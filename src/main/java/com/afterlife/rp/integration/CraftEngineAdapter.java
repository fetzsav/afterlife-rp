package com.afterlife.rp.integration;

import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/**
 * CraftEngine-backed custom item provider (ADR 0002). CraftEngine classes are
 * only touched behind the availability guard, so the plugin runs unchanged when
 * CraftEngine is absent — callers keep their vanilla-material fallback.
 */
public final class CraftEngineAdapter implements CustomItemAdapter, Adapter {

    private final boolean available;

    public CraftEngineAdapter() {
        this.available = Bukkit.getPluginManager().isPluginEnabled("CraftEngine");
    }

    @Override
    public String name() {
        return "CraftEngine";
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String detail() {
        return available
                ? "custom item models"
                : "install CraftEngine for custom item models (vanilla fallback active)";
    }

    @Override
    public Optional<ItemStack> render(String catalogId, Component displayName, int amount) {
        if (!available || catalogId == null || catalogId.isBlank()) {
            return Optional.empty();
        }
        return Hook.render(catalogId, displayName, amount);
    }

    /** Isolated so CraftEngine classes never load when the plugin is absent. */
    private static final class Hook {
        static Optional<ItemStack> render(String catalogId, Component displayName, int amount) {
            try {
                BukkitItemDefinition definition = CraftEngineItems.byId(catalogId);
                if (definition == null) {
                    return Optional.empty();
                }
                ItemStack stack = definition.buildBukkitItem();
                if (stack == null) {
                    return Optional.empty();
                }
                stack.setAmount(Math.max(1, amount));
                if (displayName != null) {
                    var meta = stack.getItemMeta();
                    meta.displayName(displayName);
                    stack.setItemMeta(meta);
                }
                return Optional.of(stack);
            } catch (Throwable t) {
                // A missing/renamed catalog id must never break item delivery.
                return Optional.empty();
            }
        }
    }
}
