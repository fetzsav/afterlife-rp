package com.afterlife.rp.module.banking;

import com.afterlife.rp.config.Messages;
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
    private final Messages messages;
    private final Logger logger;

    public BankingListener(
            DatabaseManager databaseManager,
            AccountService accountService,
            PendingDeliveryService pendingDeliveryService,
            SerializedItemService itemService,
            Messages messages,
            Logger logger) {
        this.databaseManager = databaseManager;
        this.accountService = accountService;
        this.pendingDeliveryService = pendingDeliveryService;
        this.itemService = itemService;
        this.messages = messages;
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
            // Escrow returns: redeliver the EXISTING serialized item unchanged.
            if (pending.itemType().startsWith("escrow_item:")) {
                UUID serial = UUID.fromString(
                        pending.itemType().substring("escrow_item:".length()));
                databaseManager.db()
                        .supply(connection -> new com.afterlife.rp.shared.items
                                .SerializedItemRepository().find(connection, serial))
                        .thenAccept(record -> databaseManager.db().onMain(() -> {
                            if (record.isEmpty() || !player.isOnline()) {
                                return;
                            }
                            var stack = BankingItems.toStack(itemService, messages, record.get());
                            player.getInventory().addItem(stack).values().forEach(rest ->
                                    player.getWorld().dropItemNaturally(player.getLocation(), rest));
                        }));
                return;
            }
            // Escrow refunds/fees: fresh dirty notes of the stored denomination.
            String effectiveType = pending.itemType().startsWith("escrow_note:")
                    ? com.afterlife.rp.shared.items.ItemTypes.DIRTY_MONEY
                    : pending.itemType();
            // Fresh serials: the originals were voided when delivery failed.
            for (int i = 0; i < pending.quantity(); i++) {
                databaseManager.db().<SerializedItem>inTransaction(connection -> {
                    SerializedItem item = new SerializedItem(UUID.randomUUID(), effectiveType,
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
                    var stack = BankingItems.toStack(itemService, messages, item);
                    player.getInventory().addItem(stack).values().forEach(rest ->
                            player.getWorld().dropItemNaturally(player.getLocation(), rest));
                }));
            }
        });
    }
}
