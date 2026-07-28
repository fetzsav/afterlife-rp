package com.afterlife.rp.integration;

import com.afterlife.rp.shared.identity.IdentityService;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/** Detects soft dependencies and reports them through /afterlife health. */
public final class IntegrationManager {

    private final List<Adapter> adapters = new ArrayList<>();
    private LuckPermsAdapter luckPerms;
    private WorldGuardAdapter worldGuard;

    public void detect(Plugin plugin, IdentityService identityService, Logger logger) {
        adapters.clear();
        luckPerms = add(logger, LuckPermsAdapter::new);
        worldGuard = add(logger, WorldGuardAdapter::new);
        add(logger, () -> new PlaceholderApiAdapter(plugin, identityService));
        add(logger, () -> new PresenceAdapter("Vault",
                "ponte economia attivo dal Milestone 2",
                "installa Vault: richiesto dal Milestone 2 (economia)",
                "VaultUnlocked"));
        add(logger, () -> new PresenceAdapter("Citizens",
                "NPC disponibili per i moduli futuri",
                "installa Citizens: richiesto dai moduli con NPC (Milestone 5+)"));
        for (Adapter adapter : adapters) {
            logger.info("Integration " + adapter.name() + ": "
                    + (adapter.available() ? "active" : "missing") + " (" + adapter.detail() + ")");
        }
    }

    private <T extends Adapter> T add(Logger logger, java.util.function.Supplier<T> factory) {
        try {
            T adapter = factory.get();
            adapters.add(adapter);
            return adapter;
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Adapter initialization failed", t);
            return null;
        }
    }

    public List<Adapter> adapters() {
        return List.copyOf(adapters);
    }

    public LuckPermsAdapter luckPerms() {
        return luckPerms;
    }

    public WorldGuardAdapter worldGuard() {
        return worldGuard;
    }
}
