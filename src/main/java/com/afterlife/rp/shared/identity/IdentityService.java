package com.afterlife.rp.shared.identity;

import com.afterlife.rp.database.DatabaseManager;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Loads, caches, and mutates permanent player identities. */
public final class IdentityService {

    private final DatabaseManager databaseManager;
    private final IdentityRepository repository;
    private final Map<UUID, PlayerIdentity> cache = new ConcurrentHashMap<>();

    public IdentityService(DatabaseManager databaseManager, IdentityRepository repository) {
        this.databaseManager = databaseManager;
        this.repository = repository;
    }

    /** Called from the async pre-login thread; safe to block there. */
    public CompletableFuture<PlayerIdentity> loadAndCache(UUID uuid, String name) {
        return databaseManager.db().inTransaction(connection -> repository.ensure(connection, uuid, name))
                .thenApply(identity -> {
                    cache.put(uuid, identity);
                    return identity;
                });
    }

    public Optional<PlayerIdentity> cached(UUID uuid) {
        return Optional.ofNullable(cache.get(uuid));
    }

    /** Sets ({@code nickname != null}) or clears the nickname and refreshes the cache. */
    public CompletableFuture<PlayerIdentity> setNickname(UUID uuid, String nickname) {
        return databaseManager.db().supply(connection -> {
            repository.updateNickname(connection, uuid, nickname);
            return repository.find(connection, uuid).orElseThrow();
        }).thenApply(identity -> {
            cache.put(uuid, identity);
            return identity;
        });
    }

    public void handleQuit(UUID uuid) {
        cache.remove(uuid);
        databaseManager.db().supply(connection -> {
            repository.touchLastSeen(connection, uuid);
            return null;
        });
    }

    public void clearCache() {
        cache.clear();
    }
}
