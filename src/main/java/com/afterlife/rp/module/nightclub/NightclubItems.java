package com.afterlife.rp.module.nightclub;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.shared.economy.Money;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Presentation for drinks and receipts; authority is PDC + DB (rule 6). Names
 * come from items.* in the default language (an item reads the same for all).
 */
public final class NightclubItems {

    private NightclubItems() {}

    public static ItemStack drinkStack(SerializedItemService itemService, Messages messages,
            NightclubService service, SerializedItem drink) {
        NightclubConfig.Product product = service.config().products().get(drink.itemType());
        String quality = service.qualityFromMetadata(drink.metadata());
        // The product name itself is configured in modules/nightclub.yml; only the
        // quality decoration is translated.
        String key = switch (quality) {
            case "MASTERWORK" -> "items.drink-masterwork";
            case "DILUTED" -> "items.drink-diluted";
            default -> "items.drink";
        };
        String name = product == null ? drink.itemType() : product.name();
        return itemService.toItemStack(drink, Material.POTION,
                messages.itemName(key, Placeholder.unparsed("name", name)));
    }

    public static ItemStack receiptStack(SerializedItemService itemService, Messages messages,
            SerializedItem receipt) {
        long cents = receipt.denomination() == null ? 0 : receipt.denomination();
        return itemService.toItemStack(receipt, Material.PAPER,
                messages.itemName("items.receipt",
                        Placeholder.unparsed("amount", Money.format(cents))));
    }
}
