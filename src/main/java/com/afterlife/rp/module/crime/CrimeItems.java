package com.afterlife.rp.module.crime;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Presentation for crime items; authority is PDC + DB (rule 6). Names come from
 * items.crime.* in the default language (an item reads the same for all).
 */
public final class CrimeItems {

    private CrimeItems() {}

    public static ItemStack toStack(SerializedItemService itemService, Messages messages,
            SerializedItem item) {
        return switch (item.itemType()) {
            case CrimeService.ITEM_DRUG -> itemService.toItemStack(item, Material.SUGAR,
                    messages.itemName("items.crime.drug"));
            case CrimeService.ITEM_SEALED -> itemService.toItemStack(item, Material.PAPER,
                    messages.itemName("items.crime.sealed-bag"));
            case CrimeService.ITEM_HACK_DEVICE -> itemService.toItemStack(item, Material.COMPARATOR,
                    messages.itemName("items.crime.hack-device"));
            default -> itemService.toItemStack(item, Material.PAPER,
                    messages.itemName("items.unknown",
                            Placeholder.unparsed("type", item.itemType())));
        };
    }
}
