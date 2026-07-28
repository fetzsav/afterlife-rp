package com.afterlife.rp.module.crime;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.items.SerializedItemService;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** Drug consumption trigger and trip cleanup on quit (§9.4). */
public final class CrimeListener implements Listener {

    private final DatabaseManager databaseManager;
    private final CrimeService service;
    private final CrimeRuntime runtime;
    private final SerializedItemService itemService;
    private final Messages messages;
    private final java.util.Map<UUID, Long> cooldown = new ConcurrentHashMap<>();

    public CrimeListener(Plugin plugin, DatabaseManager databaseManager, CrimeService service,
            CrimeRuntime runtime, SerializedItemService itemService, Messages messages) {
        this.databaseManager = databaseManager;
        this.service = service;
        this.runtime = runtime;
        this.itemService = itemService;
        this.messages = messages;
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        var data = itemService.readVerified(event.getItem())
                .filter(d -> CrimeService.ITEM_DRUG.equals(d.itemType()))
                .orElse(null);
        if (data == null || !databaseManager.ready()) {
            return;
        }
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = cooldown.get(player.getUniqueId());
        if (last != null && now - last < service.config().consumeCooldownSeconds() * 1000L) {
            messages.send(player, "crime.consume-cooldown");
            return;
        }
        // One-winner consumption: a copied dose can only be used once.
        service.consumeDose(data.serial()).thenAccept(consumed ->
                databaseManager.db().onMain(() -> {
                    if (!consumed || !player.isOnline()) {
                        return;
                    }
                    cooldown.put(player.getUniqueId(), now);
                    var hand = player.getInventory().getItemInMainHand();
                    var handData = itemService.readVerified(hand);
                    if (handData.isPresent() && handData.get().serial().equals(data.serial())) {
                        hand.setAmount(hand.getAmount() - 1);
                    }
                    runtime.runTrip(player);
                }));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        runtime.endTrip(event.getPlayer().getUniqueId());
    }
}
