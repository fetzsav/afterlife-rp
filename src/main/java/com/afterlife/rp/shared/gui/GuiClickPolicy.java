package com.afterlife.rp.shared.gui;

import org.bukkit.event.inventory.ClickType;

/**
 * Decides which clicks a GUI dispatches to button handlers. Every click event on
 * a GUI inventory is cancelled regardless; this only gates handler dispatch.
 * Shift-clicks, hotbar swaps, double-clicks, drops, and keyboard tricks are
 * never dispatched (master plan §14).
 */
public final class GuiClickPolicy {

    private GuiClickPolicy() {}

    public static boolean shouldDispatch(ClickType clickType, boolean clickedTopInventory) {
        if (!clickedTopInventory) {
            return false;
        }
        return clickType == ClickType.LEFT || clickType == ClickType.RIGHT;
    }
}
