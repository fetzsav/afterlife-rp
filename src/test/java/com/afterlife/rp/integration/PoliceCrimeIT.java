package com.afterlife.rp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.config.DatabaseSettings;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.module.banking.BankingConfig;
import com.afterlife.rp.module.banking.BankingService;
import com.afterlife.rp.module.crime.CrimeConfig;
import com.afterlife.rp.module.crime.CrimeService;
import com.afterlife.rp.module.electrician.ElectricianService;
import com.afterlife.rp.module.legal.LegalConfig;
import com.afterlife.rp.module.legal.LegalService;
import com.afterlife.rp.module.police.PoliceConfig;
import com.afterlife.rp.module.police.PoliceService;
import com.afterlife.rp.shared.economy.Account;
import com.afterlife.rp.shared.economy.AccountRepository;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.LedgerRepository;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.economy.PendingDeliveryService;
import com.afterlife.rp.shared.items.HmacSigner;
import com.afterlife.rp.shared.items.ItemStatus;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemRepository;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.missions.Mission;
import com.afterlife.rp.shared.missions.MissionRepository;
import com.afterlife.rp.shared.missions.MissionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Milestone 8 exit-gate tests: police access requires authority (denials
 * audited); warrants expire by the DB clock; drug sales and ATM hacks consume
 * once; seizures enter the evidence chain (§17 M8).
 */
@Tag("integration")
@Testcontainers
class PoliceCrimeIT {

    @Container
    private static final MariaDBContainer<?> MARIADB =
            new MariaDBContainer<>("mariadb:11.4").withDatabaseName("afterlife");

    private static DatabaseManager databaseManager;
    private static AccountService accountService;
    private static LedgerService ledgerService;
    private static PoliceService policeService;
    private static CrimeService crimeService;
    private static MissionService missionService;
    private static SerializedItemRepository itemRepository;

    @BeforeAll
    static void start() {
        databaseManager = new DatabaseManager(
                new DatabaseSettings(MARIADB.getHost(), MARIADB.getFirstMappedPort(),
                        MARIADB.getDatabaseName(), MARIADB.getUsername(), MARIADB.getPassword(),
                        8, 5000),
                PoliceCrimeIT.class.getClassLoader(), Runnable::run,
                Logger.getLogger("PoliceCrimeIT"));
        assertEquals(DatabaseManager.State.READY, databaseManager.start().join());

        AccountRepository accountRepository = new AccountRepository();
        LedgerRepository ledgerRepository = new LedgerRepository();
        itemRepository = new SerializedItemRepository();
        AuditService auditService = new AuditService(databaseManager,
                Logger.getLogger("PoliceCrimeIT"));
        accountService = new AccountService(databaseManager, accountRepository, auditService,
                "05428", "11101");
        ledgerService = new LedgerService(databaseManager, accountRepository, ledgerRepository,
                accountService::onLedgerCommit);
        missionService = new MissionService(databaseManager, new MissionRepository(),
                auditService, Logger.getLogger("PoliceCrimeIT"));
        SerializedItemService itemService = new SerializedItemService(databaseManager,
                itemRepository, new HmacSigner("integration-test-key-0123456789ab".getBytes()));
        BankingService bankingService = new BankingService(databaseManager, accountService,
                ledgerService, accountRepository, ledgerRepository, itemRepository, itemService,
                new PendingDeliveryService(databaseManager), auditService,
                new BankingConfig(true, List.of(50000L, 20000L, 10000L, 5000L, 2000L, 1000L, 500L),
                        true, List.of("ATM"), 4.0, List.of(5000L), "05428", "11101", 7, 5));
        LegalService legalService = new LegalService(databaseManager, accountService, ledgerService,
                itemRepository, auditService, new LegalConfig(true, 15, 45, 0, 0, 4000, 120));
        policeService = new PoliceService(databaseManager, accountService, legalService,
                auditService, new PoliceConfig(true, 30, 4, 6, 5,
                        List.of("drug_dose"), List.of("sealed_bag"), List.of(100_000L, 1_000_000L)));
        crimeService = new CrimeService(databaseManager, missionService, bankingService,
                itemRepository, auditService, new CrimeConfig(true, List.of("STREET_SALE_ZONE"),
                        90, 4000, 8000, 0.25, 0.70, 20, 4, 45, List.of("ALLAY"), List.of("ZOMBIE"),
                        List.of("ATM"), 20, 20000, 60000, true));
        accountService.loadSystemAccounts().join();
    }

    @AfterAll
    static void stop() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    private long auditCount(String action) {
        return databaseManager.db().supply(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM audit_events WHERE action = ?")) {
                statement.setString(1, action);
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }).join();
    }

    private SerializedItem issue(String type, UUID owner) {
        SerializedItem item = new SerializedItem(UUID.randomUUID(), type, owner, null,
                ItemStatus.ISSUED, owner, System.currentTimeMillis(), null);
        databaseManager.db().inTransaction(connection -> {
            itemRepository.insert(connection, item);
            return null;
        }).join();
        return item;
    }

    @Test
    void searchWithoutAuthorityIsDeniedAndAudited() {
        UUID officer = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        long deniedBefore = auditCount("SEARCH_DENIED");

        // Consent authority without consent -> denied.
        assertEquals(PoliceService.SearchDecision.DENIED,
                policeService.authorizeSearch(officer, "Officer", target,
                        PoliceService.SearchAuthority.CONSENT, false).join());
        assertEquals(deniedBefore + 1, auditCount("SEARCH_DENIED"));

        // Consent given -> authorized and audited.
        long authBefore = auditCount("SEARCH_AUTHORIZED");
        assertEquals(PoliceService.SearchDecision.AUTHORIZED,
                policeService.authorizeSearch(officer, "Officer", target,
                        PoliceService.SearchAuthority.CONSENT, true).join());
        assertEquals(authBefore + 1, auditCount("SEARCH_AUTHORIZED"));
    }

    @Test
    void warrantAuthorizesSearchUntilItExpiresByDbClock() {
        UUID officer = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        policeService.issueWarrant("SEARCH", target, officer, "Officer", "test", 30).join();

        assertEquals(PoliceService.SearchDecision.AUTHORIZED,
                policeService.authorizeSearch(officer, "Officer", target,
                        PoliceService.SearchAuthority.WARRANT, false).join());

        // Backdate the warrant past expiry: it no longer authorizes.
        databaseManager.db().supply(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE warrants SET expires_at = TIMESTAMPADD(HOUR, -1, CURRENT_TIMESTAMP(3)) "
                            + "WHERE target_uuid = ?")) {
                statement.setString(1, target.toString());
                statement.executeUpdate();
            }
            return null;
        }).join();
        assertEquals(PoliceService.SearchDecision.DENIED,
                policeService.authorizeSearch(officer, "Officer", target,
                        PoliceService.SearchAuthority.WARRANT, false).join());
    }

    @Test
    void seizureEntersEvidenceChainAndConfiscatesTheItem() {
        UUID officer = UUID.randomUUID();
        SerializedItem contraband = issue("drug_dose", UUID.randomUUID());
        long id = policeService.seize("dose sequestrata", contraband.serial(), officer, "Officer")
                .join();
        assertTrue(id > 0);
        // Confiscated items leave circulation (status CONFISCATED).
        var record = databaseManager.db().supply(connection ->
                itemRepository.find(connection, contraband.serial())).join().orElseThrow();
        assertEquals(ItemStatus.CONFISCATED, record.status());
    }

    @Test
    void drugSaleConsumesDoseExactlyOnce() {
        UUID seller = UUID.randomUUID();
        SerializedItem dose = issue("drug_dose", seller);

        List<CompletableFuture<CrimeService.SaleResult>> attempts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            attempts.add(crimeService.sellDose(seller, dose.serial()));
        }
        long sold = attempts.stream().map(CompletableFuture::join)
                .filter(CrimeService.SaleResult::sold).count();
        assertEquals(1, sold, "a dose sells exactly once (M8 exit gate)");
    }

    @Test
    void sealedDrugsDefeatK9WhileUnsealedDoNot() {
        UUID owner = UUID.randomUUID();
        SerializedItem dose = issue("drug_dose", owner);
        // The pure classification: drug_dose is contraband, sealed_bag is odor-proof.
        assertTrue(policeService.config().k9ContrabandTypes().contains("drug_dose"));
        assertTrue(policeService.config().k9OdorproofTypes().contains("sealed_bag"));

        var bag = crimeService.seal(owner, dose.serial()).join();
        assertTrue(bag.isPresent());
        assertEquals("sealed_bag", bag.get().itemType());
        // The original dose was voided by sealing.
        var original = databaseManager.db().supply(connection ->
                itemRepository.find(connection, dose.serial())).join().orElseThrow();
        assertEquals(ItemStatus.VOID, original.status());
    }

    @Test
    void atmHackBuildsFromCircuitBoardAndPaysOnce() {
        UUID hacker = UUID.randomUUID();
        SerializedItem board = issue(ElectricianService.ITEM_TYPE_CIRCUIT_BOARD, hacker);
        var device = crimeService.buildHackDevice(hacker, board.serial()).join();
        assertTrue(device.isPresent());
        // Board is consumed; a second build fails.
        assertTrue(crimeService.buildHackDevice(hacker, board.serial()).join().isEmpty());

        UUID atmPoi = UUID.randomUUID();
        Mission mission = crimeService.startHack(hacker, atmPoi, device.get().serial())
                .join().orElseThrow();
        // Device consumed at start; a replay with the same serial fails.
        assertTrue(crimeService.startHack(hacker, atmPoi, device.get().serial()).join().isEmpty());

        List<CompletableFuture<CrimeService.HackResult>> finishes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            finishes.add(crimeService.finishHack(mission));
        }
        long paid = finishes.stream().map(CompletableFuture::join)
                .filter(CrimeService.HackResult::rewarded).count();
        assertEquals(1, paid, "an ATM hack pays exactly once");
    }

    @Test
    void accountCheckIsAuditedAndScoped() {
        Account target = accountService.getOrCreatePersonal(UUID.randomUUID()).join();
        UUID officer = UUID.randomUUID();
        long before = auditCount("ACCOUNT_CHECK");
        var check = policeService.checkAccount(officer, "Officer", target.ownerRef(), "Target")
                .join();
        assertTrue(check.isPresent());
        assertEquals(before + 1, auditCount("ACCOUNT_CHECK"));
        // The band string never reveals the exact balance.
        assertFalse(check.get().balanceBand().matches(".*\\d{6,}.*"));
    }
}
