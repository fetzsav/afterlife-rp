package com.afterlife.rp.integration;

import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

/**
 * Applies a custom model to a serialized item's presentation. Implementations
 * are soft dependencies: when the provider is absent, callers keep the vanilla
 * material fallback the item frameworks already produce (master plan §2.1).
 */
public interface CustomItemAdapter {

    /** True when the backing provider is loaded and usable. */
    boolean available();

    /**
     * Returns a provider-modelled stack for the given catalog id, preserving the
     * caller's display name and stack amount. Empty when the id is unmapped or
     * the provider is unavailable, so the caller uses its vanilla fallback.
     */
    Optional<ItemStack> render(String catalogId, Component displayName, int amount);
}
