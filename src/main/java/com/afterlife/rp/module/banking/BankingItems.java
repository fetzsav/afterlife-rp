package com.afterlife.rp.module.banking;

import com.afterlife.rp.shared.economy.Money;
import com.afterlife.rp.shared.items.ItemTypes;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Presentation for banking instruments. Names/materials are cosmetic only —
 * authority lives in PDC + database (rule 6). Custom models arrive with the
 * resource-pack milestone.
 */
public final class BankingItems {

    private BankingItems() {}

    public static ItemStack toStack(SerializedItemService itemService, SerializedItem item) {
        return switch (item.itemType()) {
            case ItemTypes.BANKNOTE -> itemService.toItemStack(item, Material.PAPER,
                    Component.text("Banconota da " + Money.format(denomination(item)),
                            NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            case ItemTypes.DIRTY_MONEY -> itemService.toItemStack(item, Material.PAPER,
                    Component.text("Denaro sporco — " + Money.format(denomination(item)),
                            NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false));
            case ItemTypes.CREDIT_CARD -> itemService.toItemStack(item, Material.NAME_TAG,
                    Component.text("Carta di credito AfterLife", NamedTextColor.AQUA)
                            .decoration(TextDecoration.ITALIC, false));
            case ItemTypes.CHECK -> itemService.toItemStack(item, Material.PAPER,
                    Component.text("Assegno — " + Money.format(denomination(item)),
                            NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            default -> itemService.toItemStack(item, Material.PAPER,
                    Component.text(item.itemType(), NamedTextColor.WHITE)
                            .decoration(TextDecoration.ITALIC, false));
        };
    }

    private static long denomination(SerializedItem item) {
        return item.denomination() == null ? 0 : item.denomination();
    }
}
