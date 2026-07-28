package com.afterlife.rp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.config.DatabaseSettings;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.economy.Account;
import com.afterlife.rp.shared.economy.AccountRepository;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.EconomyReportService;
import com.afterlife.rp.shared.economy.LedgerRepository;
import com.afterlife.rp.shared.economy.LedgerService;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** M9 economy report: creation/destruction attributed correctly by reason. */
@Tag("integration")
@Testcontainers
class EconomyReportIT {

    @Container
    private static final MariaDBContainer<?> MARIADB =
            new MariaDBContainer<>("mariadb:11.4").withDatabaseName("afterlife");

    private static DatabaseManager databaseManager;
    private static AccountService accountService;
    private static LedgerService ledgerService;
    private static EconomyReportService reportService;

    @BeforeAll
    static void start() {
        databaseManager = new DatabaseManager(
                new DatabaseSettings(MARIADB.getHost(), MARIADB.getFirstMappedPort(),
                        MARIADB.getDatabaseName(), MARIADB.getUsername(), MARIADB.getPassword(),
                        8, 5000),
                EconomyReportIT.class.getClassLoader(), Runnable::run,
                Logger.getLogger("EconomyReportIT"));
        assertEquals(DatabaseManager.State.READY, databaseManager.start().join());
        AccountRepository accountRepository = new AccountRepository();
        AuditService auditService = new AuditService(databaseManager,
                Logger.getLogger("EconomyReportIT"));
        accountService = new AccountService(databaseManager, accountRepository, auditService,
                "05428", "11101");
        ledgerService = new LedgerService(databaseManager, accountRepository,
                new LedgerRepository(), accountService::onLedgerCommit);
        reportService = new EconomyReportService(databaseManager);
        accountService.loadSystemAccounts().join();
    }

    @AfterAll
    static void stop() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    @Test
    void reportAttributesCreationAndDestructionByReason() {
        Account player = accountService.getOrCreatePersonal(UUID.randomUUID()).join();
        UUID government = accountService.system(AccountService.SYSTEM_GOVERNMENT).id();
        UUID cashIssuance = accountService.system(AccountService.SYSTEM_CASH_ISSUANCE).id();

        // A wage credits the player from the government: +50 into the economy.
        ledgerService.execute("wage-" + UUID.randomUUID(), "TEST_WAGE", null, null,
                List.of(new LedgerService.Line(government, -5000),
                        new LedgerService.Line(player.id(), 5000)), false).join();
        // An ATM deposit moves cash-issuance -> player: net into the economy too.
        ledgerService.execute("dep-" + UUID.randomUUID(), "TEST_DEPOSIT", null, null,
                List.of(new LedgerService.Line(cashIssuance, -2000),
                        new LedgerService.Line(player.id(), 2000)), false).join();
        // A fee debits the player to the government: -1000, destruction from the
        // player economy's perspective.
        ledgerService.execute("fee-" + UUID.randomUUID(), "TEST_FEE", null, null,
                List.of(new LedgerService.Line(player.id(), -1000),
                        new LedgerService.Line(government, 1000)), false).join();

        var report = reportService.report(24).join();
        long wage = report.flows().stream().filter(f -> f.reason().equals("TEST_WAGE"))
                .mapToLong(EconomyReportService.ReasonFlow::netToPlayers).sum();
        long fee = report.flows().stream().filter(f -> f.reason().equals("TEST_FEE"))
                .mapToLong(EconomyReportService.ReasonFlow::netToPlayers).sum();
        assertEquals(5000, wage, "wage adds to the player economy");
        assertEquals(-1000, fee, "fee removes from the player economy");
        assertTrue(report.totalCreated() >= 7000);
        assertEquals(-1000, report.totalDestroyed());
    }
}
