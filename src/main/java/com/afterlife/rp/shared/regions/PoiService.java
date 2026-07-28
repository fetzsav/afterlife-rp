package com.afterlife.rp.shared.regions;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.database.DatabaseManager;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;

/** Cached POI registry backed by the database; survives restarts (M1 exit gate). */
public final class PoiService {

    private final DatabaseManager databaseManager;
    private final PoiRepository repository;
    private final AuditService auditService;
    private final Map<String, Poi> byName = new ConcurrentHashMap<>();

    public PoiService(DatabaseManager databaseManager, PoiRepository repository, AuditService auditService) {
        this.databaseManager = databaseManager;
        this.repository = repository;
        this.auditService = auditService;
    }

    public CompletableFuture<Integer> load() {
        return databaseManager.db().supply(repository::findAll).thenApply(pois -> {
            byName.clear();
            for (Poi poi : pois) {
                byName.put(key(poi.name()), poi);
            }
            return pois.size();
        });
    }

    public Optional<Poi> byName(String name) {
        return Optional.ofNullable(byName.get(key(name)));
    }

    public Optional<Poi> byId(UUID id) {
        return byName.values().stream().filter(poi -> poi.id().equals(id)).findFirst();
    }

    public Collection<Poi> all() {
        return List.copyOf(byName.values());
    }

    public List<Poi> byTypeAndStatus(Collection<String> types, String status) {
        return byName.values().stream()
                .filter(poi -> types.contains(poi.type()) && poi.status().equals(status))
                .toList();
    }

    /** One-winner POI status transition (e.g. ACTIVE -> FAILED -> REPAIRING). */
    public CompletableFuture<Boolean> updateStatus(UUID poiId, String from, String to) {
        return databaseManager.db().<Boolean>inTransaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE points_of_interest SET status = ?, version = version + 1 "
                            + "WHERE id = ? AND status = ?")) {
                statement.setString(1, to);
                statement.setString(2, poiId.toString());
                statement.setString(3, from);
                return statement.executeUpdate() == 1;
            }
        }).thenApply(changed -> {
            if (changed) {
                byId(poiId).ifPresent(poi -> byName.put(key(poi.name()), new Poi(
                        poi.id(), poi.name(), poi.type(), poi.world(), poi.x(), poi.y(), poi.z(),
                        poi.yaw(), poi.pitch(), poi.regionId(), to, poi.createdBy())));
            }
            return changed;
        });
    }

    public CompletableFuture<Poi> create(
            String name, String type, Location location, String regionId, UUID createdBy, String actorName) {
        Poi poi = new Poi(
                UUID.randomUUID(),
                name,
                type,
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                regionId,
                "ACTIVE",
                createdBy);
        return databaseManager.db().inTransaction(connection -> {
            repository.insert(connection, poi);
            return poi;
        }).thenApply(saved -> {
            byName.put(key(saved.name()), saved);
            auditService.log(createdBy, actorName, "POI_CREATE", saved.name(), Map.of(
                    "type", saved.type(),
                    "world", saved.world(),
                    "x", String.valueOf(saved.x()),
                    "y", String.valueOf(saved.y()),
                    "z", String.valueOf(saved.z()),
                    "region", saved.regionId() == null ? "" : saved.regionId()));
            return saved;
        });
    }

    public CompletableFuture<Boolean> remove(String name, UUID actor, String actorName) {
        return databaseManager.db().inTransaction(connection -> repository.deleteByName(connection, name))
                .thenApply(removed -> {
                    if (removed) {
                        byName.remove(key(name));
                        auditService.log(actor, actorName, "POI_REMOVE", name, Map.of());
                    }
                    return removed;
                });
    }

    private String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
