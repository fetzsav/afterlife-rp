package com.afterlife.rp.shared.gui;

import java.util.Map;
import net.kyori.adventure.text.Component;

/** A menu definition rendered into a session-bound inventory. */
public interface GuiMenu {

    Component title();

    /** Inventory size; must be a multiple of 9, at most 54. */
    int size();

    Map<Integer, GuiButton> buttons();

    /** Permission revalidated on every click (rule: revalidate per interaction). */
    default String permission() {
        return null;
    }
}
