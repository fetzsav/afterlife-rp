package com.afterlife.rp.module.nightclub;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.integration.WorldGuardAdapter;
import com.afterlife.rp.shared.items.ItemStatus;
import com.afterlife.rp.shared.items.SerializedItemService;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Drink consumption with quality-scaled effects and cooldowns, VIP weapon
 * blocking, and blacklist pushback at the club boundary (§9.11).
 */
public final class NightclubListener implements Listener {

    private final DatabaseManager databaseManager;
    private final NightclubService service;
    private final SerializedItemService itemService;
    private final WorldGuardAdapter worldGuard;
    private final Messages messages;
    private final Map<UUID, Long> drinkCooldown = new ConcurrentHashMap<>();
    private volatile Set<UUID> blacklisted = Set.of();

    public NightclubListener(Plugin plugin, DatabaseManager databaseManager,
            NightclubService service, SerializedItemService itemService,
            WorldGuardAdapter worldGuard, Messages messages) {
        this.databaseManager = databaseManager;
        this.service = service;
        this.itemService = itemService;
        this.worldGuard = worldGuard;
        this.messages = messages;
        // Blacklist cache refresh + boundary pushback (safe, non-damaging).
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshBlacklist, 100L, 200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::pushbackBlacklisted, 40L, 40L);
    }

    private void refreshBlacklist() {
        if (databaseManager.ready()) {
            service.blacklist().thenAccept(list -> blacklisted = Set.copyOf(list));
        }
    }

    private boolean inRegion(Player player, String region) {
        return worldGuard != null && worldGuard.available()
                && worldGuard.regionsAt(player.getLocation()).contains(region);
    }

    private void pushbackBlacklisted() {
        if (blacklisted.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (blacklisted.contains(player.getUniqueId())
                    && inRegion(player, service.config().clubRegion())) {
                // Safe pushback + warning at the boundary (§9.11).
                player.setVelocity(player.getLocation().getDirection().multiply(-1)
                        .setY(0.2).normalize().multiply(1.2));
                messages.send(player, "club.blacklist-pushback");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrink(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        var held = event.getItem();
        var data = itemService.readVerified(held).orElse(null);
        if (data == null || !service.config().products().containsKey(data.itemType())) {
            return;
        }
        event.setCancelled(true);
        if (!databaseManager.ready()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = drinkCooldown.get(player.getUniqueId());
        if (last != null && now - last < service.config().drinkCooldownSeconds() * 1000L) {
            messages.send(player, "club.drink-cooldown");
            return;
        }
        // One-winner redemption: a duplicated drink serial can only be drunk once.
        itemService.transition(data.serial(), ItemStatus.ISSUED, ItemStatus.REDEEMED)
                .thenCombine(databaseManager.db().supply(connection ->
                        new com.afterlife.rp.shared.items.SerializedItemRepository()
                                .find(connection, data.serial())), (won, record) ->
                        new Object[] {won, record})
                .thenAccept(result -> databaseManager.db().onMain(() -> {
                    boolean won = (Boolean) ((Object[]) result)[0];
                    if (!won || !player.isOnline()) {
                        if (player.isOnline()) {
                            messages.send(player, "club.drink-invalid");
                        }
                        return;
                    }
                    drinkCooldown.put(player.getUniqueId(), now);
                    var handItem = player.getInventory().getItemInMainHand();
                    var handData = itemService.readVerified(handItem);
                    if (handData.isPresent() && handData.get().serial().equals(data.serial())) {
                        handItem.setAmount(handItem.getAmount() - 1);
                    }
                    @SuppressWarnings("unchecked")
                    var record = (java.util.Optional<com.afterlife.rp.shared.items.SerializedItem>)
                            ((Object[]) result)[1];
                    String quality = record.map(r -> service.qualityFromMetadata(r.metadata()))
                            .orElse("NORMAL");
                    applyDrink(player, data.itemType(), quality);
                }));
    }

    private void applyDrink(Player player, String productId, String quality) {
        NightclubConfig.Product product = service.config().products().get(productId);
        if (product == null) {
            return;
        }
        if (product.cures()) {
            player.removePotionEffect(PotionEffectType.NAUSEA);
            player.removePotionEffect(PotionEffectType.SLOWNESS);
            messages.send(player, "club.drink-cured");
            return;
        }
        double multiplier = switch (quality) {
            case "MASTERWORK" -> service.config().masterworkMultiplier();
            case "DILUTED" -> service.config().dilutedMultiplier();
            default -> 1.0;
        };
        applyEffect(player, product.effect(), multiplier);
        applyEffect(player, product.sideEffect(), 1.0);
        messages.send(player, "club.drink-consumed");
    }

    private void applyEffect(Player player, String spec, double durationMultiplier) {
        if (spec == null || spec.isBlank()) {
            return;
        }
        String[] parts = spec.split(":");
        if (parts.length != 3) {
            return;
        }
        PotionEffectType type = org.bukkit.Registry.EFFECT.get(
                org.bukkit.NamespacedKey.minecraft(parts[0].toLowerCase(Locale.ROOT)));
        if (type == null) {
            return;
        }
        int amplifier = Integer.parseInt(parts[1]);
        int seconds = (int) Math.max(1, Integer.parseInt(parts[2]) * durationMultiplier);
        player.addPotionEffect(new PotionEffect(type, seconds * 20, amplifier, false, true));
    }

    /** Drawing configured weapons in the VIP region is blocked (§9.11). */
    @EventHandler(ignoreCancelled = true)
    public void onVipViolence(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        String weapon = attacker.getInventory().getItemInMainHand().getType().name();
        if (service.config().weaponMaterials().contains(weapon)
                && (inRegion(attacker, service.config().vipRegion())
                        || inRegion(attacker, service.config().clubRegion()))) {
            event.setCancelled(true);
            messages.send(attacker, "club.vip-weapons-blocked");
        }
    }
}
