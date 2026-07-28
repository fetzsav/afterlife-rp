package com.afterlife.rp.shared.gui;

import java.util.function.BiConsumer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/**
 * A clickable slot. The icon is presentation only — never an authoritative
 * identifier (rule 6); behavior lives entirely in the handler.
 */
public record GuiButton(ItemStack icon, String permission, BiConsumer<Player, ClickType> handler) {

    public static GuiButton of(ItemStack icon, BiConsumer<Player, ClickType> handler) {
        return new GuiButton(icon, null, handler);
    }
}
