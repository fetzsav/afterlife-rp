package com.afterlife.rp.shared.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** Custom holder marking inventories owned by the GUI framework (rule 7). */
public final class GuiHolder implements InventoryHolder {

    private final GuiSession session;
    private Inventory inventory;

    public GuiHolder(GuiSession session) {
        this.session = session;
    }

    public GuiSession session() {
        return session;
    }

    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
