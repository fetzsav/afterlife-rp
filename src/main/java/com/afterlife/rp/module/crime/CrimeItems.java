package com.afterlife.rp.module.crime;

import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Presentation for crime items; authority is PDC + DB (rule 6). */
public final class CrimeItems {

    private CrimeItems() {}

    public static ItemStack toStack(SerializedItemService itemService, SerializedItem item) {
        return switch (item.itemType()) {
            case CrimeService.ITEM_DRUG -> named(itemService, item, Material.SUGAR,
                    "Dose", NamedTextColor.LIGHT_PURPLE);
            case CrimeService.ITEM_SEALED -> named(itemService, item, Material.PAPER,
                    "Sacchetto sigillato", NamedTextColor.GRAY);
            case CrimeService.ITEM_HACK_DEVICE -> named(itemService, item, Material.COMPARATOR,
                    "Dispositivo di hacking ATM", NamedTextColor.DARK_RED);
            default -> named(itemService, item, Material.PAPER, item.itemType(),
                    NamedTextColor.WHITE);
        };
    }

    private static ItemStack named(SerializedItemService itemService, SerializedItem item,
            Material material, String name, NamedTextColor color) {
        return itemService.toItemStack(item, material,
                Component.text(name, color).decoration(TextDecoration.ITALIC, false));
    }
}
