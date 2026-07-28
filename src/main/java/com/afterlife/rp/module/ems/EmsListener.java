package com.afterlife.rp.module.ems;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Injury engine (§9.8): damage rolls configurable injuries; mild periodic
 * effects reflect untreated conditions without making play unbearable.
 */
public final class EmsListener implements Listener {

    private final DatabaseManager databaseManager;
    private final EmsService emsService;
    private final Messages messages;
    private final Map<UUID, Set<String>> injuredTypes = new ConcurrentHashMap<>();

    public EmsListener(Plugin plugin, DatabaseManager databaseManager, EmsService emsService,
            Messages messages) {
        this.databaseManager = databaseManager;
        this.emsService = emsService;
        this.messages = messages;
        if (emsService.config().applyEffects()) {
            Bukkit.getScheduler().runTaskTimer(plugin, this::applyEffects, 100L, 100L);
        }
    }

    /** Treatment code calls this to keep the effect cache honest. */
    public void refresh(UUID player) {
        if (!databaseManager.ready()) {
            return;
        }
        emsService.activeInjuries(player).thenAccept(injuries -> {
            Set<String> types = ConcurrentHashMap.newKeySet();
            injuries.forEach(injury -> types.add(injury.type()));
            injuredTypes.put(player, types);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !databaseManager.ready()) {
            return;
        }
        if (event.getFinalDamage() < emsService.config().minimumDamage()) {
            return;
        }
        boolean lowHealth = player.getHealth() - event.getFinalDamage()
                <= emsService.config().unconsciousHealthThreshold();
        emsService.maybeInflict(player.getUniqueId(), event.getCause().name(), lowHealth)
                .thenAccept(inflicted -> inflicted.ifPresent(type ->
                        databaseManager.db().onMain(() -> {
                            refresh(player.getUniqueId());
                            if (player.isOnline()) {
                                messages.send(player, "ems.injured",
                                        Placeholder.unparsed("type", type));
                            }
                        })));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        injuredTypes.remove(event.getPlayer().getUniqueId());
    }

    private void applyEffects(){
        for (Player player : Bukkit.getOnlinePlayers()) {
            Set<String> types = injuredTypes.getOrDefault(player.getUniqueId(), Set.of());
            if (types.isEmpty()) {
                continue;
            }
            if (types.contains("FRACTURE") || types.contains("LEG_PAIN")) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 140, 0,
                        true, false));
            }
            if (types.contains("BLEEDING")) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 140, 0,
                        true, false));
            }
            if (types.contains("UNCONSCIOUS")) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 140, 0,
                        true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 140, 3,
                        true, false));
            }
        }
    }
}
