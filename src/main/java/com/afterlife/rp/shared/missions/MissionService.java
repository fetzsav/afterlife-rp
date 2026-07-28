package com.afterlife.rp.shared.missions;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.database.DatabaseManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared mission framework (§17 M5): persisted state machines with one active
 * mission per type per player, one-winner transitions (a reward can never pay
 * twice), and recovery on restart, quit, and deadline expiry (rule 13).
 */
public final class MissionService {

    private final DatabaseManager databaseManager;
    private final MissionRepository repository;
    private final AuditService auditService;
    private final Logger logger;

    private final Map<String, MissionHandler> handlersByTypePrefix = new ConcurrentHashMap<>();
    private final Map<UUID, List<Mission>> activeByPlayer = new ConcurrentHashMap<>();

    public MissionService(DatabaseManager databaseManager, MissionRepository repository,
            AuditService auditService, Logger logger) {
        this.databaseManager = databaseManager;
        this.repository = repository;
        this.auditService = auditService;
        this.logger = logger;
    }

    public void registerHandler(String typePrefix, MissionHandler handler) {
        handlersByTypePrefix.put(typePrefix, handler);
    }

    public MissionHandler handlerFor(String type) {
        for (Map.Entry<String, MissionHandler> entry : handlersByTypePrefix.entrySet()) {
            if (type.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Creates an ACTIVE mission unless the player already has one of this type. */
    public CompletableFuture<Optional<Mission>> claim(String type, UUID owner, UUID targetPoi,
            UUID originPoi, int deadlineSeconds, long rewardSnapshot, String data) {
        Mission mission = new Mission(UUID.randomUUID(), type, owner, "ACTIVE",
                targetPoi, originPoi, rewardSnapshot, data, 0);
        return databaseManager.db().<Optional<Mission>>inTransaction(connection -> {
            if (repository.findActiveOfType(connection, owner, type).isPresent()) {
                return Optional.empty();
            }
            repository.insert(connection, mission, deadlineSeconds);
            return Optional.of(mission);
        }).thenApply(claimed -> {
            claimed.ifPresent(m -> cacheAdd(owner, m));
            return claimed;
        });
    }

    /**
     * The single reward gate: ACTIVE -> COMPLETED wins exactly once. Callers
     * pay/deliver ONLY when this returns true (M5 exit gate).
     */
    public CompletableFuture<Boolean> complete(UUID missionId) {
        return transition(missionId, "ACTIVE", "COMPLETED");
    }

    public CompletableFuture<Boolean> transition(UUID missionId, String from, String to) {
        return databaseManager.db().<Boolean>inTransaction(connection ->
                repository.transition(connection, missionId, from, to))
                .thenApply(changed -> {
                    if (changed) {
                        cacheRemove(missionId);
                    }
                    return changed;
                });
    }

    public CompletableFuture<Void> updateData(UUID missionId, String data) {
        return databaseManager.db().<Void>inTransaction(connection -> {
            repository.updateData(connection, missionId, data);
            return null;
        }).thenApply(v -> {
            activeByPlayer.values().forEach(missions -> missions.replaceAll(mission ->
                    mission.id().equals(missionId)
                            ? new Mission(mission.id(), mission.type(), mission.owner(),
                                    mission.state(), mission.targetPoiId(), mission.originPoiId(),
                                    mission.rewardSnapshot(), data, mission.version() + 1)
                            : mission));
            return null;
        });
    }

    public Optional<Mission> cachedActiveOfType(UUID owner, String type) {
        return activeByPlayer.getOrDefault(owner, List.of()).stream()
                .filter(mission -> mission.type().equals(type))
                .findFirst();
    }

    public List<Mission> cachedActive(UUID owner) {
        return List.copyOf(activeByPlayer.getOrDefault(owner, List.of()));
    }

    public CompletableFuture<Void> loadActiveOnJoin(UUID owner) {
        return databaseManager.db().supply(connection -> repository.findActiveFor(connection, owner))
                .thenAccept(missions -> activeByPlayer.put(owner,
                        new ArrayList<>(missions)));
    }

    /** Cancels every ACTIVE mission of a player (quit, AFK); notifies handlers. */
    public CompletableFuture<Void> cancelAllFor(UUID owner, String reason) {
        return databaseManager.db().supply(connection -> repository.findActiveFor(connection, owner))
                .thenCompose(missions -> {
                    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                    for (Mission mission : missions) {
                        chain = chain.thenCompose(v -> end(mission, "CANCELLED", reason));
                    }
                    return chain;
                }).thenApply(v -> {
                    activeByPlayer.remove(owner);
                    return null;
                });
    }

    /** Expires overdue missions (startup recovery + periodic sweep). */
    public CompletableFuture<Integer> expireOverdue(String trigger) {
        return databaseManager.db().supply(repository::findOverdue).thenCompose(overdue -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (Mission mission : overdue) {
                chain = chain.thenCompose(v -> end(mission, "EXPIRED", trigger));
            }
            return chain.thenApply(v -> overdue.size());
        });
    }

    /** One-winner end + handler callback + audit. */
    public CompletableFuture<Void> end(Mission mission, String endState, String reason) {
        return transition(mission.id(), "ACTIVE", endState).thenAccept(changed -> {
            if (!changed) {
                return;
            }
            auditService.log(null, "SYSTEM", "MISSION_" + endState, mission.id().toString(),
                    Map.of("type", mission.type(), "owner", mission.owner().toString(),
                            "reason", reason));
            MissionHandler handler = handlerFor(mission.type());
            if (handler != null) {
                try {
                    handler.onEnded(mission, endState);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Mission handler failed for " + mission.id(), e);
                }
            }
        });
    }

    public void evict(UUID owner) {
        activeByPlayer.remove(owner);
    }

    private void cacheAdd(UUID owner, Mission mission) {
        activeByPlayer.computeIfAbsent(owner, k -> new ArrayList<>()).add(mission);
    }

    private void cacheRemove(UUID missionId) {
        activeByPlayer.values().forEach(missions ->
                missions.removeIf(mission -> mission.id().equals(missionId)));
    }
}
