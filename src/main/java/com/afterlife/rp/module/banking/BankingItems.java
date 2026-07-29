package com.afterlife.rp.module.banking;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.shared.economy.Money;
import com.afterlife.rp.shared.items.ItemTypes;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Presentation for banking instruments. Names/materials are cosmetic only —
 * authority lives in PDC + database (rule 6). Names come from items.* in the
 * default language: a note in a chest reads the same for everyone.
 */
public final class BankingItems {

    private BankingItems() {}

    public static ItemStack toStack(SerializedItemService itemService, Messages messages,
            SerializedItem item) {
        return switch (item.itemType()) {
            case ItemTypes.BANKNOTE -> itemService.toItemStack(item, Material.PAPER,
                    messages.itemName("items.banknote", amount(item)));
            case ItemTypes.DIRTY_MONEY -> itemService.toItemStack(item, Material.PAPER,
                    messages.itemName("items.dirty-money", amount(item)));
            case ItemTypes.CREDIT_CARD -> itemService.toItemStack(item, Material.NAME_TAG,
                    messages.itemName("items.credit-card"));
            case ItemTypes.CHECK -> itemService.toItemStack(item, Material.PAPER,
                    messages.itemName("items.check", amount(item)));
            default -> itemService.toItemStack(item, Material.PAPER,
                    messages.itemName("items.unknown",
                            Placeholder.unparsed("type", item.itemType())));
        };
    }

    private static TagResolver amount(SerializedItem item) {
        long denomination = item.denomination() == null ? 0 : item.denomination();
        return Placeholder.unparsed("amount", Money.format(denomination));
    }
}
