package com.afterlife.rp.module.banking;

import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.PendingDeliveryService;
import com.afterlife.rp.shared.items.ItemStatus;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemService;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Ensures the personal account + balance cache on join and recovers pending
 * deliveries (restart recovery, rule 13). Each pending row is claimed with a
 * one-winner transition before any item is handed out.
 */
public final class BankingListener implements Listener {

    private final DatabaseManager databaseManager;
    private final AccountService accountService;
    private final PendingDeliveryService pendingDeliveryService;
    private final SerializedItemService itemService;
    private final Logger logger;

    public BankingListener(
            DatabaseManager databaseManager,
            AccountService accountService,
            PendingDeliveryService pendingDeliveryService,
            SerializedItemService itemService,
            Logger logger) {
        this.databaseManager = databaseManager;
        this.accountService = accountService;
        this.pendingDeliveryService = pendingDeliveryService;
        this.itemService = itemService;
        this.logger = logger;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!databaseManager.ready()) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        accountService.getOrCreatePersonal(uuid)
                .thenCompose(account -> pendingDeliveryService.pendingFor(uuid))
                .thenAccept(pendings -> {
                    for (PendingDeliveryService.Pending pending : pendings) {
                        deliver(player, pending);
                    }
                })
                .exceptionally(e -> {
                    logger.log(Level.SEVERE, "Banking join setup failed for " + player.getName(), e);
                    return null;
                });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        accountService.evict(event.getPlayer().getUniqueId());
    }

    private void deliver(Player player, PendingDeliveryService.Pending pending) {
        pendingDeliveryService.markDelivered(pending.id(), pending.version()).thenAccept(claimed -> {
            if (!claimed) {
                return;
            }
            // Fresh serials: the originals were voided when delivery failed.
            for (int i = 0; i < pending.quantity(); i++) {
                databaseManager.db().<SerializedItem>inTransaction(connection -> {
                    SerializedItem item = new SerializedItem(UUID.randomUUID(), pending.itemType(),
                            pending.playerUuid(), pending.denomination(), ItemStatus.ISSUED,
                            null, System.currentTimeMillis(), null);
                    new com.afterlife.rp.shared.items.SerializedItemRepository()
                            .insert(connection, item);
                    return item;
                }).thenAccept(item -> databaseManager.db().onMain(() -> {
                    if (!player.isOnline()) {
                        // Player left mid-recovery: queue again for the next join.
                        pendingDeliveryService.insertStandalone(pending.playerUuid(),
                                pending.itemType(), pending.denomination(), 1,
                                pending.reason(), pending.transactionId());
                        return;
                    }
                    var stack = BankingItems.toStack(itemService, item);
                    player.getInventory().addItem(stack).values().forEach(rest ->
                            player.getWorld().dropItemNaturally(player.getLocation(), rest));
                }));
            }
        });
    }
}
