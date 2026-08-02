package com.afterlife.rp.integration;

import com.afterlife.rp.shared.identity.IdentityService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Detects soft dependencies and reports them through /afterlife health. */
public final class IntegrationManager {

    /** No-provider fallback: callers keep their vanilla-material item stacks. */
    private static final CustomItemAdapter UNAVAILABLE_ITEMS = new CustomItemAdapter() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Optional<ItemStack> render(String catalogId, Component displayName, int amount) {
            return Optional.empty();
        }
    };

    private final List<Adapter> adapters = new ArrayList<>();
    private LuckPermsAdapter luckPerms;
    private WorldGuardAdapter worldGuard;
    private CraftEngineAdapter customItems;

    public void detect(Plugin plugin, IdentityService identityService, Logger logger) {
        adapters.clear();
        luckPerms = add(logger, LuckPermsAdapter::new);
        worldGuard = add(logger, WorldGuardAdapter::new);
        add(logger, () -> new PlaceholderApiAdapter(plugin, identityService));
        add(logger, () -> new PresenceAdapter("Vault",
                "economy bridge active",
                "install VaultUnlocked so other plugins can read AfterLife balances",
                "VaultUnlocked"));
        add(logger, () -> new PresenceAdapter("Citizens",
                "NPCs available to job modules",
                "install Citizens for NPC-driven jobs"));
        customItems = add(logger, CraftEngineAdapter::new);
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

    /** Custom-item provider; never null (falls back to an unavailable adapter). */
    public CustomItemAdapter customItems() {
        return customItems != null ? customItems : UNAVAILABLE_ITEMS;
    }

    public LuckPermsAdapter luckPerms() {
        return luckPerms;
    }

    public WorldGuardAdapter worldGuard() {
        return worldGuard;
    }
}
