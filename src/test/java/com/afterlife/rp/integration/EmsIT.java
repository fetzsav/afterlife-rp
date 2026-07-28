package com.afterlife.rp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.config.DatabaseSettings;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.module.ems.EmsConfig;
import com.afterlife.rp.module.ems.EmsService;
import com.afterlife.rp.shared.economy.Account;
import com.afterlife.rp.shared.economy.AccountRepository;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.LedgerRepository;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.items.SerializedItemRepository;
import com.afterlife.rp.shared.missions.MissionRepository;
import com.afterlife.rp.shared.missions.MissionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Milestone 6 exit-gate tests: treatments require the exact sequence, batches
 * trace correctly, illegal extraction cancels safely (§17 M6).
 */
@Tag("integration")
@Testcontainers
class EmsIT {

    @Container
    private static final MariaDBContainer<?> MARIADB =
            new MariaDBContainer<>("mariadb:11.4").withDatabaseName("afterlife");

    private static DatabaseManager databaseManager;
    private static AccountService accountService;
    private static LedgerService ledgerService;
    private static MissionService missionService;
    private static EmsService emsService;

    @BeforeAll
    static void start() {
        databaseManager = new DatabaseManager(
                new DatabaseSettings(MARIADB.getHost(), MARIADB.getFirstMappedPort(),
                        MARIADB.getDatabaseName(), MARIADB.getUsername(), MARIADB.getPassword(),
                        8, 5000),
                EmsIT.class.getClassLoader(), Runnable::run, Logger.getLogger("EmsIT"));
        assertEquals(DatabaseManager.State.READY, databaseManager.start().join());

        AccountRepository accountRepository = new AccountRepository();
        AuditService auditService = new AuditService(databaseManager, Logger.getLogger("EmsIT"));
        accountService = new AccountService(databaseManager, accountRepository, auditService,
                "05428", "11101");
        ledgerService = new LedgerService(databaseManager, accountRepository,
                new LedgerRepository(), accountService::onLedgerCommit);
        missionService = new MissionService(databaseManager, new MissionRepository(),
                auditService, Logger.getLogger("EmsIT"));
        EmsConfig config = new EmsConfig(true, 4.0, false, 6.0, 0.35,
                Map.of("FALL", new EmsConfig.CauseRule(1.0, List.of("FRACTURE"))),
                1500, 10,
                Map.of("FRACTURE", List.of("splint", "bandage"),
                        "BLEEDING", List.of("forceps", "bandage")),
                java.util.Set.of("bandage", "splint", "medkit"),
                List.of("HOSPITAL_WORKSTATION"),
                Map.of("bandage", 800L, "adrenaline", 6000L),
                3500, 10000, 14,
                30, 40, 1, List.of("EMERGENCY_POINT"), 6000, 15, 3,
                List.of("TOXIC_BARREL"), 45, 60, 3, List.of("HOSPITAL_WORKSTATION"));
        emsService = new EmsService(databaseManager, missionService, accountService,
                ledgerService, new SerializedItemRepository(), auditService, config);
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

    @Test
    void treatmentEnforcesTheExactToolSequence() {
        Account patient = fundedAccount(50_000);
        Account medic = fundedAccount(0);
        assertTrue(emsService.maybeInflict(patient.ownerRef(), "FALL", false).join().isPresent());

        // FRACTURE requires splint then bandage: the bandage first is rejected.
        var wrong = emsService.treat(medic.ownerRef(), patient.ownerRef(), patient.id(),
                medic.id(), "bandage", null).join();
        assertEquals(EmsService.TreatStatus.WRONG_TOOL, wrong.status());

        var step1 = emsService.treat(medic.ownerRef(), patient.ownerRef(), patient.id(),
                medic.id(), "splint", null).join();
        assertEquals(EmsService.TreatStatus.STEP_DONE, step1.status());
        assertEquals("bandage", step1.nextTool());

        var step2 = emsService.treat(medic.ownerRef(), patient.ownerRef(), patient.id(),
                medic.id(), "bandage", null).join();
        assertEquals(EmsService.TreatStatus.HEALED, step2.status());
        assertEquals(0, emsService.activeInjuries(patient.ownerRef()).join().size());

        // Billing: 2 steps x 15€ = 30€; medic got 10% commission.
        long medicBalance = accountService.cachedBalance(medic.id()).orElse(0L);
        assertEquals(300, medicBalance, "medic commission must be 10% of 3000 cents");
    }

    @Test
    void treatmentStepsAreOneWinnerUnderConcurrency() {
        Account patient = fundedAccount(50_000);
        Account medicA = fundedAccount(0);
        Account medicB = fundedAccount(0);
        assertTrue(emsService.maybeInflict(patient.ownerRef(), "FALL", false).join().isPresent());

        var attemptA = emsService.treat(medicA.ownerRef(), patient.ownerRef(), patient.id(),
                medicA.id(), "splint", null);
        var attemptB = emsService.treat(medicB.ownerRef(), patient.ownerRef(), patient.id(),
                medicB.id(), "splint", null);
        long advanced = List.of(attemptA.join(), attemptB.join()).stream()
                .filter(result -> result.status() == EmsService.TreatStatus.STEP_DONE)
                .count();
        assertEquals(1, advanced, "two medics cannot both advance the same step");
    }

    @Test
    void medicineBatchesTraceBackToTheirProducer() {
        Account medic = fundedAccount(10_000);
        var produced = emsService.produce(medic.ownerRef(), "DrRossi", medic.id(),
                "bandage", false, null).join();
        assertTrue(produced.isPresent());
        String code = produced.get().batchCode();
        assertTrue(code.startsWith("DrRossi-"));

        var info = emsService.traceBatch(code).join();
        assertTrue(info.isPresent());
        assertEquals("LEGAL", info.get().legality());
        assertEquals(medic.ownerRef().toString(), info.get().producer());

        // Illegal conversion path produces an ILLEGAL batch (§9.8).
        var chemicalMission = emsService.startExtraction(medic.ownerRef(), UUID.randomUUID(), 45)
                .join().orElseThrow();
        var chemical = emsService.finishExtraction(chemicalMission).join().orElseThrow();
        var adrenaline = emsService.convertChemical(medic.ownerRef(), "DrRossi",
                chemical.serial(), null).join();
        assertTrue(adrenaline.isPresent());
        assertEquals("ILLEGAL",
                emsService.traceBatch(adrenaline.get().batchCode()).join().orElseThrow().legality());
    }

    @Test
    void extractionCancelsSafelyWithoutProducingAChemical() {
        Account medic = fundedAccount(0);
        var mission = emsService.startExtraction(medic.ownerRef(), UUID.randomUUID(), 50)
                .join().orElseThrow();
        // Interrupted (movement/disconnect): the mission cancels...
        missionService.end(mission, "CANCELLED", "moved").join();
        // ...and the completion gate can never fire afterwards.
        assertTrue(emsService.finishExtraction(mission).join().isEmpty(),
                "a cancelled extraction must never produce a chemical (M6 exit gate)");
    }

    @Test
    void certificatesRequireAHealedPatient() {
        Account patient = fundedAccount(50_000);
        Account medic = fundedAccount(0);
        assertTrue(emsService.maybeInflict(patient.ownerRef(), "FALL", false).join().isPresent());

        var refused = emsService.issueCertificate(medic.ownerRef(), "DrRossi", medic.id(),
                patient.ownerRef(), patient.id()).join();
        assertEquals(EmsService.CertificateStatus.PATIENT_INJURED, refused.status());

        emsService.treat(medic.ownerRef(), patient.ownerRef(), patient.id(), medic.id(),
                "splint", null).join();
        emsService.treat(medic.ownerRef(), patient.ownerRef(), patient.id(), medic.id(),
                "bandage", null).join();
        var issued = emsService.issueCertificate(medic.ownerRef(), "DrRossi", medic.id(),
                patient.ownerRef(), patient.id()).join();
        assertEquals(EmsService.CertificateStatus.ISSUED, issued.status());
        assertNotNull(issued.item());
        assertFalse(emsService.batchCodeFromMetadata(issued.item().metadata()) != null,
                "certificates are not medicine batches");
    }
}
