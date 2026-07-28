package com.afterlife.rp.shared.missions;

import com.afterlife.rp.shared.regions.Poi;
import com.afterlife.rp.shared.regions.PoiService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Main-thread mission HUD and AFK watchdog: action-bar distance to the target
 * POI, compass pointing, and per-handler AFK warn/cancel (§9.6 anti-AFK).
 */
public final class MissionTracker {

    private record Movement(Location lastPosition, long lastMoveMs, boolean warned) {}

    private final Plugin plugin;
    private final MissionService missionService;
    private final PoiService poiService;
    private final Map<UUID, Movement> movementByPlayer = new ConcurrentHashMap<>();
    private BukkitTask task;

    public MissionTracker(Plugin plugin, MissionService missionService, PoiService poiService) {
        this.plugin = plugin;
        this.missionService = missionService;
        this.poiService = poiService;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            var missions = missionService.cachedActive(player.getUniqueId());
            if (missions.isEmpty()) {
                movementByPlayer.remove(player.getUniqueId());
                continue;
            }
            Mission mission = missions.getFirst();
            Poi target = mission.targetPoiId() == null
                    ? null
                    : poiService.byId(mission.targetPoiId()).orElse(null);
            if (target != null) {
                Location location = target.location();
                if (location != null && location.getWorld().equals(player.getWorld())) {
                    int distance = (int) location.distance(player.getLocation());
                    player.sendActionBar(Component.text("➤ " + target.name() + "  " + distance + "m",
                            NamedTextColor.AQUA));
                    player.setCompassTarget(location);
                }
            }
            enforceAfk(player, mission);
        }
    }

    private void enforceAfk(Player player, Mission mission) {
        MissionHandler handler = missionService.handlerFor(mission.type());
        int[] thresholds = handler == null ? null : handler.afkWarnCancelSeconds();
        if (thresholds == null) {
            movementByPlayer.remove(player.getUniqueId());
            return;
        }
        long now = System.currentTimeMillis();
        Movement movement = movementByPlayer.get(player.getUniqueId());
        Location current = player.getLocation();
        if (movement == null
                || movement.lastPosition().getBlockX() != current.getBlockX()
                || movement.lastPosition().getBlockZ() != current.getBlockZ()
                || !movement.lastPosition().getWorld().equals(current.getWorld())) {
            movementByPlayer.put(player.getUniqueId(), new Movement(current.clone(), now, false));
            return;
        }
        long idleSeconds = (now - movement.lastMoveMs()) / 1000;
        if (idleSeconds >= thresholds[1]) {
            movementByPlayer.remove(player.getUniqueId());
            player.sendActionBar(Component.text("✖ Consegna annullata: inattività",
                    NamedTextColor.RED));
            missionService.end(mission, "CANCELLED", "afk");
        } else if (idleSeconds >= thresholds[0] && !movement.warned()) {
            movementByPlayer.put(player.getUniqueId(),
                    new Movement(movement.lastPosition(), movement.lastMoveMs(), true));
            player.sendActionBar(Component.text("⚠ Muoviti o la missione verrà annullata!",
                    NamedTextColor.GOLD));
        }
    }
}
