package com.afterlife.rp.module.nightclub;

import com.afterlife.rp.shared.economy.Money;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Presentation for drinks and receipts; authority is PDC + DB (rule 6). */
public final class NightclubItems {

    private NightclubItems() {}

    public static ItemStack drinkStack(SerializedItemService itemService, NightclubService service,
            SerializedItem drink) {
        NightclubConfig.Product product = service.config().products().get(drink.itemType());
        String quality = service.qualityFromMetadata(drink.metadata());
        String suffix = switch (quality) {
            case "MASTERWORK" -> " ★";
            case "DILUTED" -> " (annacquato)";
            default -> "";
        };
        NamedTextColor color = switch (quality) {
            case "MASTERWORK" -> NamedTextColor.GOLD;
            case "DILUTED" -> NamedTextColor.GRAY;
            default -> NamedTextColor.AQUA;
        };
        return itemService.toItemStack(drink, Material.POTION,
                Component.text((product == null ? drink.itemType() : product.name()) + suffix, color)
                        .decoration(TextDecoration.ITALIC, false));
    }

    public static ItemStack receiptStack(SerializedItemService itemService, SerializedItem receipt) {
        long cents = receipt.denomination() == null ? 0 : receipt.denomination();
        return itemService.toItemStack(receipt, Material.PAPER,
                Component.text("Scontrino — " + Money.format(cents), NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC, false));
    }
}
