package com.afterlife.rp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.config.DatabaseSettings;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.missions.JobSessionService;
import com.afterlife.rp.shared.missions.Mission;
import com.afterlife.rp.shared.missions.MissionRepository;
import com.afterlife.rp.shared.missions.MissionService;
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
 * Milestone 5 exit-gate tests: missions cannot reward twice; recovery on
 * restart/disconnect works (§17 M5).
 */
@Tag("integration")
@Testcontainers
class MissionIT {

    @Container
    private static final MariaDBContainer<?> MARIADB =
            new MariaDBContainer<>("mariadb:11.4").withDatabaseName("afterlife");

    private static DatabaseManager databaseManager;
    private static MissionService missionService;
    private static JobSessionService jobSessions;

    @BeforeAll
    static void start() {
        databaseManager = new DatabaseManager(
                new DatabaseSettings(MARIADB.getHost(), MARIADB.getFirstMappedPort(),
                        MARIADB.getDatabaseName(), MARIADB.getUsername(), MARIADB.getPassword(),
                        8, 5000),
                MissionIT.class.getClassLoader(), Runnable::run, Logger.getLogger("MissionIT"));
        assertEquals(DatabaseManager.State.READY, databaseManager.start().join());
        missionService = new MissionService(databaseManager, new MissionRepository(),
                new AuditService(databaseManager, Logger.getLogger("MissionIT")),
                Logger.getLogger("MissionIT"));
        jobSessions = new JobSessionService(databaseManager);
    }

    @AfterAll
    static void stop() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    @Test
    void oneActiveMissionPerTypePerPlayer() {
        UUID player = UUID.randomUUID();
        assertTrue(missionService.claim("FOOD_DELIVERY", player, null, null, 600, 0, null)
                .join().isPresent());
        assertTrue(missionService.claim("FOOD_DELIVERY", player, null, null, 600, 0, null)
                .join().isEmpty(), "second claim of the same type must be rejected");
        // A different type is allowed.
        assertTrue(missionService.claim("ELECTRICIAN_REPAIR", player, null, null, 600, 0, null)
                .join().isPresent());
    }

    @Test
    void missionCompletesExactlyOnceUnderConcurrency() {
        UUID player = UUID.randomUUID();
        Mission mission = missionService.claim("CONTRABAND_DELIVERY", player, null, null,
                600, 5000, null).join().orElseThrow();

        List<CompletableFuture<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            attempts.add(missionService.complete(mission.id()));
        }
        long winners = attempts.stream().map(CompletableFuture::join)
                .filter(Boolean::booleanValue).count();
        assertEquals(1, winners, "the reward gate must open exactly once (M5 exit gate)");
    }

    @Test
    void startupRecoveryExpiresOverdueMissions() {
        UUID player = UUID.randomUUID();
        Mission mission = missionService.claim("FOOD_DELIVERY", player, null, null,
                600, 0, null).join().orElseThrow();

        // Simulate a crash that left the mission past its deadline.
        databaseManager.db().supply(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE missions SET deadline = TIMESTAMPADD(HOUR, -1, CURRENT_TIMESTAMP(3)) "
                            + "WHERE id = ?")) {
                statement.setString(1, mission.id().toString());
                statement.executeUpdate();
            }
            return null;
        }).join();

        assertTrue(missionService.expireOverdue("test-startup").join() >= 1);
        assertFalse(missionService.complete(mission.id()).join(),
                "an expired mission can never pay out");
    }

    @Test
    void quitCancelsActiveMissions() {
        UUID player = UUID.randomUUID();
        missionService.claim("FOOD_DELIVERY", player, null, null, 600, 0, null).join().orElseThrow();
        missionService.cancelAllFor(player, "quit").join();
        assertTrue(missionService.claim("FOOD_DELIVERY", player, null, null, 600, 0, null)
                .join().isPresent(), "after quit-cancel the player can claim again");
    }

    @Test
    void jobSessionsCloseOnStartupRecovery() {
        UUID player = UUID.randomUUID();
        assertTrue(jobSessions.start(player, "DELIVERY").join());
        assertFalse(jobSessions.start(player, "DELIVERY").join(), "one active session per job");
        assertTrue(jobSessions.closeStaleOnStartup().join() >= 1);
        assertTrue(jobSessions.start(player, "DELIVERY").join(),
                "after recovery the player can start a fresh shift");
    }
}
