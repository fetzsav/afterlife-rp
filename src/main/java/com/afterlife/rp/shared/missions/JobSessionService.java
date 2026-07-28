package com.afterlife.rp.shared.missions;

import com.afterlife.rp.database.DatabaseManager;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** On-duty job sessions (§6.1). Duty ends on quit; cache mirrors the DB. */
public final class JobSessionService {

    private final DatabaseManager databaseManager;
    private final Map<UUID, Set<String>> onDuty = new ConcurrentHashMap<>();

    public JobSessionService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public boolean isOnDuty(UUID player, String job) {
        return onDuty.getOrDefault(player, Set.of()).contains(job);
    }

    public CompletableFuture<Boolean> start(UUID player, String job) {
        if (isOnDuty(player, job)) {
            return CompletableFuture.completedFuture(false);
        }
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO job_sessions (id, player_uuid, job) VALUES (?, ?, ?)")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, player.toString());
                statement.setString(3, job);
                statement.executeUpdate();
            }
            return true;
        }).thenApply(started -> {
            if (started) {
                onDuty.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(job);
            }
            return started;
        });
    }

    public CompletableFuture<Boolean> end(UUID player, String job) {
        Set<String> jobs = onDuty.getOrDefault(player, Set.of());
        if (!jobs.contains(job)) {
            return CompletableFuture.completedFuture(false);
        }
        jobs.remove(job);
        return closeSessions(player, job).thenApply(v -> true);
    }

    /** Quit/disconnect: end every active session for the player. */
    public CompletableFuture<Void> endAll(UUID player) {
        onDuty.remove(player);
        return closeSessions(player, null);
    }

    /** Startup recovery: sessions left ACTIVE by a crash are closed. */
    public CompletableFuture<Integer> closeStaleOnStartup() {
        onDuty.clear();
        return databaseManager.db().inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE job_sessions SET state = 'ENDED', ended_at = CURRENT_TIMESTAMP(3), "
                            + "version = version + 1 WHERE state = 'ACTIVE'")) {
                return statement.executeUpdate();
            }
        });
    }

    private CompletableFuture<Void> closeSessions(UUID player, String job) {
        return databaseManager.db().inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE job_sessions SET state = 'ENDED', ended_at = CURRENT_TIMESTAMP(3), "
                            + "version = version + 1 WHERE player_uuid = ? AND state = 'ACTIVE'"
                            + (job == null ? "" : " AND job = ?"))) {
                statement.setString(1, player.toString());
                if (job != null) {
                    statement.setString(2, job);
                }
                statement.executeUpdate();
            }
            return null;
        });
    }
}
