package com.afterlife.rp.module.nightclub;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.module.banking.BankingService;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.economy.PendingDeliveryService;
import com.afterlife.rp.shared.items.ItemStatus;
import com.afterlife.rp.shared.items.ItemTypes;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemRepository;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nightclub business logic (§9.11): POS orders with atomic stock+payment+
 * receipts, wholesale restocking, criminal escrow with an atomic swap, and
 * anonymous escrowed bounties. Every value-moving transition is one-winner.
 */
public final class NightclubService {

    public static final String BUSINESS = "nightclub";
    public static final String ITEM_RECEIPT = "receipt";
    public static final String TREASURY = "nightclub_treasury";
    public static final String BOUNTY_ESCROW = "bounty_escrow";

    private final DatabaseManager databaseManager;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final BankingService bankingService;
    private final SerializedItemRepository itemRepository;
    private final SerializedItemService itemService;
    private final PendingDeliveryService pendingDeliveryService;
    private final AuditService auditService;
    private final NightclubConfig config;
    private final Gson gson = new Gson();

    /** Happy hour is staff-controlled and time-boxed (§9.11 manager dashboard). */
    private final AtomicLong happyHourUntilMs = new AtomicLong();

    public NightclubService(
            DatabaseManager databaseManager,
            AccountService accountService,
            LedgerService ledgerService,
            BankingService bankingService,
            SerializedItemRepository itemRepository,
            SerializedItemService itemService,
            PendingDeliveryService pendingDeliveryService,
            AuditService auditService,
            NightclubConfig config) {
        this.databaseManager = databaseManager;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.bankingService = bankingService;
        this.itemRepository = itemRepository;
        this.itemService = itemService;
        this.pendingDeliveryService = pendingDeliveryService;
        this.auditService = auditService;
        this.config = config;
    }

    public NightclubConfig config() {
        return config;
    }

    public boolean happyHourActive() {
        return System.currentTimeMillis() < happyHourUntilMs.get();
    }

    public void startHappyHour(UUID manager, String managerName) {
        happyHourUntilMs.set(System.currentTimeMillis()
                + config.happyHourDurationMinutes() * 60_000L);
        auditService.log(manager, managerName, "HAPPY_HOUR_START", BUSINESS,
                Map.of("minutes", String.valueOf(config.happyHourDurationMinutes())));
    }

    // --- stock ---

    public CompletableFuture<Void> seedStockRows() {
        return databaseManager.db().<Void>inTransaction(connection -> {
            for (Map.Entry<String, NightclubConfig.Product> entry : config.products().entrySet()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT IGNORE INTO business_stock (business, product, stock, retail_cents) "
                                + "VALUES (?, ?, 0, ?)")) {
                    statement.setString(1, BUSINESS);
                    statement.setString(2, entry.getKey());
                    statement.setLong(3, entry.getValue().retailCents());
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    public record StockRow(String product, int stock, long retailCents) {}

    public CompletableFuture<List<StockRow>> stock() {
        return databaseManager.db().supply(connection -> {
            List<StockRow> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT product, stock, retail_cents FROM business_stock WHERE business = ? "
                            + "ORDER BY product")) {
                statement.setString(1, BUSINESS);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new StockRow(rs.getString(1), rs.getInt(2), rs.getLong(3)));
                    }
                }
            }
            return rows;
        });
    }

    /** Manager price change, clamped to the staff-set limits (§9.11). */
    public CompletableFuture<Boolean> setPrice(String product, long cents, UUID manager,
            String managerName) {
        NightclubConfig.Product def = config.products().get(product);
        if (def == null || cents < def.retailMinCents() || cents > def.retailMaxCents()) {
            return CompletableFuture.completedFuture(false);
        }
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE business_stock SET retail_cents = ?, version = version + 1 "
                            + "WHERE business = ? AND product = ?")) {
                statement.setLong(1, cents);
                statement.setString(2, BUSINESS);
                statement.setString(3, product);
                return statement.executeUpdate() == 1;
            }
        }).thenApply(changed -> {
            if (changed) {
                auditService.log(manager, managerName, "PRICE_SET", product,
                        Map.of("cents", String.valueOf(cents)));
            }
            return changed;
        });
    }

    /** Wholesale restock: treasury pays now, stock arrives after the delay (§9.11). */
    public CompletableFuture<Boolean> restock(String product, int quantity, UUID manager,
            String managerName) {
        NightclubConfig.Product def = config.products().get(product);
        if (def == null || quantity < 1 || quantity > 512) {
            return CompletableFuture.completedFuture(false);
        }
        long cost = def.wholesaleCents() * quantity;
        UUID treasury = accountService.system(TREASURY).id();
        UUID government = accountService.system(AccountService.SYSTEM_GOVERNMENT).id();
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            ledgerService.apply(connection, "restock-" + UUID.randomUUID(), "WHOLESALE_SUPPLY",
                    manager, product + " x" + quantity,
                    List.of(new LedgerService.Line(treasury, -cost),
                            new LedgerService.Line(government, cost)),
                    false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO inventory_orders (id, business, product, quantity, cost_cents, "
                            + "ordered_by, deliver_at) VALUES (?, ?, ?, ?, ?, ?, "
                            + "TIMESTAMPADD(MINUTE, ?, CURRENT_TIMESTAMP(3)))")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, BUSINESS);
                statement.setString(3, product);
                statement.setInt(4, quantity);
                statement.setLong(5, cost);
                statement.setString(6, manager.toString());
                statement.setInt(7, config.restockDelayMinutes());
                statement.executeUpdate();
            }
            return true;
        }).handle((ok, e) -> {
            if (e != null) {
                LedgerService.failureFrom(e);
                return false;
            }
            auditService.log(manager, managerName, "RESTOCK_ORDERED", product,
                    Map.of("quantity", String.valueOf(quantity), "cost", String.valueOf(cost)));
            return ok;
        }).thenCompose(ok -> ok
                ? accountService.refreshBalances(treasury, government).thenApply(v -> true)
                : CompletableFuture.completedFuture(false));
    }

    /** Periodic task: due wholesale orders land in stock exactly once each. */
    public CompletableFuture<Integer> deliverDueRestocks() {
        return databaseManager.db().inTransaction(connection -> {
            int delivered = 0;
            List<String[]> due = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, product, quantity, version FROM inventory_orders "
                            + "WHERE business = ? AND state = 'PENDING' "
                            + "AND deliver_at <= CURRENT_TIMESTAMP(3)")) {
                statement.setString(1, BUSINESS);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        due.add(new String[] {rs.getString(1), rs.getString(2),
                                String.valueOf(rs.getInt(3)), String.valueOf(rs.getInt(4))});
                    }
                }
            }
            for (String[] order : due) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE inventory_orders SET state = 'DELIVERED', version = version + 1 "
                                + "WHERE id = ? AND state = 'PENDING' AND version = ?")) {
                    statement.setString(1, order[0]);
                    statement.setInt(2, Integer.parseInt(order[3]));
                    if (statement.executeUpdate() != 1) {
                        continue;
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE business_stock SET stock = stock + ?, version = version + 1 "
                                + "WHERE business = ? AND product = ?")) {
                    statement.setInt(1, Integer.parseInt(order[2]));
                    statement.setString(2, BUSINESS);
                    statement.setString(3, order[1]);
                    statement.executeUpdate();
                }
                delivered++;
            }
            return delivered;
        });
    }

    // --- POS (§9.11): propose -> customer accepts -> atomic settlement ---

    public record OrderLine(String product, int quantity) {}

    public CompletableFuture<Optional<UUID>> proposeOrder(UUID bartender, UUID customer,
            List<OrderLine> lines) {
        return databaseManager.db().supply(connection -> {
            long total = 0;
            JsonArray json = new JsonArray();
            boolean happyHour = happyHourActive();
            for (OrderLine line : lines) {
                if (!config.products().containsKey(line.product()) || line.quantity() < 1
                        || line.quantity() > 16) {
                    return Optional.<UUID>empty();
                }
                long retail;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT retail_cents FROM business_stock WHERE business = ? AND product = ?")) {
                    statement.setString(1, BUSINESS);
                    statement.setString(2, line.product());
                    try (ResultSet rs = statement.executeQuery()) {
                        if (!rs.next()) {
                            return Optional.<UUID>empty();
                        }
                        retail = rs.getLong(1);
                    }
                }
                long unit = config.priceWithDiscount(retail, happyHour);
                total += unit * line.quantity();
                JsonObject entry = new JsonObject();
                entry.addProperty("product", line.product());
                entry.addProperty("quantity", line.quantity());
                entry.addProperty("unit_cents", unit);
                json.add(entry);
            }
            UUID orderId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO business_orders (id, customer_uuid, employee_uuid, order_lines, "
                            + "total_cents) VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, orderId.toString());
                statement.setString(2, customer.toString());
                statement.setString(3, bartender.toString());
                statement.setString(4, gson.toJson(json));
                statement.setLong(5, total);
                statement.executeUpdate();
            }
            return Optional.of(orderId);
        });
    }

    public record OrderView(UUID id, UUID customer, UUID employee, long totalCents,
            List<OrderLine> lines) {}

    public CompletableFuture<Optional<OrderView>> pendingOrderFor(UUID customer) {
        return databaseManager.db().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, customer_uuid, employee_uuid, order_lines, total_cents "
                            + "FROM business_orders "
                            + "WHERE customer_uuid = ? AND state = 'PROPOSED' "
                            + "AND created_at > TIMESTAMPADD(SECOND, -?, CURRENT_TIMESTAMP(3)) "
                            + "ORDER BY created_at DESC LIMIT 1")) {
                statement.setString(1, customer.toString());
                statement.setInt(2, config.orderTimeoutSeconds());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    List<OrderLine> lines = new ArrayList<>();
                    JsonArray json = gson.fromJson(rs.getString("order_lines"), JsonArray.class);
                    json.forEach(element -> {
                        JsonObject entry = element.getAsJsonObject();
                        lines.add(new OrderLine(entry.get("product").getAsString(),
                                entry.get("quantity").getAsInt()));
                    });
                    return Optional.of(new OrderView(UUID.fromString(rs.getString("id")),
                            UUID.fromString(rs.getString("customer_uuid")),
                            UUID.fromString(rs.getString("employee_uuid")),
                            rs.getLong("total_cents"), lines));
                }
            }
        });
    }

    public enum PosResult { COMPLETED, NOT_PROPOSED, OUT_OF_STOCK, PAYMENT_FAILED }

    public record PosOutcome(PosResult result, List<SerializedItem> drinks,
            SerializedItem customerReceipt, SerializedItem bartenderReceipt, long totalCents) {}

    /**
     * Customer acceptance settles everything in ONE transaction: order state
     * (one winner), stock decrements (guarded non-negative), payment with
     * commission, drinks, and both receipts (§9.11 POS use case).
     */
    public CompletableFuture<PosOutcome> acceptOrder(OrderView order, UUID customerAccountId,
            UUID bartenderAccountId, int commissionPercent) {
        UUID treasury = accountService.system(TREASURY).id();
        return databaseManager.db().<PosOutcome>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE business_orders SET state = 'ACCEPTED', version = version + 1 "
                            + "WHERE id = ? AND state = 'PROPOSED'")) {
                statement.setString(1, order.id().toString());
                if (statement.executeUpdate() != 1) {
                    return new PosOutcome(PosResult.NOT_PROPOSED, List.of(), null, null, 0);
                }
            }
            for (OrderLine line : order.lines()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE business_stock SET stock = stock - ?, version = version + 1 "
                                + "WHERE business = ? AND product = ? AND stock >= ?")) {
                    statement.setInt(1, line.quantity());
                    statement.setString(2, BUSINESS);
                    statement.setString(3, line.product());
                    statement.setInt(4, line.quantity());
                    if (statement.executeUpdate() != 1) {
                        throw new LedgerService.LedgerAbort(LedgerService.Status.INVALID);
                    }
                }
            }
            long commission = order.totalCents() * commissionPercent / 100;
            LedgerService.Result payment = ledgerService.apply(connection,
                    "pos-" + order.id(), "POS_SALE", order.employee(), null,
                    List.of(new LedgerService.Line(customerAccountId, -order.totalCents()),
                            new LedgerService.Line(treasury, order.totalCents() - commission),
                            new LedgerService.Line(bartenderAccountId, commission)),
                    false);
            List<SerializedItem> drinks = new ArrayList<>();
            for (OrderLine line : order.lines()) {
                for (int i = 0; i < line.quantity(); i++) {
                    JsonObject metadata = new JsonObject();
                    metadata.addProperty("quality", "NORMAL");
                    SerializedItem drink = new SerializedItem(UUID.randomUUID(), line.product(),
                            order.customer(), null, ItemStatus.ISSUED, order.employee(),
                            System.currentTimeMillis(), gson.toJson(metadata));
                    itemRepository.insert(connection, drink);
                    drinks.add(drink);
                }
            }
            SerializedItem customerReceipt = insertReceipt(connection, order,
                    payment.transactionId(), order.customer());
            SerializedItem bartenderReceipt = insertReceipt(connection, order,
                    payment.transactionId(), order.employee());
            return new PosOutcome(PosResult.COMPLETED, drinks, customerReceipt, bartenderReceipt,
                    order.totalCents());
        }).handle((outcome, e) -> {
            if (e != null) {
                LedgerService.Status status = LedgerService.failureFrom(e).status();
                return new PosOutcome(status == LedgerService.Status.INVALID
                        ? PosResult.OUT_OF_STOCK : PosResult.PAYMENT_FAILED,
                        List.of(), null, null, 0);
            }
            return outcome;
        }).thenCompose(outcome -> outcome.result() == PosResult.COMPLETED
                ? accountService.refreshBalances(customerAccountId, bartenderAccountId, treasury)
                        .thenApply(v -> outcome)
                : CompletableFuture.completedFuture(outcome));
    }

    private SerializedItem insertReceipt(java.sql.Connection connection, OrderView order,
            UUID transactionId, UUID owner) throws java.sql.SQLException {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("order", order.id().toString());
        metadata.addProperty("tx", transactionId.toString());
        SerializedItem receipt = new SerializedItem(UUID.randomUUID(), ITEM_RECEIPT, owner,
                order.totalCents(), ItemStatus.ISSUED, order.employee(),
                System.currentTimeMillis(), gson.toJson(metadata));
        itemRepository.insert(connection, receipt);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO receipts (order_id, transaction_id, item_serial) VALUES (?, ?, ?)")) {
            statement.setString(1, order.id().toString());
            statement.setString(2, transactionId.toString());
            statement.setString(3, receipt.serial().toString());
            statement.executeUpdate();
        }
        return receipt;
    }

    public CompletableFuture<Boolean> declineOrder(UUID orderId) {
        return databaseManager.db().inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE business_orders SET state = 'DECLINED', version = version + 1 "
                            + "WHERE id = ? AND state = 'PROPOSED'")) {
                statement.setString(1, orderId.toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    /** Shaker: converts one stock unit into a drink of the rolled quality. */
    public CompletableFuture<Optional<SerializedItem>> mixDrink(UUID bartender, String product,
            String quality) {
        if (!config.products().containsKey(product)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return databaseManager.db().<Optional<SerializedItem>>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE business_stock SET stock = stock - 1, version = version + 1 "
                            + "WHERE business = ? AND product = ? AND stock >= 1")) {
                statement.setString(1, BUSINESS);
                statement.setString(2, product);
                if (statement.executeUpdate() != 1) {
                    return Optional.empty();
                }
            }
            JsonObject metadata = new JsonObject();
            metadata.addProperty("quality", quality);
            SerializedItem drink = new SerializedItem(UUID.randomUUID(), product, bartender, null,
                    ItemStatus.ISSUED, bartender, System.currentTimeMillis(), gson.toJson(metadata));
            itemRepository.insert(connection, drink);
            return Optional.of(drink);
        });
    }

    public String qualityFromMetadata(String metadata) {
        if (metadata == null) {
            return "NORMAL";
        }
        JsonObject json = gson.fromJson(metadata, JsonObject.class);
        return json != null && json.has("quality") ? json.get("quality").getAsString() : "NORMAL";
    }

    // --- criminal escrow (§9.11): atomic swap, safe cancel/timeout ---

    public record EscrowDeal(UUID id, UUID bartender, UUID partyA, UUID partyB, long agreedCents,
            List<UUID> aItemSerials, long bCollectedCents, boolean aLocked, boolean bLocked,
            String state, int version) {}

    public CompletableFuture<UUID> createEscrow(UUID bartender, String bartenderName, UUID partyA,
            UUID partyB, long agreedCents) {
        UUID id = UUID.randomUUID();
        return databaseManager.db().<UUID>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO escrow_deals (id, bartender_uuid, party_a_uuid, party_b_uuid, "
                            + "agreed_cents) VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, id.toString());
                statement.setString(2, bartender.toString());
                statement.setString(3, partyA.toString());
                statement.setString(4, partyB.toString());
                statement.setLong(5, agreedCents);
                statement.executeUpdate();
            }
            return id;
        }).thenApply(created -> {
            auditService.log(bartender, bartenderName, "ESCROW_CREATE", id.toString(),
                    Map.of("party_a", partyA.toString(), "party_b", partyB.toString(),
                            "cents", String.valueOf(agreedCents)));
            return created;
        });
    }

    public CompletableFuture<Optional<EscrowDeal>> openEscrowFor(UUID participant) {
        return databaseManager.db().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM escrow_deals WHERE state IN ('OPEN','LOCKED') "
                            + "AND (party_a_uuid = ? OR party_b_uuid = ? OR bartender_uuid = ?) "
                            + "ORDER BY created_at DESC LIMIT 1")) {
                statement.setString(1, participant.toString());
                statement.setString(2, participant.toString());
                statement.setString(3, participant.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapEscrow(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Party A deposits one serialized item: physically removed, serial recorded. */
    public CompletableFuture<Boolean> escrowDepositItem(EscrowDeal deal, UUID depositor,
            UUID itemSerial) {
        if (!deal.partyA().equals(depositor) || deal.aLocked() || !"OPEN".equals(deal.state())) {
            return CompletableFuture.completedFuture(false);
        }
        return databaseManager.db().inTransaction(connection -> {
            var record = itemRepository.find(connection, itemSerial);
            if (record.isEmpty() || record.get().status() != ItemStatus.ISSUED) {
                return false;
            }
            List<UUID> serials = new ArrayList<>(deal.aItemSerials());
            if (serials.contains(itemSerial)) {
                return false;
            }
            serials.add(itemSerial);
            JsonArray json = new JsonArray();
            serials.forEach(serial -> json.add(serial.toString()));
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE escrow_deals SET a_item_serials = ?, version = version + 1 "
                            + "WHERE id = ? AND version = ? AND a_locked = 0 AND state = 'OPEN'")) {
                statement.setString(1, gson.toJson(json));
                statement.setString(2, deal.id().toString());
                statement.setInt(3, deal.version());
                return statement.executeUpdate() == 1;
            }
        });
    }

    /** Party B pays in physical dirty notes; each consumed serial redeems once. */
    public CompletableFuture<Long> escrowDepositDirty(EscrowDeal deal, UUID depositor,
            List<SerializedItemService.PdcData> offeredNotes) {
        if (!deal.partyB().equals(depositor) || deal.bLocked() || !"OPEN".equals(deal.state())) {
            return CompletableFuture.completedFuture(0L);
        }
        return databaseManager.db().<Long>inTransaction(connection -> {
            long needed = deal.agreedCents() - deal.bCollectedCents();
            long collected = 0;
            for (SerializedItemService.PdcData note : offeredNotes) {
                if (collected >= needed) {
                    break;
                }
                var record = itemRepository.find(connection, note.serial());
                if (record.isEmpty() || record.get().denomination() == null
                        || record.get().status() != ItemStatus.ISSUED
                        || !ItemTypes.DIRTY_MONEY.equals(record.get().itemType())) {
                    continue;
                }
                if (itemRepository.transition(connection, note.serial(),
                        ItemStatus.ISSUED, ItemStatus.REDEEMED)) {
                    collected += record.get().denomination();
                }
            }
            if (collected > 0) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE escrow_deals SET b_collected_cents = b_collected_cents + ?, "
                                + "version = version + 1 WHERE id = ? AND b_locked = 0 "
                                + "AND state = 'OPEN'")) {
                    statement.setLong(1, collected);
                    statement.setString(2, deal.id().toString());
                    if (statement.executeUpdate() != 1) {
                        throw new LedgerService.LedgerAbort(LedgerService.Status.INVALID);
                    }
                }
            }
            return collected;
        }).handle((collected, e) -> e != null ? 0L : collected);
    }

    /** Locks one side; when both are locked the deal moves to LOCKED. */
    public CompletableFuture<Boolean> escrowLock(EscrowDeal deal, UUID party) {
        boolean isA = deal.partyA().equals(party);
        boolean isB = deal.partyB().equals(party);
        if (!isA && !isB) {
            return CompletableFuture.completedFuture(false);
        }
        if (isB && deal.bCollectedCents() < deal.agreedCents()) {
            return CompletableFuture.completedFuture(false);
        }
        return databaseManager.db().inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE escrow_deals SET " + (isA ? "a_locked = 1" : "b_locked = 1")
                            + ", state = CASE WHEN " + (isA ? "b_locked = 1" : "a_locked = 1")
                            + " THEN 'LOCKED' ELSE state END, version = version + 1 "
                            + "WHERE id = ? AND state = 'OPEN' AND " + (isA ? "a_locked" : "b_locked")
                            + " = 0")) {
                statement.setString(1, deal.id().toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    public enum EscrowResult { COMPLETED, NOT_LOCKED, RACE_LOST }

    /**
     * The bartender confirms: ownership swaps atomically and exactly once —
     * items to B, dirty value minus commission to A, commission to the
     * bartender, all as durable pending deliveries (§9.11 exit gate).
     */
    public CompletableFuture<EscrowResult> escrowConfirm(EscrowDeal deal, UUID bartender,
            String bartenderName) {
        if (!deal.bartender().equals(bartender)) {
            return CompletableFuture.completedFuture(EscrowResult.RACE_LOST);
        }
        if (!"LOCKED".equals(deal.state())) {
            return CompletableFuture.completedFuture(EscrowResult.NOT_LOCKED);
        }
        long commission = deal.bCollectedCents() * config.escrowCommissionPercent() / 100;
        long toA = deal.bCollectedCents() - commission;
        return databaseManager.db().<EscrowResult>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE escrow_deals SET state = 'COMPLETED', commission_cents = ?, "
                            + "version = version + 1 WHERE id = ? AND state = 'LOCKED'")) {
                statement.setLong(1, commission);
                statement.setString(2, deal.id().toString());
                if (statement.executeUpdate() != 1) {
                    return EscrowResult.RACE_LOST;
                }
            }
            // Item ownership flips to B; physical delivery is durable.
            for (UUID serial : deal.aItemSerials()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE serialized_items SET owner_uuid = ?, version = version + 1 "
                                + "WHERE serial = ? AND status = 'ISSUED'")) {
                    statement.setString(1, deal.partyB().toString());
                    statement.setString(2, serial.toString());
                    statement.executeUpdate();
                }
            }
            return EscrowResult.COMPLETED;
        }).thenCompose(result -> {
            if (result != EscrowResult.COMPLETED) {
                return CompletableFuture.completedFuture(result);
            }
            auditService.log(bartender, bartenderName, "ESCROW_COMPLETED", deal.id().toString(),
                    Map.of("commission", String.valueOf(commission)));
            CompletableFuture<?> aPayout = toA > 0
                    ? bankingService.issueDirty(deal.partyA(), roundToNote(toA), bartender)
                            .thenCompose(notes -> queueDirtyNotes(deal.partyA(), notes, "ESCROW_PAYOUT"))
                    : CompletableFuture.completedFuture(null);
            CompletableFuture<?> fee = commission > 0 && roundToNote(commission) > 0
                    ? bankingService.issueDirty(bartender, roundToNote(commission), bartender)
                            .thenCompose(notes -> queueDirtyNotes(bartender, notes, "ESCROW_FEE"))
                    : CompletableFuture.completedFuture(null);
            return CompletableFuture.allOf(aPayout, fee).thenApply(v -> result);
        });
    }

    /** Either side cancels before both deposits lock; deposits return once. */
    public CompletableFuture<Boolean> escrowCancel(EscrowDeal deal, UUID actor, String actorName) {
        if ("LOCKED".equals(deal.state())) {
            return CompletableFuture.completedFuture(false);
        }
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE escrow_deals SET state = 'CANCELLED', version = version + 1 "
                            + "WHERE id = ? AND state = 'OPEN'")) {
                statement.setString(1, deal.id().toString());
                return statement.executeUpdate() == 1;
            }
        }).thenCompose(cancelled -> {
            if (!cancelled) {
                return CompletableFuture.completedFuture(false);
            }
            auditService.log(actor, actorName, "ESCROW_CANCELLED", deal.id().toString(), Map.of());
            // A's items go back to A (owners never changed); B's dirty value reissues.
            CompletableFuture<?> back = deal.bCollectedCents() > 0
                    ? bankingService.issueDirty(deal.partyB(), roundToNote(deal.bCollectedCents()),
                                    actor)
                            .thenCompose(notes -> queueDirtyNotes(deal.partyB(), notes,
                                    "ESCROW_REFUND"))
                    : CompletableFuture.completedFuture(null);
            CompletableFuture<Void> items = CompletableFuture.completedFuture(null);
            for (UUID serial : deal.aItemSerials()) {
                items = items.thenCompose(v -> pendingDeliveryService.insertStandalone(
                        deal.partyA(), "escrow_item:" + serial, null, 1, "ESCROW_RETURN", null));
            }
            return CompletableFuture.allOf(back, items).thenApply(v -> true);
        });
    }

    /** Timeout sweep: stale OPEN deals cancel and return deposits once (§9.11). */
    public CompletableFuture<Integer> expireStaleEscrows() {
        return databaseManager.db().supply(connection -> {
            List<EscrowDeal> stale = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM escrow_deals WHERE state = 'OPEN' "
                            + "AND created_at < TIMESTAMPADD(MINUTE, -?, CURRENT_TIMESTAMP(3))")) {
                statement.setInt(1, config.escrowTimeoutMinutes());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        stale.add(mapEscrow(rs));
                    }
                }
            }
            return stale;
        }).thenCompose(stale -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (EscrowDeal deal : stale) {
                chain = chain.thenCompose(v ->
                        escrowCancel(deal, deal.bartender(), "SYSTEM_TIMEOUT").thenApply(x -> null));
            }
            return chain.thenApply(v -> stale.size());
        });
    }

    private CompletableFuture<Void> queueDirtyNotes(UUID recipient, List<SerializedItem> notes,
            String reason) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (SerializedItem note : notes) {
            chain = chain.thenCompose(v -> pendingDeliveryService.insertStandalone(
                    recipient, "escrow_note:" + note.serial(), note.denomination(), 1, reason, null));
        }
        return chain;
    }

    private long roundToNote(long cents) {
        return cents - (cents % 500);
    }

    private EscrowDeal mapEscrow(ResultSet rs) throws java.sql.SQLException {
        List<UUID> serials = new ArrayList<>();
        String raw = rs.getString("a_item_serials");
        if (raw != null) {
            JsonArray json = gson.fromJson(raw, JsonArray.class);
            if (json != null) {
                json.forEach(element -> serials.add(UUID.fromString(element.getAsString())));
            }
        }
        return new EscrowDeal(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("bartender_uuid")),
                UUID.fromString(rs.getString("party_a_uuid")),
                UUID.fromString(rs.getString("party_b_uuid")),
                rs.getLong("agreed_cents"),
                serials,
                rs.getLong("b_collected_cents"),
                rs.getBoolean("a_locked"),
                rs.getBoolean("b_locked"),
                rs.getString("state"),
                rs.getInt("version"));
    }

    // --- bounties (§9.11): escrowed at creation, sponsor hidden, audited ---

    public CompletableFuture<Optional<Long>> createBounty(UUID sponsor, String sponsorName,
            UUID sponsorAccountId, UUID target, long amountCents) {
        if (amountCents < config.bountyMinCents()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        UUID escrow = accountService.system(BOUNTY_ESCROW).id();
        return databaseManager.db().<Optional<Long>>inTransaction(connection -> {
            ledgerService.apply(connection, "bounty-" + UUID.randomUUID(), "BOUNTY_ESCROW",
                    sponsor, null,
                    List.of(new LedgerService.Line(sponsorAccountId, -amountCents),
                            new LedgerService.Line(escrow, amountCents)),
                    false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO bounties (sponsor_uuid, target_uuid, amount_cents) VALUES (?, ?, ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, sponsor.toString());
                statement.setString(2, target.toString());
                statement.setLong(3, amountCents);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return Optional.of(keys.getLong(1));
                }
            }
        }).handle((result, e) -> {
            if (e != null) {
                LedgerService.failureFrom(e);
                return Optional.<Long>empty();
            }
            // The audit trail keeps the sponsor even though players never see them.
            result.ifPresent(id -> auditService.log(sponsor, sponsorName, "BOUNTY_CREATE",
                    String.valueOf(id), Map.of("target", target.toString(),
                            "cents", String.valueOf(amountCents))));
            return result;
        }).thenCompose(result -> result.isPresent()
                ? accountService.refreshBalances(sponsorAccountId, escrow).thenApply(v -> result)
                : CompletableFuture.completedFuture(result));
    }

    public record BountyView(long id, UUID target, long amountCents) {}

    /** Player-facing list: target and amount only — never the sponsor (§9.11). */
    public CompletableFuture<List<BountyView>> openBounties() {
        return databaseManager.db().supply(connection -> {
            List<BountyView> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, target_uuid, amount_cents FROM bounties WHERE state = 'OPEN'")) {
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(new BountyView(rs.getLong(1),
                                UUID.fromString(rs.getString(2)), rs.getLong(3)));
                    }
                }
            }
            return result;
        });
    }

    public enum BountyPayResult { COMPLETED, NOT_OPEN, PAYMENT_FAILED }

    /** Bartender-confirmed payout per staff policy: pays once, fee retained. */
    public CompletableFuture<BountyPayResult> payBounty(long bountyId, UUID claimantAccountId,
            UUID bartenderAccountId, UUID claimant, UUID bartender, String bartenderName) {
        UUID escrow = accountService.system(BOUNTY_ESCROW).id();
        return databaseManager.db().<BountyPayResult>inTransaction(connection -> {
            long amount;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT amount_cents FROM bounties WHERE id = ? AND state = 'OPEN'")) {
                statement.setLong(1, bountyId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return BountyPayResult.NOT_OPEN;
                    }
                    amount = rs.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE bounties SET state = 'PAID', paid_to = ?, version = version + 1 "
                            + "WHERE id = ? AND state = 'OPEN'")) {
                statement.setString(1, claimant.toString());
                statement.setLong(2, bountyId);
                if (statement.executeUpdate() != 1) {
                    return BountyPayResult.NOT_OPEN;
                }
            }
            long fee = amount * config.bountyFeePercent() / 100;
            ledgerService.apply(connection, "bountypay-" + bountyId, "BOUNTY_PAYOUT",
                    bartender, null,
                    List.of(new LedgerService.Line(escrow, -amount),
                            new LedgerService.Line(claimantAccountId, amount - fee),
                            new LedgerService.Line(bartenderAccountId, fee)),
                    false);
            return BountyPayResult.COMPLETED;
        }).handle((result, e) -> {
            if (e != null) {
                LedgerService.failureFrom(e);
                return BountyPayResult.PAYMENT_FAILED;
            }
            if (result == BountyPayResult.COMPLETED) {
                auditService.log(bartender, bartenderName, "BOUNTY_PAID", String.valueOf(bountyId),
                        Map.of("claimant", claimant.toString()));
            }
            return result;
        }).thenCompose(result -> result == BountyPayResult.COMPLETED
                ? accountService.refreshBalances(claimantAccountId, bartenderAccountId, escrow)
                        .thenApply(v -> result)
                : CompletableFuture.completedFuture(result));
    }

    // --- blacklist (§9.11) ---

    public CompletableFuture<Boolean> blacklistAdd(UUID player, String reason, UUID actor,
            String actorName) {
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT IGNORE INTO nightclub_blacklist (player_uuid, reason, added_by) "
                            + "VALUES (?, ?, ?)")) {
                statement.setString(1, player.toString());
                statement.setString(2, reason);
                statement.setString(3, actor.toString());
                return statement.executeUpdate() == 1;
            }
        }).thenApply(added -> {
            if (added) {
                auditService.log(actor, actorName, "BLACKLIST_ADD", player.toString(),
                        Map.of("reason", reason));
            }
            return added;
        });
    }

    public CompletableFuture<Boolean> blacklistRemove(UUID player, UUID actor, String actorName) {
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM nightclub_blacklist WHERE player_uuid = ?")) {
                statement.setString(1, player.toString());
                return statement.executeUpdate() == 1;
            }
        }).thenApply(removed -> {
            if (removed) {
                auditService.log(actor, actorName, "BLACKLIST_REMOVE", player.toString(), Map.of());
            }
            return removed;
        });
    }

    public CompletableFuture<List<UUID>> blacklist() {
        return databaseManager.db().supply(connection -> {
            List<UUID> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT player_uuid FROM nightclub_blacklist")) {
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(UUID.fromString(rs.getString(1)));
                    }
                }
            }
            return result;
        });
    }

    // --- employees (§9.11 manager dashboard) ---

    public CompletableFuture<Boolean> hire(UUID player, String role, int commissionPercent,
            UUID manager, String managerName) {
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO nightclub_employees (player_uuid, role, commission_percent, hired_by) "
                            + "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE role = VALUES(role), "
                            + "commission_percent = VALUES(commission_percent), state = 'ACTIVE', "
                            + "version = version + 1")) {
                statement.setString(1, player.toString());
                statement.setString(2, role);
                statement.setInt(3, commissionPercent);
                statement.setString(4, manager.toString());
                statement.executeUpdate();
            }
            return true;
        }).thenApply(hired -> {
            auditService.log(manager, managerName, "NIGHTCLUB_HIRE", player.toString(),
                    Map.of("role", role, "commission", String.valueOf(commissionPercent)));
            return hired;
        });
    }

    public CompletableFuture<Boolean> fire(UUID player, UUID manager, String managerName) {
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE nightclub_employees SET state = 'FIRED', version = version + 1 "
                            + "WHERE player_uuid = ? AND state = 'ACTIVE'")) {
                statement.setString(1, player.toString());
                return statement.executeUpdate() == 1;
            }
        }).thenApply(fired -> {
            if (fired) {
                auditService.log(manager, managerName, "NIGHTCLUB_FIRE", player.toString(), Map.of());
            }
            return fired;
        });
    }

    public CompletableFuture<Integer> commissionPercentFor(UUID employee) {
        return databaseManager.db().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT commission_percent FROM nightclub_employees "
                            + "WHERE player_uuid = ? AND state = 'ACTIVE'")) {
                statement.setString(1, employee.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : config.defaultCommissionPercent();
                }
            }
        });
    }
}
