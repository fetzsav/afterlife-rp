package com.afterlife.rp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.config.DatabaseSettings;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.identity.IdentityRepository;
import com.afterlife.rp.shared.identity.PlayerIdentity;
import com.afterlife.rp.shared.items.ItemStatus;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemRepository;
import com.afterlife.rp.shared.regions.Poi;
import com.afterlife.rp.shared.regions.PoiRepository;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Database integration tests against disposable MariaDB (master plan §15).
 * Run with AFTERLIFE_IT=1 ./gradlew test
 */
@Tag("integration")
@Testcontainers
class FoundationIT {

    @Container
    private static final MariaDBContainer<?> MARIADB =
            new MariaDBContainer<>("mariadb:11.4").withDatabaseName("afterlife");

    private static DatabaseManager databaseManager;

    private static DatabaseSettings settings() {
        return new DatabaseSettings(
                MARIADB.getHost(),
                MARIADB.getFirstMappedPort(),
                MARIADB.getDatabaseName(),
                MARIADB.getUsername(),
                MARIADB.getPassword(),
                8,
                5000);
    }

    @BeforeAll
    static void start() {
        databaseManager = new DatabaseManager(
                settings(), FoundationIT.class.getClassLoader(), Runnable::run,
                Logger.getLogger("FoundationIT"));
        assertEquals(DatabaseManager.State.READY, databaseManager.start().join(),
                "database must start and migrate");
    }

    @AfterAll
    static void stop() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    @Test
    void migrationCreatedCoreTables() throws Exception {
        Set<String> tables = new HashSet<>();
        databaseManager.db().supply(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SHOW TABLES")) {
                while (rs.next()) {
                    tables.add(rs.getString(1).toLowerCase());
                }
            }
            return null;
        }).join();
        assertTrue(tables.containsAll(Set.of(
                "players", "audit_events", "points_of_interest", "serialized_items")));
    }

    @Test
    void concurrentDistinctPlayersGetUniquePublicIds() {
        IdentityRepository repository = new IdentityRepository();
        List<CompletableFuture<PlayerIdentity>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            UUID uuid = UUID.randomUUID();
            String name = "Player" + i;
            futures.add(databaseManager.db().inTransaction(
                    connection -> repository.ensure(connection, uuid, name)));
        }
        Set<Long> publicIds = new HashSet<>();
        for (CompletableFuture<PlayerIdentity> future : futures) {
            publicIds.add(future.join().publicId());
        }
        assertEquals(40, publicIds.size(), "every player must get a unique public ID");
    }

    @Test
    void samePlayerConcurrentEnsureCreatesOneRow() {
        IdentityRepository repository = new IdentityRepository();
        UUID uuid = UUID.randomUUID();
        List<CompletableFuture<PlayerIdentity>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            futures.add(databaseManager.db().inTransaction(
                    connection -> repository.ensure(connection, uuid, "SamePlayer")));
        }
        Set<Long> publicIds = new HashSet<>();
        for (CompletableFuture<PlayerIdentity> future : futures) {
            publicIds.add(future.join().publicId());
        }
        assertEquals(1, publicIds.size(), "one player must map to exactly one public ID");
    }

    @Test
    void playerLocaleRoundTrips() {
        IdentityRepository repository = new IdentityRepository();
        UUID uuid = UUID.randomUUID();
        databaseManager.db().inTransaction(connection ->
                repository.ensure(connection, uuid, "LangPlayer")).join();
        // Default (unset) locale is null → server default.
        assertEquals(null, databaseManager.db().supply(connection ->
                repository.find(connection, uuid)).join().orElseThrow().locale());
        // A chosen language persists and reads back.
        databaseManager.db().inTransaction(connection ->
                repository.updateLocale(connection, uuid, "it")).join();
        assertEquals("it", databaseManager.db().supply(connection ->
                repository.find(connection, uuid)).join().orElseThrow().locale());
    }

    @Test
    void serializedItemStatusTransitionWinsExactlyOnce() {
        SerializedItemRepository repository = new SerializedItemRepository();
        UUID serial = UUID.randomUUID();
        databaseManager.db().inTransaction(connection -> {
            repository.insert(connection, new SerializedItem(
                    serial, "dirty_money", null, 500L, ItemStatus.ISSUED, null,
                    System.currentTimeMillis(), null));
            return null;
        }).join();

        AtomicInteger successes = new AtomicInteger();
        List<CompletableFuture<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            attempts.add(databaseManager.db().inTransaction(connection ->
                    repository.transition(connection, serial, ItemStatus.ISSUED, ItemStatus.REDEEMED))
                    .thenAccept(won -> {
                        if (won) {
                            successes.incrementAndGet();
                        }
                    }));
        }
        attempts.forEach(CompletableFuture::join);
        assertEquals(1, successes.get(), "a serial must redeem exactly once (rule 13)");
    }

    @Test
    void poiSurvivesReconnect() {
        PoiRepository repository = new PoiRepository();
        UUID id = UUID.randomUUID();
        databaseManager.db().inTransaction(connection -> {
            repository.insert(connection, new Poi(
                    id, "atm_centrale", "ATM", "world", 100.5, 64, -20.5,
                    0f, 0f, "bank_district", "ACTIVE", null));
            return null;
        }).join();

        // Simulates a server restart: a fresh pool against the same database.
        DatabaseManager second = new DatabaseManager(
                settings(), FoundationIT.class.getClassLoader(), Runnable::run,
                Logger.getLogger("FoundationIT-2"));
        assertEquals(DatabaseManager.State.READY, second.start().join());
        try {
            List<Poi> reloaded = second.db().supply(repository::findAll).join();
            assertTrue(reloaded.stream().anyMatch(poi ->
                    poi.id().equals(id) && "bank_district".equals(poi.regionId())));
        } finally {
            second.shutdown();
        }
    }

    @Test
    void auditEventsInsertAndRemainQueryable() {
        databaseManager.db().supply(connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO audit_events (actor_uuid, actor_name, action, target, context) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, "TestActor");
                statement.setString(3, "TEST_ACTION");
                statement.setString(4, "target-1");
                statement.setString(5, "{\"k\":\"v\"}");
                statement.executeUpdate();
            }
            return null;
        }).join();
        long count = databaseManager.db().supply(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(
                            "SELECT COUNT(*) FROM audit_events WHERE action = 'TEST_ACTION'")) {
                rs.next();
                return rs.getLong(1);
            }
        }).join();
        assertTrue(count >= 1);
    }
}
