package com.afterlife.rp.module.delivery;

import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.missions.MissionService;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Package protections (§9.6): order packages cannot be dropped, stashed in
 * containers, or kept through death — the mission ends and the serial voids.
 */
public final class DeliveryListener implements Listener {

    private static final Set<String> PACKAGE_TYPES =
            Set.of(DeliveryService.ITEM_FOOD_PACKAGE, DeliveryService.ITEM_CONTRABAND_PACKAGE);

    private final DatabaseManager databaseManager;
    private final MissionService missionService;
    private final SerializedItemService itemService;

    public DeliveryListener(DatabaseManager databaseManager, MissionService missionService,
            SerializedItemService itemService) {
        this.databaseManager = databaseManager;
        this.missionService = missionService;
        this.itemService = itemService;
    }

    private boolean isPackage(ItemStack stack) {
        return stack != null && itemService.readVerified(stack)
                .filter(data -> PACKAGE_TYPES.contains(data.itemType()))
                .isPresent();
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isPackage(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // Block any attempt to move a package into a non-player inventory.
        if (event.getInventory().getType() == InventoryType.CRAFTING) {
            return;
        }
        if (isPackage(event.getCurrentItem()) || isPackage(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        event.getDrops().removeIf(this::isPackage);
        if (databaseManager.ready()) {
            // Death cancels active deliveries; the handler voids package serials.
            missionService.cancelAllFor(player.getUniqueId(), "death");
        }
    }
}
