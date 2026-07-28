package com.afterlife.rp.module.banking;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.economy.Account;
import com.afterlife.rp.shared.economy.AccountRepository;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.DenominationBreakdown;
import com.afterlife.rp.shared.economy.LedgerRepository;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.economy.PendingDeliveryService;
import com.afterlife.rp.shared.items.ItemStatus;
import com.afterlife.rp.shared.items.ItemTypes;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemRepository;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Banking use cases (§9.1). Every method is async; composed flows join item
 * status transitions and ledger entries in a single SQL transaction so a
 * restart can never split them (rule 13).
 */
public final class BankingService {

    public record DepositResult(LedgerService.Status status, long totalCents, List<UUID> redeemedSerials) {}

    public record WithdrawResult(LedgerService.Status status, UUID transactionId, List<SerializedItem> notes) {}

    public record CheckData(UUID payee, long expiresAt) {}

    private final DatabaseManager databaseManager;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final SerializedItemRepository itemRepository;
    private final SerializedItemService itemService;
    private final PendingDeliveryService pendingDeliveryService;
    private final AuditService auditService;
    private final BankingConfig config;
    private final Gson gson = new Gson();

    public BankingService(
            DatabaseManager databaseManager,
            AccountService accountService,
            LedgerService ledgerService,
            AccountRepository accountRepository,
            LedgerRepository ledgerRepository,
            SerializedItemRepository itemRepository,
            SerializedItemService itemService,
            PendingDeliveryService pendingDeliveryService,
            AuditService auditService,
            BankingConfig config) {
        this.databaseManager = databaseManager;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.itemRepository = itemRepository;
        this.itemService = itemService;
        this.pendingDeliveryService = pendingDeliveryService;
        this.auditService = auditService;
        this.config = config;
    }

    public BankingConfig config() {
        return config;
    }

    /**
     * Redeems verified banknotes and credits the account atomically. Notes whose
     * DB record does not match (copied serial already redeemed, tampered
     * denomination) are skipped; the ledger credits only what was redeemed.
     */
    public CompletableFuture<DepositResult> depositNotes(
            UUID playerUuid, UUID accountId, List<SerializedItemService.PdcData> notes, String idempotencyKey) {
        Account cashIssuance = accountService.system(AccountService.SYSTEM_CASH_ISSUANCE);
        return databaseManager.db().<DepositResult>inTransaction(connection -> {
            long total = 0;
            List<UUID> redeemed = new ArrayList<>();
            for (SerializedItemService.PdcData note : notes) {
                if (!ItemTypes.BANKNOTE.equals(note.itemType())) {
                    continue;
                }
                var record = itemRepository.find(connection, note.serial());
                if (record.isEmpty()
                        || record.get().status() != ItemStatus.ISSUED
                        || record.get().denomination() == null
                        || record.get().denomination() != note.denomination()
                        || !ItemTypes.BANKNOTE.equals(record.get().itemType())) {
                    continue;
                }
                if (itemRepository.transition(connection, note.serial(),
                        ItemStatus.ISSUED, ItemStatus.REDEEMED)) {
                    total += note.denomination();
                    redeemed.add(note.serial());
                }
            }
            if (redeemed.isEmpty()) {
                return new DepositResult(LedgerService.Status.INVALID, 0, List.of());
            }
            LedgerService.Result result = ledgerService.apply(connection, idempotencyKey,
                    "ATM_DEPOSIT", playerUuid, redeemed.size() + " banconote",
                    List.of(new LedgerService.Line(cashIssuance.id(), -total),
                            new LedgerService.Line(accountId, total)),
                    false);
            return new DepositResult(result.status(), total, redeemed);
        }).handle((result, e) -> {
            if (e != null) {
                return new DepositResult(LedgerService.failureFrom(e).status(), 0, List.of());
            }
            if (result.status() == LedgerService.Status.COMPLETED) {
                refreshBalances(accountId, cashIssuance.id());
            }
            return result;
        });
    }

    /** Debits the account and creates the note records in one transaction. */
    public CompletableFuture<WithdrawResult> withdraw(
            UUID playerUuid, UUID accountId, long amountCents, String idempotencyKey) {
        Map<Long, Integer> breakdown =
                DenominationBreakdown.breakdown(amountCents, config.denominationsCentsDesc());
        if (breakdown == null) {
            return CompletableFuture.completedFuture(
                    new WithdrawResult(LedgerService.Status.INVALID, null, List.of()));
        }
        Account cashIssuance = accountService.system(AccountService.SYSTEM_CASH_ISSUANCE);
        return databaseManager.db().<WithdrawResult>inTransaction(connection -> {
            LedgerService.Result result = ledgerService.apply(connection, idempotencyKey,
                    "ATM_WITHDRAW", playerUuid, null,
                    List.of(new LedgerService.Line(accountId, -amountCents),
                            new LedgerService.Line(cashIssuance.id(), amountCents)),
                    false);
            List<SerializedItem> created = new ArrayList<>();
            for (Map.Entry<Long, Integer> entry : breakdown.entrySet()) {
                for (int i = 0; i < entry.getValue(); i++) {
                    SerializedItem note = new SerializedItem(UUID.randomUUID(), ItemTypes.BANKNOTE,
                            playerUuid, entry.getKey(), ItemStatus.ISSUED, playerUuid,
                            System.currentTimeMillis(), null);
                    itemRepository.insert(connection, note);
                    created.add(note);
                }
            }
            return new WithdrawResult(result.status(), result.transactionId(), created);
        }).handle((result, e) -> {
            if (e != null) {
                return new WithdrawResult(LedgerService.failureFrom(e).status(), null, List.of());
            }
            if (result.status() == LedgerService.Status.COMPLETED) {
                refreshBalances(accountId, cashIssuance.id());
            }
            return result;
        });
    }

    /** Voids an undeliverable note and records a durable redelivery (§7.4 step 8). */
    public CompletableFuture<Void> voidAndQueueRedelivery(SerializedItem note, UUID transactionId) {
        return databaseManager.db().<Void>inTransaction(connection -> {
            if (itemRepository.transition(connection, note.serial(), ItemStatus.ISSUED, ItemStatus.VOID)) {
                pendingDeliveryService.insert(connection, note.owner(), note.itemType(),
                        note.denomination(), 1, "WITHDRAW_OVERFLOW", transactionId);
            }
            return null;
        });
    }

    public CompletableFuture<LedgerService.Result> transferByIban(
            UUID playerUuid, UUID sourceAccountId, String targetIban, long amountCents,
            String idempotencyKey) {
        return databaseManager.db().<LedgerService.Result>inTransaction(connection -> {
            var target = accountRepository.findByIban(connection, targetIban);
            if (target.isEmpty()) {
                throw new LedgerService.LedgerAbort(LedgerService.Status.ACCOUNT_NOT_FOUND);
            }
            if (target.get().id().equals(sourceAccountId)) {
                throw new LedgerService.LedgerAbort(LedgerService.Status.INVALID);
            }
            return ledgerService.apply(connection, idempotencyKey, "TRANSFER", playerUuid,
                    "verso " + targetIban,
                    List.of(new LedgerService.Line(sourceAccountId, -amountCents),
                            new LedgerService.Line(target.get().id(), amountCents)),
                    false);
        }).handle((result, e) -> e != null ? LedgerService.failureFrom(e) : ledgerService.publish(result));
    }

    public CompletableFuture<List<LedgerRepository.StatementEntry>> statement(UUID accountId) {
        return databaseManager.db().supply(connection ->
                ledgerRepository.lastEntries(connection, accountId, config.statementEntries()));
    }

    /** Banker issues a card bound to the target's account (§9.1 employee). */
    public CompletableFuture<SerializedItem> issueCard(UUID target, UUID accountId, UUID banker,
            String bankerName) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("account", accountId.toString());
        SerializedItem card = new SerializedItem(UUID.randomUUID(), ItemTypes.CREDIT_CARD, target,
                null, ItemStatus.ISSUED, banker, System.currentTimeMillis(), gson.toJson(metadata));
        return databaseManager.db().<SerializedItem>inTransaction(connection -> {
            itemRepository.insert(connection, card);
            return card;
        }).thenApply(saved -> {
            auditService.log(banker, bankerName, "CARD_ISSUE", target.toString(),
                    Map.of("serial", saved.serial().toString()));
            return saved;
        });
    }

    /** Revokes every active card of the target; returns how many were voided. */
    public CompletableFuture<Integer> revokeCards(UUID target, UUID banker, String bankerName) {
        return databaseManager.db().<Integer>inTransaction(connection -> {
            int voided = 0;
            for (SerializedItem card : itemRepository.findByOwnerAndType(
                    connection, target, ItemTypes.CREDIT_CARD, ItemStatus.ISSUED)) {
                if (itemRepository.transition(connection, card.serial(),
                        ItemStatus.ISSUED, ItemStatus.VOID)) {
                    voided++;
                }
            }
            return voided;
        }).thenApply(voided -> {
            if (voided > 0) {
                auditService.log(banker, bankerName, "CARD_REVOKE", target.toString(),
                        Map.of("count", String.valueOf(voided)));
            }
            return voided;
        });
    }

    /** True when the held card belongs to the player, is active, and matches the account. */
    public CompletableFuture<Boolean> validateCard(
            SerializedItemService.PdcData cardData, UUID playerUuid, UUID accountId) {
        if (!ItemTypes.CREDIT_CARD.equals(cardData.itemType())) {
            return CompletableFuture.completedFuture(false);
        }
        return databaseManager.db().supply(connection -> itemRepository.find(connection, cardData.serial()))
                .thenApply(record -> record.isPresent()
                        && record.get().status() == ItemStatus.ISSUED
                        && playerUuid.equals(record.get().owner())
                        && accountId.toString().equals(readMetadata(record.get(), "account")));
    }

    /** Escrows funds and creates a payee-specific, expiring, single-use check (§7.1). */
    public CompletableFuture<SerializedItem> issueCheck(
            UUID issuer, UUID issuerAccountId, UUID payee, long amountCents, String idempotencyKey) {
        Account clearing = accountService.system(AccountService.SYSTEM_CHECK_CLEARING);
        long expiresAt = System.currentTimeMillis() + config.checkExpiryDays() * 86_400_000L;
        JsonObject metadata = new JsonObject();
        metadata.addProperty("payee", payee.toString());
        metadata.addProperty("expires_at", expiresAt);
        SerializedItem check = new SerializedItem(UUID.randomUUID(), ItemTypes.CHECK, payee,
                amountCents, ItemStatus.ISSUED, issuer, System.currentTimeMillis(), gson.toJson(metadata));
        return databaseManager.db().<SerializedItem>inTransaction(connection -> {
            ledgerService.apply(connection, idempotencyKey, "CHECK_ISSUE", issuer,
                    "assegno per " + payee,
                    List.of(new LedgerService.Line(issuerAccountId, -amountCents),
                            new LedgerService.Line(clearing.id(), amountCents)),
                    false);
            itemRepository.insert(connection, check);
            return check;
        }).thenApply(saved -> {
            refreshBalances(issuerAccountId, clearing.id());
            return saved;
        });
    }

    public enum CheckRedeemStatus { COMPLETED, NOT_PAYEE, EXPIRED, ALREADY_USED, UNKNOWN }

    /** Redeems a held check: single winner via the status transition. */
    public CompletableFuture<CheckRedeemStatus> redeemCheck(
            UUID playerUuid, UUID payeeAccountId, SerializedItemService.PdcData checkData,
            String idempotencyKey) {
        Account clearing = accountService.system(AccountService.SYSTEM_CHECK_CLEARING);
        return databaseManager.db().<CheckRedeemStatus>inTransaction(connection -> {
            var record = itemRepository.find(connection, checkData.serial());
            if (record.isEmpty() || !ItemTypes.CHECK.equals(record.get().itemType())
                    || record.get().denomination() == null) {
                return CheckRedeemStatus.UNKNOWN;
            }
            if (!playerUuid.toString().equals(readMetadata(record.get(), "payee"))) {
                return CheckRedeemStatus.NOT_PAYEE;
            }
            String expiresText = readMetadata(record.get(), "expires_at");
            if (expiresText != null && System.currentTimeMillis() > Long.parseLong(expiresText)) {
                return CheckRedeemStatus.EXPIRED;
            }
            if (!itemRepository.transition(connection, record.get().serial(),
                    ItemStatus.ISSUED, ItemStatus.REDEEMED)) {
                return CheckRedeemStatus.ALREADY_USED;
            }
            long amount = record.get().denomination();
            ledgerService.apply(connection, idempotencyKey, "CHECK_REDEEM", playerUuid, null,
                    List.of(new LedgerService.Line(clearing.id(), -amount),
                            new LedgerService.Line(payeeAccountId, amount)),
                    false);
            return CheckRedeemStatus.COMPLETED;
        }).handle((status, e) -> {
            if (e != null) {
                LedgerService.failureFrom(e);
                return CheckRedeemStatus.UNKNOWN;
            }
            if (status == CheckRedeemStatus.COMPLETED) {
                refreshBalances(payeeAccountId, clearing.id());
            }
            return status;
        });
    }

    /** Creates physical dirty-money notes (no ledger — dirty money is physical only). */
    public CompletableFuture<List<SerializedItem>> issueDirty(UUID owner, long amountCents, UUID actor) {
        Map<Long, Integer> breakdown =
                DenominationBreakdown.breakdown(amountCents, config.denominationsCentsDesc());
        if (breakdown == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return databaseManager.db().inTransaction(connection -> {
            List<SerializedItem> created = new ArrayList<>();
            for (Map.Entry<Long, Integer> entry : breakdown.entrySet()) {
                for (int i = 0; i < entry.getValue(); i++) {
                    SerializedItem note = new SerializedItem(UUID.randomUUID(), ItemTypes.DIRTY_MONEY,
                            owner, entry.getKey(), ItemStatus.ISSUED, actor,
                            System.currentTimeMillis(), null);
                    itemRepository.insert(connection, note);
                    created.add(note);
                }
            }
            return created;
        });
    }

    private String readMetadata(SerializedItem item, String key) {
        if (item.metadata() == null) {
            return null;
        }
        JsonObject json = gson.fromJson(item.metadata(), JsonObject.class);
        return json != null && json.has(key) ? json.get(key).getAsString() : null;
    }

    /** Composed flows bypass the ledger listener; re-read the touched balances. */
    private void refreshBalances(UUID... accountIds) {
        databaseManager.db().supply(connection -> {
            Map<UUID, Long> balances = new java.util.HashMap<>();
            for (UUID id : accountIds) {
                accountRepository.findById(connection, id)
                        .ifPresent(account -> balances.put(id, account.balance()));
            }
            return balances;
        }).thenAccept(accountService::onLedgerCommit);
    }
}
