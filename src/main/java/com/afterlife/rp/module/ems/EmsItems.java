package com.afterlife.rp.module.ems;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Presentation for medical items; authority is PDC + DB (rule 6). Names come
 * from items.ems.* in the default language (an item reads the same for all).
 */
public final class EmsItems {

    private EmsItems() {}

    public static ItemStack toStack(SerializedItemService itemService, Messages messages,
            SerializedItem item) {
        return switch (item.itemType()) {
            case "forceps" -> named(itemService, messages, item, Material.SHEARS,
                    "items.ems.forceps");
            case "bandage" -> named(itemService, messages, item, Material.PAPER,
                    "items.ems.bandage");
            case "splint" -> named(itemService, messages, item, Material.STICK,
                    "items.ems.splint");
            case "medkit" -> named(itemService, messages, item, Material.BUNDLE,
                    "items.ems.medkit");
            case "defibrillator" -> named(itemService, messages, item, Material.LIGHTNING_ROD,
                    "items.ems.defibrillator");
            case "scanner" -> named(itemService, messages, item, Material.SPYGLASS,
                    "items.ems.scanner");
            case "extraction_syringe" -> named(itemService, messages, item, Material.ARROW,
                    "items.ems.extraction-syringe");
            case "adrenaline" -> named(itemService, messages, item, Material.POTION,
                    "items.ems.adrenaline");
            case EmsService.ITEM_CHEMICAL -> named(itemService, messages, item, Material.SLIME_BALL,
                    "items.ems.chemical");
            case EmsService.ITEM_CERTIFICATE -> named(itemService, messages, item, Material.PAPER,
                    "items.ems.certificate");
            default -> itemService.toItemStack(item, Material.PAPER,
                    messages.itemName("items.unknown",
                            Placeholder.unparsed("type", item.itemType())));
        };
    }

    private static ItemStack named(SerializedItemService itemService, Messages messages,
            SerializedItem item, Material material, String key) {
        return itemService.toItemStack(item, material, messages.itemName(key));
    }
}
