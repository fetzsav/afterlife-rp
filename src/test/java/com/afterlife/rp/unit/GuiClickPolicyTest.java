package com.afterlife.rp.unit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.shared.gui.GuiClickPolicy;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

class GuiClickPolicyTest {

    @Test
    void onlyPrimaryClicksInTopInventoryDispatch() {
        assertTrue(GuiClickPolicy.shouldDispatch(ClickType.LEFT, true));
        assertTrue(GuiClickPolicy.shouldDispatch(ClickType.RIGHT, true));
    }

    @Test
    void exploitProneClickTypesNeverDispatch() {
        for (ClickType clickType : new ClickType[] {
                ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT, ClickType.DOUBLE_CLICK,
                ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.DROP,
                ClickType.CONTROL_DROP, ClickType.MIDDLE, ClickType.CREATIVE,
                ClickType.WINDOW_BORDER_LEFT, ClickType.WINDOW_BORDER_RIGHT, ClickType.UNKNOWN }) {
            assertFalse(GuiClickPolicy.shouldDispatch(clickType, true),
                    clickType + " must not dispatch");
        }
    }

    @Test
    void bottomInventoryClicksNeverDispatch() {
        assertFalse(GuiClickPolicy.shouldDispatch(ClickType.LEFT, false));
        assertFalse(GuiClickPolicy.shouldDispatch(ClickType.RIGHT, false));
    }
}
