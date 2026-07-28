package com.afterlife.rp.module.realestate;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.economy.PendingDeliveryService;
import com.afterlife.rp.shared.items.ItemStatus;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemRepository;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Real-estate module (§9.7): legal sales with serialized keys and lock
 * versions, dirty rentals paid in physical dirty money, confidential black
 * files, the director-only black safe, and power anomalies. Ownership and lock
 * changes are single-transaction and race-safe (M3 exit gate).
 */
public final class RealEstateService {

    public static final String ITEM_TYPE_KEY = "property_key";
    public static final String ITEM_TYPE_BLACK_FILE = "black_file";
    public static final String BLACK_SAFE_ID = "realestate";

    public enum SaleResult { COMPLETED, NOT_AVAILABLE, PAYMENT_FAILED, NOT_FOUND }

    public record DirtyRentResult(boolean completed, long collectedCents, List<UUID> consumedSerials,
            SerializedItem fileItem) {}

    private final DatabaseManager databaseManager;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final SerializedItemRepository itemRepository;
    private final PendingDeliveryService pendingDeliveryService;
    private final AuditService auditService;
    private final RealEstateConfig config;
    private final Gson gson = new Gson();

    public RealEstateService(
            DatabaseManager databaseManager,
            AccountService accountService,
            LedgerService ledgerService,
            SerializedItemRepository itemRepository,
            PendingDeliveryService pendingDeliveryService,
            AuditService auditService,
            RealEstateConfig config) {
        this.databaseManager = databaseManager;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.itemRepository = itemRepository;
        this.pendingDeliveryService = pendingDeliveryService;
        this.auditService = auditService;
        this.config = config;
    }

    public RealEstateConfig config() {
        return config;
    }

    // --- registry ---

    public CompletableFuture<Property> createProperty(String name, String type, String world,
            double x, double y, double z, String regionId, long priceCents, boolean dirty,
            UUID actor, String actorName) {
        Property property = new Property(UUID.randomUUID(), name, type, world, x, y, z, regionId,
                priceCents, dirty, dirty ? "DIRTY_AVAILABLE" : "AVAILABLE", 1, 0);
        return databaseManager.db().<Property>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO properties (id, name, type, world, x, y, z, region_id, price, dirty, state) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, property.id().toString());
                statement.setString(2, property.name());
                statement.setString(3, property.type());
                statement.setString(4, property.world());
                statement.setDouble(5, property.x());
                statement.setDouble(6, property.y());
                statement.setDouble(7, property.z());
                statement.setString(8, property.regionId());
                statement.setLong(9, property.price());
                statement.setBoolean(10, property.dirty());
                statement.setString(11, property.state());
                statement.executeUpdate();
            }
            return property;
        }).thenApply(saved -> {
            auditService.log(actor, actorName, "PROPERTY_CREATE", saved.name(), Map.of(
                    "type", saved.type(), "price", String.valueOf(saved.price()),
                    "dirty", String.valueOf(saved.dirty())));
            return saved;
        });
    }

    public CompletableFuture<List<Property>> listByState(String state) {
        return databaseManager.db().supply(connection -> {
            List<Property> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM properties WHERE state = ? ORDER BY name")) {
                statement.setString(1, state);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(map(rs));
                    }
                }
            }
            return result;
        });
    }

    public CompletableFuture<Optional<Property>> byName(String name) {
        return databaseManager.db().supply(connection -> findByName(connection, name));
    }

    // --- legal sale (§9.7): atomic ownership + payment + key ---

    public record SaleOutcome(SaleResult result, SerializedItem key) {}

    public CompletableFuture<SaleOutcome> sell(String propertyName, UUID buyer, UUID buyerAccountId,
            UUID agent, String agentName) {
        Optional<UUID> revenue = Optional.ofNullable(
                accountService.system(config.revenueAccountCode()).id());
        return databaseManager.db().<SaleOutcome>inTransaction(connection -> {
            Optional<Property> found = findByName(connection, propertyName);
            if (found.isEmpty()) {
                return new SaleOutcome(SaleResult.NOT_FOUND, null);
            }
            Property property = found.get();
            // One-winner transition: a concurrent sale loses this UPDATE.
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE properties SET state = 'OWNED', version = version + 1 "
                            + "WHERE id = ? AND state = 'AVAILABLE' AND version = ?")) {
                statement.setString(1, property.id().toString());
                statement.setInt(2, property.version());
                if (statement.executeUpdate() != 1) {
                    return new SaleOutcome(SaleResult.NOT_AVAILABLE, null);
                }
            }
            ledgerService.apply(connection, "prop-" + property.id() + "-" + UUID.randomUUID(),
                    "PROPERTY_SALE", agent, property.name(),
                    List.of(new LedgerService.Line(buyerAccountId, -property.price()),
                            new LedgerService.Line(revenue.orElseThrow(), property.price())),
                    false);
            insertOwnership(connection, property.id(), buyer, "OWNER");
            SerializedItem key = insertKey(connection, property, buyer);
            return new SaleOutcome(SaleResult.COMPLETED, key);
        }).handle((outcome, e) -> {
            if (e != null) {
                LedgerService.failureFrom(e);
                return new SaleOutcome(SaleResult.PAYMENT_FAILED, null);
            }
            if (outcome.result() == SaleResult.COMPLETED) {
                auditService.log(agent, agentName, "PROPERTY_SALE", propertyName,
                        Map.of("buyer", buyer.toString()));
            }
            return outcome;
        });
    }

    // --- lock change (§9.7): bump revokes all earlier keys ---

    public record LockChange(boolean changed, SerializedItem newKey, UUID currentOwner) {}

    public CompletableFuture<LockChange> changeLock(String propertyName, String reason,
            UUID agent, String agentName) {
        return databaseManager.db().<LockChange>inTransaction(connection -> {
            Optional<Property> found = findByName(connection, propertyName);
            if (found.isEmpty()) {
                return new LockChange(false, null, null);
            }
            Property property = found.get();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE properties SET lock_version = lock_version + 1, version = version + 1 "
                            + "WHERE id = ? AND version = ?")) {
                statement.setString(1, property.id().toString());
                statement.setInt(2, property.version());
                if (statement.executeUpdate() != 1) {
                    return new LockChange(false, null, null);
                }
            }
            Optional<UUID> owner = currentOwner(connection, property.id());
            SerializedItem newKey = null;
            if (owner.isPresent()) {
                Property bumped = new Property(property.id(), property.name(), property.type(),
                        property.world(), property.x(), property.y(), property.z(),
                        property.regionId(), property.price(), property.dirty(), property.state(),
                        property.lockVersion() + 1, property.version() + 1);
                newKey = insertKey(connection, bumped, owner.get());
            }
            return new LockChange(true, newKey, owner.orElse(null));
        }).thenApply(change -> {
            if (change.changed()) {
                auditService.log(agent, agentName, "LOCK_CHANGE", propertyName,
                        Map.of("reason", reason));
            }
            return change;
        });
    }

    /** A key opens only if it matches the property and its CURRENT lock version. */
    public CompletableFuture<Boolean> keyValid(SerializedItemService.PdcData keyData) {
        if (!ITEM_TYPE_KEY.equals(keyData.itemType())) {
            return CompletableFuture.completedFuture(false);
        }
        return databaseManager.db().supply(connection -> {
            Optional<SerializedItem> record = itemRepository.find(connection, keyData.serial());
            if (record.isEmpty() || record.get().status() != ItemStatus.ISSUED
                    || record.get().metadata() == null) {
                return false;
            }
            JsonObject meta = gson.fromJson(record.get().metadata(), JsonObject.class);
            if (meta == null || !meta.has("property") || !meta.has("lock_version")) {
                return false;
            }
            UUID propertyId = UUID.fromString(meta.get("property").getAsString());
            int keyLockVersion = meta.get("lock_version").getAsInt();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT lock_version FROM properties WHERE id = ?")) {
                statement.setString(1, propertyId.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() && rs.getInt("lock_version") == keyLockVersion;
                }
            }
        });
    }

    // --- dirty rental (§9.7): physical dirty money -> black safe + black file ---

    public CompletableFuture<DirtyRentResult> rentDirty(String propertyName, UUID tenant,
            List<SerializedItemService.PdcData> offeredDirtyNotes, UUID agent, String agentName) {
        return databaseManager.db().<DirtyRentResult>inTransaction(connection -> {
            Optional<Property> found = findByName(connection, propertyName);
            if (found.isEmpty() || !found.get().dirty()) {
                return new DirtyRentResult(false, 0, List.of(), null);
            }
            Property property = found.get();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE properties SET state = 'DIRTY_RENTED', version = version + 1 "
                            + "WHERE id = ? AND state = 'DIRTY_AVAILABLE' AND version = ?")) {
                statement.setString(1, property.id().toString());
                statement.setInt(2, property.version());
                if (statement.executeUpdate() != 1) {
                    return new DirtyRentResult(false, 0, List.of(), null);
                }
            }
            // Consume offered dirty notes (each redeems exactly once) until the
            // price is covered; a copied serial simply fails its transition.
            long collected = 0;
            List<UUID> consumed = new ArrayList<>();
            for (SerializedItemService.PdcData note : offeredDirtyNotes) {
                if (collected >= property.price()) {
                    break;
                }
                Optional<SerializedItem> record = itemRepository.find(connection, note.serial());
                if (record.isEmpty() || record.get().denomination() == null
                        || record.get().status() != ItemStatus.ISSUED
                        || !"dirty_money".equals(record.get().itemType())) {
                    continue;
                }
                if (itemRepository.transition(connection, note.serial(),
                        ItemStatus.ISSUED, ItemStatus.REDEEMED)) {
                    collected += record.get().denomination();
                    consumed.add(note.serial());
                }
            }
            if (collected < property.price()) {
                throw new LedgerService.LedgerAbort(LedgerService.Status.INSUFFICIENT_FUNDS);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE black_safes SET dirty_cents = dirty_cents + ?, version = version + 1 "
                            + "WHERE id = ?")) {
                statement.setLong(1, collected);
                statement.setString(2, BLACK_SAFE_ID);
                statement.executeUpdate();
            }
            insertOwnership(connection, property.id(), tenant, "TENANT");

            JsonObject metadata = new JsonObject();
            metadata.addProperty("property", property.id().toString());
            SerializedItem file = new SerializedItem(UUID.randomUUID(), ITEM_TYPE_BLACK_FILE, agent,
                    null, ItemStatus.ISSUED, agent, System.currentTimeMillis(), gson.toJson(metadata));
            itemRepository.insert(connection, file);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO black_property_files (id, property_id, item_serial) VALUES (?, ?, ?)")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, property.id().toString());
                statement.setString(3, file.serial().toString());
                statement.executeUpdate();
            }
            return new DirtyRentResult(true, collected, consumed, file);
        }).handle((result, e) -> {
            if (e != null) {
                LedgerService.failureFrom(e);
                return new DirtyRentResult(false, 0, List.of(), null);
            }
            if (result.completed()) {
                // Deliberately NOT in the police-facing registry; the black file is
                // the only trace besides this audit row (§9.7).
                auditService.log(agent, agentName, "DIRTY_RENT", propertyName,
                        Map.of("collected_cents", String.valueOf(result.collectedCents())));
            }
            return result;
        });
    }

    /**
     * Destroying the confidential file ends the rental and future collection
     * rights but leaves the audit event and the DESTROYED row (§9.7).
     */
    public CompletableFuture<Boolean> destroyBlackFile(SerializedItemService.PdcData fileData,
            UUID actor, String actorName) {
        if (!ITEM_TYPE_BLACK_FILE.equals(fileData.itemType())) {
            return CompletableFuture.completedFuture(false);
        }
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            UUID propertyId = null;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE black_property_files SET state = 'DESTROYED', "
                            + "destroyed_at = CURRENT_TIMESTAMP(3), version = version + 1 "
                            + "WHERE item_serial = ? AND state = 'ACTIVE'")) {
                statement.setString(1, fileData.serial().toString());
                if (statement.executeUpdate() != 1) {
                    return false;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT property_id FROM black_property_files WHERE item_serial = ?")) {
                statement.setString(1, fileData.serial().toString());
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        propertyId = UUID.fromString(rs.getString("property_id"));
                    }
                }
            }
            itemRepository.transition(connection, fileData.serial(), ItemStatus.ISSUED, ItemStatus.VOID);
            if (propertyId != null) {
                endOwnership(connection, propertyId, "TENANT");
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE properties SET state = 'DIRTY_AVAILABLE', version = version + 1 "
                                + "WHERE id = ? AND state = 'DIRTY_RENTED'")) {
                    statement.setString(1, propertyId.toString());
                    statement.executeUpdate();
                }
            }
            return true;
        }).thenApply(destroyed -> {
            if (destroyed) {
                auditService.log(actor, actorName, "BLACK_FILE_DESTROYED",
                        fileData.serial().toString(), Map.of());
            }
            return destroyed;
        });
    }

    // --- black safe (§9.7): director-only withdrawals ---

    public CompletableFuture<Long> blackSafeBalance() {
        return databaseManager.db().supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT dirty_cents FROM black_safes WHERE id = ?")) {
                statement.setString(1, BLACK_SAFE_ID);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /** Conditional decrement; the caller issues the physical dirty notes on success. */
    public CompletableFuture<Boolean> blackSafeWithdraw(long cents, UUID director, String directorName) {
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE black_safes SET dirty_cents = dirty_cents - ?, version = version + 1 "
                            + "WHERE id = ? AND dirty_cents >= ?")) {
                statement.setLong(1, cents);
                statement.setString(2, BLACK_SAFE_ID);
                statement.setLong(3, cents);
                return statement.executeUpdate() == 1;
            }
        }).thenApply(withdrawn -> {
            if (withdrawn) {
                auditService.log(director, directorName, "BLACK_SAFE_WITHDRAW", BLACK_SAFE_ID,
                        Map.of("cents", String.valueOf(cents)));
            }
            return withdrawn;
        });
    }

    // --- power anomalies (§9.7, simplified per M3 report) ---

    public record PowerAlert(String propertyName, String district) {}

    /** Accumulates consumption for illegally rented properties; returns new alerts. */
    public CompletableFuture<List<PowerAlert>> tickPowerAnomalies() {
        return databaseManager.db().inTransaction(connection -> {
            List<PowerAlert> alerts = new ArrayList<>();
            List<Property> rented = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM properties WHERE state = 'DIRTY_RENTED'");
                    ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rented.add(map(rs));
                }
            }
            for (Property property : rented) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO power_anomalies (property_id, consumption) VALUES (?, ?) "
                                + "ON DUPLICATE KEY UPDATE consumption = consumption + VALUES(consumption), "
                                + "version = version + 1")) {
                    statement.setString(1, property.id().toString());
                    statement.setInt(2, config.powerIncrementPerCheck());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE power_anomalies SET state = 'ALERTED', alerted_at = CURRENT_TIMESTAMP(3), "
                                + "version = version + 1 WHERE property_id = ? AND state = 'ACCUMULATING' "
                                + "AND consumption >= ?")) {
                    statement.setString(1, property.id().toString());
                    statement.setInt(2, config.powerAlertThreshold());
                    if (statement.executeUpdate() == 1) {
                        alerts.add(new PowerAlert(property.name(), property.district()));
                    }
                }
            }
            return alerts;
        });
    }

    // --- internals ---

    private Optional<Property> findByName(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM properties WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private Optional<UUID> currentOwner(Connection connection, UUID propertyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid FROM property_ownership WHERE property_id = ? AND kind = 'OWNER' "
                        + "AND ended_at IS NULL ORDER BY id DESC LIMIT 1")) {
            statement.setString(1, propertyId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(UUID.fromString(rs.getString(1))) : Optional.empty();
            }
        }
    }

    private void insertOwnership(Connection connection, UUID propertyId, UUID player, String kind)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO property_ownership (property_id, player_uuid, kind) VALUES (?, ?, ?)")) {
            statement.setString(1, propertyId.toString());
            statement.setString(2, player.toString());
            statement.setString(3, kind);
            statement.executeUpdate();
        }
    }

    private void endOwnership(Connection connection, UUID propertyId, String kind) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE property_ownership SET ended_at = CURRENT_TIMESTAMP(3) "
                        + "WHERE property_id = ? AND kind = ? AND ended_at IS NULL")) {
            statement.setString(1, propertyId.toString());
            statement.setString(2, kind);
            statement.executeUpdate();
        }
    }

    private SerializedItem insertKey(Connection connection, Property property, UUID owner)
            throws SQLException {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("property", property.id().toString());
        metadata.addProperty("lock_version", property.lockVersion());
        SerializedItem key = new SerializedItem(UUID.randomUUID(), ITEM_TYPE_KEY, owner, null,
                ItemStatus.ISSUED, owner, System.currentTimeMillis(), gson.toJson(metadata));
        itemRepository.insert(connection, key);
        return key;
    }

    private Property map(ResultSet rs) throws SQLException {
        return new Property(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getString("type"),
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getString("region_id"),
                rs.getLong("price"),
                rs.getBoolean("dirty"),
                rs.getString("state"),
                rs.getInt("lock_version"),
                rs.getInt("version"));
    }

    /** Queues a key/file item for redelivery when the recipient is offline. */
    public CompletableFuture<Void> queueItemDelivery(SerializedItem item, String reason) {
        return pendingDeliveryService.insertStandalone(item.owner(), item.itemType(),
                item.denomination(), 1, reason, null);
    }
}
