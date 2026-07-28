package com.afterlife.rp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.config.DatabaseSettings;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.module.banking.BankingConfig;
import com.afterlife.rp.module.banking.BankingService;
import com.afterlife.rp.shared.economy.Account;
import com.afterlife.rp.shared.economy.AccountRepository;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.LedgerRepository;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.economy.PendingDeliveryService;
import com.afterlife.rp.shared.economy.ReconciliationService;
import com.afterlife.rp.shared.items.ItemTypes;
import com.afterlife.rp.shared.items.SerializedItemRepository;
import com.afterlife.rp.shared.items.SerializedItemService;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Milestone 2 exit-gate tests (§15): concurrency cannot overspend, idempotency
 * replays are rejected, instruments redeem once, frozen accounts refuse debits,
 * reconciliation detects tampering, pending deliveries are claimed once.
 */
@Tag("integration")
@Testcontainers
class EconomyIT {

    @Container
    private static final MariaDBContainer<?> MARIADB =
            new MariaDBContainer<>("mariadb:11.4").withDatabaseName("afterlife");

    private static DatabaseManager databaseManager;
    private static AccountService accountService;
    private static LedgerService ledgerService;
    private static BankingService bankingService;
    private static PendingDeliveryService pendingDeliveryService;
    private static ReconciliationService reconciliationService;

    @BeforeAll
    static void start() {
        databaseManager = new DatabaseManager(
                new DatabaseSettings(MARIADB.getHost(), MARIADB.getFirstMappedPort(),
                        MARIADB.getDatabaseName(), MARIADB.getUsername(), MARIADB.getPassword(),
                        8, 5000),
                EconomyIT.class.getClassLoader(), Runnable::run, Logger.getLogger("EconomyIT"));
        assertEquals(DatabaseManager.State.READY, databaseManager.start().join());

        AccountRepository accountRepository = new AccountRepository();
        LedgerRepository ledgerRepository = new LedgerRepository();
        AuditService auditService = new AuditService(databaseManager, Logger.getLogger("EconomyIT"));
        accountService = new AccountService(databaseManager, accountRepository, auditService,
                "05428", "11101");
        ledgerService = new LedgerService(databaseManager, accountRepository, ledgerRepository,
                accountService::onLedgerCommit);
        pendingDeliveryService = new PendingDeliveryService(databaseManager);
        reconciliationService = new ReconciliationService(databaseManager, auditService);
        SerializedItemRepository itemRepository = new SerializedItemRepository();
        // HMAC/ItemStack paths are not exercised here; a null signer would NPE, so
        // use a real one with a fixed key.
        SerializedItemService itemService = new SerializedItemService(databaseManager, itemRepository,
                new com.afterlife.rp.shared.items.HmacSigner(
                        "integration-test-key-0123456789ab".getBytes()));
        BankingConfig config = new BankingConfig(true,
                List.of(50000L, 20000L, 10000L, 5000L, 2000L, 1000L, 500L),
                true, List.of("ATM"), 4.0, List.of(5000L), "05428", "11101", 7, 5);
        bankingService = new BankingService(databaseManager, accountService, ledgerService,
                accountRepository, ledgerRepository, itemRepository, itemService,
                pendingDeliveryService, auditService, config);

        accountService.loadSystemAccounts().join();
    }

    @AfterAll
    static void stop() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    private Account fundedAccount(long cents) {
        Account account = accountService.getOrCreatePersonal(UUID.randomUUID()).join();
        if (cents > 0) {
            UUID government = accountService.system(AccountService.SYSTEM_GOVERNMENT).id();
            LedgerService.Result result = ledgerService.execute("fund-" + UUID.randomUUID(),
                    "TEST_FUND", null, null,
                    List.of(new LedgerService.Line(government, -cents),
                            new LedgerService.Line(account.id(), cents)),
                    false).join();
            assertEquals(LedgerService.Status.COMPLETED, result.status());
        }
        return account;
    }

    @Test
    void concurrentTransfersCannotOverspend() {
        Account source = fundedAccount(10_000);
        Account target = fundedAccount(0);
        List<CompletableFuture<LedgerService.Result>> attempts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            attempts.add(ledgerService.execute("overspend-" + UUID.randomUUID(),
                    "TEST_TRANSFER", null, null,
                    List.of(new LedgerService.Line(source.id(), -10_000),
                            new LedgerService.Line(target.id(), 10_000)),
                    false));
        }
        long completed = attempts.stream()
                .map(CompletableFuture::join)
                .filter(result -> result.status() == LedgerService.Status.COMPLETED)
                .count();
        assertEquals(1, completed, "only one transfer may spend the balance");
    }

    @Test
    void sameIdempotencyKeyCreatesOneTransaction() {
        Account source = fundedAccount(5_000);
        Account target = fundedAccount(0);
        String key = "idem-" + UUID.randomUUID();
        List<LedgerService.Line> lines = List.of(
                new LedgerService.Line(source.id(), -1_000),
                new LedgerService.Line(target.id(), 1_000));
        assertEquals(LedgerService.Status.COMPLETED,
                ledgerService.execute(key, "TEST_TRANSFER", null, null, lines, false).join().status());
        assertEquals(LedgerService.Status.DUPLICATE,
                ledgerService.execute(key, "TEST_TRANSFER", null, null, lines, false).join().status());

        long rows = databaseManager.db().supply(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?")) {
                statement.setString(1, key);
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }).join();
        assertEquals(1, rows);
    }

    @Test
    void frozenAccountRefusesDebitsButAcceptsCredits() {
        Account frozen = fundedAccount(2_000);
        Account other = fundedAccount(2_000);
        assertTrue(accountService.setFrozen(frozen.id(), true, "indagine", null, "Test").join());

        assertEquals(LedgerService.Status.ACCOUNT_FROZEN, ledgerService.execute(
                "frz-" + UUID.randomUUID(), "TEST_TRANSFER", null, null,
                List.of(new LedgerService.Line(frozen.id(), -500),
                        new LedgerService.Line(other.id(), 500)),
                false).join().status());

        assertEquals(LedgerService.Status.COMPLETED, ledgerService.execute(
                "frz-" + UUID.randomUUID(), "TEST_TRANSFER", null, null,
                List.of(new LedgerService.Line(other.id(), -500),
                        new LedgerService.Line(frozen.id(), 500)),
                false).join().status());

        // Director seizure with explicit override still works (§9.1).
        assertEquals(LedgerService.Status.COMPLETED, ledgerService.execute(
                "frz-" + UUID.randomUUID(), "SEIZURE", null, null,
                List.of(new LedgerService.Line(frozen.id(), -1_000),
                        new LedgerService.Line(
                                accountService.system(AccountService.SYSTEM_SEIZURE).id(), 1_000)),
                true).join().status());
    }

    @Test
    void withdrawnNoteDepositsExactlyOnceEvenConcurrently() {
        Account account = fundedAccount(50_000);
        var withdraw = bankingService.withdraw(account.ownerRef(), account.id(), 5_000,
                "wd-" + UUID.randomUUID()).join();
        assertEquals(LedgerService.Status.COMPLETED, withdraw.status());
        assertFalse(withdraw.notes().isEmpty());

        var note = withdraw.notes().getFirst();
        var pdc = new SerializedItemService.PdcData(
                note.serial(), ItemTypes.BANKNOTE, note.denomination(), note.issuedAtEpochMs());

        List<CompletableFuture<BankingService.DepositResult>> deposits = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            deposits.add(bankingService.depositNotes(account.ownerRef(), account.id(),
                    List.of(pdc), "dp-" + UUID.randomUUID()));
        }
        long credited = deposits.stream()
                .map(CompletableFuture::join)
                .filter(result -> result.status() == LedgerService.Status.COMPLETED)
                .count();
        assertEquals(1, credited, "a copied/replayed note must redeem exactly once");
    }

    @Test
    void checkRedeemsOnceAndOnlyForPayee() {
        Account issuer = fundedAccount(20_000);
        Account payee = fundedAccount(0);
        UUID payeeUuid = payee.ownerRef();
        var check = bankingService.issueCheck(issuer.ownerRef(), issuer.id(), payeeUuid,
                10_000, "chk-" + UUID.randomUUID()).join();
        var pdc = new SerializedItemService.PdcData(
                check.serial(), ItemTypes.CHECK, check.denomination(), check.issuedAtEpochMs());

        assertEquals(BankingService.CheckRedeemStatus.NOT_PAYEE,
                bankingService.redeemCheck(UUID.randomUUID(), issuer.id(), pdc,
                        "chr-" + UUID.randomUUID()).join());

        List<CompletableFuture<BankingService.CheckRedeemStatus>> attempts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            attempts.add(bankingService.redeemCheck(payeeUuid, payee.id(), pdc,
                    "chr-" + UUID.randomUUID()));
        }
        long completed = attempts.stream()
                .map(CompletableFuture::join)
                .filter(status -> status == BankingService.CheckRedeemStatus.COMPLETED)
                .count();
        assertEquals(1, completed, "a check must redeem exactly once");
    }

    @Test
    void pendingDeliveryIsClaimedByExactlyOneWorker() {
        UUID player = UUID.randomUUID();
        pendingDeliveryService.insertStandalone(player, ItemTypes.BANKNOTE, 5_000L, 1,
                "TEST", null).join();
        var pending = pendingDeliveryService.pendingFor(player).join().getFirst();

        List<CompletableFuture<Boolean>> claims = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            claims.add(pendingDeliveryService.markDelivered(pending.id(), pending.version()));
        }
        long winners = claims.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count();
        assertEquals(1, winners, "restart recovery must deliver exactly once");
    }

    @Test
    void reconciliationDetectsTamperedBalances() {
        var clean = reconciliationService.run("test-clean").join();
        assertTrue(clean.clean(), "ledger must reconcile cleanly: " + clean.defects());

        Account victim = fundedAccount(1_000);
        databaseManager.db().supply(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE accounts SET balance = balance + 777 WHERE id = '"
                        + victim.id() + "'");
            }
            return null;
        }).join();

        var tampered = reconciliationService.run("test-tampered").join();
        assertFalse(tampered.clean(), "tampered balance must be detected");

        // Restore so later tests (and the clean check) stay meaningful.
        databaseManager.db().supply(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE accounts SET balance = balance - 777 WHERE id = '"
                        + victim.id() + "'");
            }
            return null;
        }).join();
    }
}
