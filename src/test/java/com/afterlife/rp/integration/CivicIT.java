package com.afterlife.rp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.config.DatabaseSettings;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.module.banking.BankingConfig;
import com.afterlife.rp.module.banking.BankingService;
import com.afterlife.rp.module.legal.LegalConfig;
import com.afterlife.rp.module.legal.LegalService;
import com.afterlife.rp.module.realestate.RealEstateConfig;
import com.afterlife.rp.module.realestate.RealEstateService;
import com.afterlife.rp.shared.economy.Account;
import com.afterlife.rp.shared.economy.AccountRepository;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.LedgerRepository;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.economy.PendingDeliveryService;
import com.afterlife.rp.shared.items.HmacSigner;
import com.afterlife.rp.shared.items.ItemTypes;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemRepository;
import com.afterlife.rp.shared.items.SerializedItemService;
import java.util.ArrayList;
import java.util.List;
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
 * Milestone 3 exit-gate tests: ownership and lock changes are atomic, destroyed
 * evidence/files stay in audit history, unauthorized access fails and is
 * audited (§17 M3).
 */
@Tag("integration")
@Testcontainers
class CivicIT {

    @Container
    private static final MariaDBContainer<?> MARIADB =
            new MariaDBContainer<>("mariadb:11.4").withDatabaseName("afterlife");

    private static DatabaseManager databaseManager;
    private static AccountService accountService;
    private static LedgerService ledgerService;
    private static LegalService legalService;
    private static RealEstateService realEstateService;
    private static BankingService bankingService;

    @BeforeAll
    static void start() {
        databaseManager = new DatabaseManager(
                new DatabaseSettings(MARIADB.getHost(), MARIADB.getFirstMappedPort(),
                        MARIADB.getDatabaseName(), MARIADB.getUsername(), MARIADB.getPassword(),
                        8, 5000),
                CivicIT.class.getClassLoader(), Runnable::run, Logger.getLogger("CivicIT"));
        assertEquals(DatabaseManager.State.READY, databaseManager.start().join());

        AccountRepository accountRepository = new AccountRepository();
        LedgerRepository ledgerRepository = new LedgerRepository();
        SerializedItemRepository itemRepository = new SerializedItemRepository();
        AuditService auditService = new AuditService(databaseManager, Logger.getLogger("CivicIT"));
        accountService = new AccountService(databaseManager, accountRepository, auditService,
                "05428", "11101");
        ledgerService = new LedgerService(databaseManager, accountRepository, ledgerRepository,
                accountService::onLedgerCommit);
        PendingDeliveryService pendingDeliveryService = new PendingDeliveryService(databaseManager);
        SerializedItemService itemService = new SerializedItemService(databaseManager, itemRepository,
                new HmacSigner("integration-test-key-0123456789ab".getBytes()));

        legalService = new LegalService(databaseManager, accountService, ledgerService,
                itemRepository, auditService,
                new LegalConfig(true, 15, 45, 0, 10_000, 4000, 120));
        realEstateService = new RealEstateService(databaseManager, accountService, ledgerService,
                itemRepository, pendingDeliveryService, auditService,
                new RealEstateConfig(true, AccountService.SYSTEM_GOVERNMENT, 60, 10, 40));
        bankingService = new BankingService(databaseManager, accountService, ledgerService,
                accountRepository, ledgerRepository, itemRepository, itemService,
                pendingDeliveryService, auditService,
                new BankingConfig(true, List.of(50000L, 20000L, 10000L, 5000L, 2000L, 1000L, 500L),
                        true, List.of("ATM"), 4.0, List.of(5000L), "05428", "11101", 7, 5));

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
            assertEquals(LedgerService.Status.COMPLETED, ledgerService.execute(
                    "fund-" + UUID.randomUUID(), "TEST_FUND", null, null,
                    List.of(new LedgerService.Line(government, -cents),
                            new LedgerService.Line(account.id(), cents)),
                    false).join().status());
        }
        return account;
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

    /** Audit writes are async fire-and-forget; wait briefly for them to land. */
    private void awaitAuditCount(String action, long atLeast) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            if (auditCount(action) >= atLeast) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(auditCount(action) >= atLeast, "audit row for " + action + " must appear");
    }

    @Test
    void concurrentSaleProducesExactlyOneOwner() {
        realEstateService.createProperty("casa_race", "HOUSE", "world", 0, 64, 0, null,
                50_000, false, null, "Test").join();
        Account buyerA = fundedAccount(100_000);
        Account buyerB = fundedAccount(100_000);

        var saleA = realEstateService.sell("casa_race", buyerA.ownerRef(), buyerA.id(),
                UUID.randomUUID(), "AgentA");
        var saleB = realEstateService.sell("casa_race", buyerB.ownerRef(), buyerB.id(),
                UUID.randomUUID(), "AgentB");
        long completed = List.of(saleA.join(), saleB.join()).stream()
                .filter(outcome -> outcome.result() == RealEstateService.SaleResult.COMPLETED)
                .count();
        assertEquals(1, completed, "a property can only be sold once (M3 exit gate)");

        long owners = databaseManager.db().supply(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM property_ownership o JOIN properties p "
                            + "ON p.id = o.property_id WHERE p.name = 'casa_race' "
                            + "AND o.kind = 'OWNER' AND o.ended_at IS NULL")) {
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }).join();
        assertEquals(1, owners);
    }

    @Test
    void lockChangeRevokesEarlierKeys() {
        realEstateService.createProperty("casa_serratura", "HOUSE", "world", 0, 64, 0, null,
                10_000, false, null, "Test").join();
        Account buyer = fundedAccount(20_000);
        var sale = realEstateService.sell("casa_serratura", buyer.ownerRef(), buyer.id(),
                UUID.randomUUID(), "Agent").join();
        assertEquals(RealEstateService.SaleResult.COMPLETED, sale.result());

        SerializedItem oldKey = sale.key();
        var oldKeyData = new SerializedItemService.PdcData(oldKey.serial(),
                RealEstateService.ITEM_TYPE_KEY, 0, oldKey.issuedAtEpochMs());
        assertTrue(realEstateService.keyValid(oldKeyData).join(), "fresh key must open");

        var change = realEstateService.changeLock("casa_serratura", "eviction test",
                UUID.randomUUID(), "Agent").join();
        assertTrue(change.changed());
        assertFalse(realEstateService.keyValid(oldKeyData).join(),
                "old key must be revoked by the lock bump (M3 exit gate)");
        var newKeyData = new SerializedItemService.PdcData(change.newKey().serial(),
                RealEstateService.ITEM_TYPE_KEY, 0, change.newKey().issuedAtEpochMs());
        assertTrue(realEstateService.keyValid(newKeyData).join(), "reissued key must open");
    }

    @Test
    void expungeArchivesRecordsWithoutDeletingThem() throws InterruptedException {
        Account client = fundedAccount(50_000);
        UUID lawyer = UUID.randomUUID();
        long recordId = legalService.addRecord(client.ownerRef(), "MINOR", "schiamazzi",
                UUID.randomUUID(), "Officer").join();
        assertTrue(legalService.markServed(recordId, UUID.randomUUID(), "Officer").join());

        assertEquals(LegalService.ExpungeResult.COMPLETED,
                legalService.expunge(client.ownerRef(), client.id(), lawyer, "Lawyer").join());

        // The row still exists, archived — never deleted (M3 exit gate).
        String status = databaseManager.db().supply(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT status FROM criminal_records WHERE id = ?")) {
                statement.setLong(1, recordId);
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        }).join();
        assertEquals("EXPUNGED", status);
        awaitAuditCount("RECORD_EXPUNGE", 1);
        assertEquals(0, legalService.activeRecords(client.ownerRef()).join().size());
    }

    @Test
    void openOrMajorRecordsBlockExpungement() {
        Account client = fundedAccount(50_000);
        legalService.addRecord(client.ownerRef(), "MAJOR", "rapina",
                UUID.randomUUID(), "Officer").join();
        assertEquals(LegalService.ExpungeResult.NOT_ELIGIBLE,
                legalService.expunge(client.ownerRef(), client.id(),
                        UUID.randomUUID(), "Lawyer").join());
    }

    @Test
    void unauthorizedEvidenceAccessFailsAndIsAudited() {
        long evidenceId = legalService.createEvidence("coltello insanguinato", null,
                UUID.randomUUID(), "Officer").join();
        long deniedBefore = auditCount("EVIDENCE_ACCESS_DENIED");

        var view = legalService.viewEvidence(evidenceId, UUID.randomUUID(), "Sneaky", false).join();
        assertTrue(view.isEmpty(), "unauthorized access must fail (M3 exit gate)");
        assertEquals(deniedBefore + 1, auditCount("EVIDENCE_ACCESS_DENIED"));

        // Authorized access works and extends the chain of custody.
        int custodyBefore = legalService.custodyCount(evidenceId).join();
        assertTrue(legalService.viewEvidence(evidenceId, UUID.randomUUID(), "Officer", true)
                .join().isPresent());
        assertEquals(custodyBefore + 1, legalService.custodyCount(evidenceId).join());
    }

    @Test
    void detentionIsSingleActiveAndAppealNeedsOverrun() {
        UUID detainee = UUID.randomUUID();
        UUID officer = UUID.randomUUID();
        assertTrue(legalService.arrest(detainee, officer, "Officer", 30).join());
        assertFalse(legalService.arrest(detainee, officer, "Officer", 30).join(),
                "one active detention per player");

        assertFalse(legalService.appeal(detainee, UUID.randomUUID(), "Lawyer").join(),
                "appeal must fail while within the allowed duration");

        // Backdate the detention to simulate an overrun, then appeal succeeds.
        databaseManager.db().supply(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE detentions SET started_at = TIMESTAMPADD(HOUR, -2, CURRENT_TIMESTAMP(3)) "
                            + "WHERE player_uuid = ? AND state = 'DETAINED'")) {
                statement.setString(1, detainee.toString());
                statement.executeUpdate();
            }
            return null;
        }).join();
        assertTrue(legalService.appeal(detainee, UUID.randomUUID(), "Lawyer").join(),
                "appeal must succeed on the machine-verifiable overrun rule");
        assertFalse(legalService.release(detainee, officer, "Officer", "POLICE").join(),
                "already released by the appeal");
    }

    @Test
    void dirtyRentalConsumesNotesOnceAndFundsBlackSafe() throws InterruptedException {
        realEstateService.createProperty("magazzino_nero", "APARTMENT", "world", 200, 64, 200,
                null, 10_000, true, null, "Test").join();
        UUID tenant = UUID.randomUUID();
        List<SerializedItem> dirtyNotes = bankingService.issueDirty(tenant, 10_000, tenant).join();
        assertFalse(dirtyNotes.isEmpty());
        List<SerializedItemService.PdcData> offered = new ArrayList<>();
        for (SerializedItem note : dirtyNotes) {
            offered.add(new SerializedItemService.PdcData(note.serial(), ItemTypes.DIRTY_MONEY,
                    note.denomination(), note.issuedAtEpochMs()));
        }

        long safeBefore = realEstateService.blackSafeBalance().join();
        var rent = realEstateService.rentDirty("magazzino_nero", tenant, offered,
                UUID.randomUUID(), "Director").join();
        assertTrue(rent.completed());
        assertEquals(10_000, rent.collectedCents());
        assertEquals(safeBefore + 10_000, realEstateService.blackSafeBalance().join());

        // Replaying the same notes cannot pay again (state + note transitions).
        var replay = realEstateService.rentDirty("magazzino_nero", tenant, offered,
                UUID.randomUUID(), "Director").join();
        assertFalse(replay.completed());

        // Destroying the file ends the rental but keeps rows and audit history.
        var fileData = new SerializedItemService.PdcData(rent.fileItem().serial(),
                RealEstateService.ITEM_TYPE_BLACK_FILE, 0, rent.fileItem().issuedAtEpochMs());
        assertTrue(realEstateService.destroyBlackFile(fileData, UUID.randomUUID(), "Anon").join());
        awaitAuditCount("BLACK_FILE_DESTROYED", 1);
        String fileState = databaseManager.db().supply(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT state FROM black_property_files WHERE item_serial = ?")) {
                statement.setString(1, rent.fileItem().serial().toString());
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        }).join();
        assertEquals("DESTROYED", fileState, "destroyed file row must remain");

        // Director-only safe withdrawal decrements once.
        assertTrue(realEstateService.blackSafeWithdraw(10_000, UUID.randomUUID(), "Director").join());
        assertFalse(realEstateService.blackSafeWithdraw(999_999_999L, UUID.randomUUID(),
                "Director").join(), "cannot overdraw the black safe");
    }

    @Test
    void contractValidatesExactlyOnce() {
        UUID partyA = UUID.randomUUID();
        UUID partyB = UUID.randomUUID();
        SerializedItem item = legalService.createSignedContract(partyA, partyB,
                "Compravendita cavallo: 100 euro").join();
        UUID contractId = legalService.contractIdFromMetadata(item.metadata()).orElseThrow();

        List<CompletableFuture<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            attempts.add(legalService.validateContract(contractId, UUID.randomUUID(), "Lawyer"));
        }
        long validated = attempts.stream().map(CompletableFuture::join)
                .filter(Boolean::booleanValue).count();
        assertEquals(1, validated, "a contract validates exactly once");
        assertEquals("VALIDATED", legalService.contractState(contractId).join().orElseThrow());
    }
}
