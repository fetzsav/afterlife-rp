package com.afterlife.rp.module.ems;

import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Presentation for medical items; authority is PDC + DB (rule 6). */
public final class EmsItems {

    private EmsItems() {}

    public static ItemStack toStack(SerializedItemService itemService, SerializedItem item) {
        return switch (item.itemType()) {
            case "forceps" -> named(itemService, item, Material.SHEARS, "Pinze chirurgiche",
                    NamedTextColor.AQUA);
            case "bandage" -> named(itemService, item, Material.PAPER, "Benda sterile",
                    NamedTextColor.WHITE);
            case "splint" -> named(itemService, item, Material.STICK, "Stecca ortopedica",
                    NamedTextColor.YELLOW);
            case "medkit" -> named(itemService, item, Material.BUNDLE, "Kit medico",
                    NamedTextColor.RED);
            case "defibrillator" -> named(itemService, item, Material.LIGHTNING_ROD,
                    "Defibrillatore", NamedTextColor.GOLD);
            case "scanner" -> named(itemService, item, Material.SPYGLASS, "Scanner medico",
                    NamedTextColor.AQUA);
            case "extraction_syringe" -> named(itemService, item, Material.ARROW,
                    "Siringa da estrazione", NamedTextColor.DARK_GREEN);
            case "adrenaline" -> named(itemService, item, Material.POTION, "Adrenalina",
                    NamedTextColor.DARK_RED);
            case EmsService.ITEM_CHEMICAL -> named(itemService, item, Material.SLIME_BALL,
                    "Composto chimico tossico", NamedTextColor.DARK_GREEN);
            case EmsService.ITEM_CERTIFICATE -> named(itemService, item, Material.PAPER,
                    "Certificato medico", NamedTextColor.GREEN);
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
