package com.afterlife.rp.module.crime;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.module.police.PoliceService;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.missions.JobSessionService;
import com.afterlife.rp.shared.missions.Mission;
import com.afterlife.rp.shared.missions.MissionService;
import com.afterlife.rp.shared.regions.Poi;
import com.afterlife.rp.shared.regions.PoiService;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Bukkit-side crime runtime (§9.4): consumer-only drug-trip hallucinations via
 * Paper hideEntity (ADR 0004), periodic gang street demand, and the ATM-hack
 * channel. Hallucination entities never harm and are always cleaned up.
 */
public final class CrimeRuntime {

    private final Plugin plugin;
    private final DatabaseManager databaseManager;
    private final CrimeConfig config;
    private final CrimeService crimeService;
    private final PoliceService policeService;
    private final MissionService missionService;
    private final PoiService poiService;
    private final JobSessionService jobSessions;
    private final SerializedItemService itemService;
    private final Messages messages;
    private final SecureRandom random = new SecureRandom();

    private final java.util.Map<UUID, List<Entity>> tripEntities = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, BukkitTask> hackTasks = new ConcurrentHashMap<>();
    private BukkitTask demandTask;

    public CrimeRuntime(Plugin plugin, DatabaseManager databaseManager, CrimeConfig config,
            CrimeService crimeService, PoliceService policeService, MissionService missionService,
            PoiService poiService, JobSessionService jobSessions, SerializedItemService itemService,
            Messages messages) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.config = config;
        this.crimeService = crimeService;
        this.policeService = policeService;
        this.missionService = missionService;
        this.poiService = poiService;
        this.jobSessions = jobSessions;
        this.itemService = itemService;
        this.messages = messages;
    }

    public void start() {
        demandTask = Bukkit.getScheduler().runTaskTimer(plugin, this::broadcastDemand,
                20L * config.demandIntervalSeconds(), 20L * config.demandIntervalSeconds());
    }

    public void stop() {
        if (demandTask != null) {
            demandTask.cancel();
        }
        hackTasks.values().forEach(BukkitTask::cancel);
        hackTasks.clear();
        tripEntities.keySet().forEach(this::endTrip);
    }

    // --- drug trips (consumer-only) ---

    /** 70/30 good/bad trip: real entities visible only to the consumer (ADR 0004). */
    public void runTrip(Player consumer) {
        boolean good = random.nextDouble() < config.goodTripChance();
        List<String> pool = good ? config.goodMobs() : config.badMobs();
        List<Entity> spawned = new ArrayList<>();
        int count = 1 + random.nextInt(config.hallucinationCap());
        for (int i = 0; i < count && i < config.hallucinationCap(); i++) {
            EntityType type;
            try {
                type = EntityType.valueOf(pool.get(random.nextInt(pool.size())));
            } catch (IllegalArgumentException e) {
                continue;
            }
            Location at = consumer.getLocation().add(random.nextDouble() * 6 - 3, 0,
                    random.nextDouble() * 6 - 3);
            Entity entity = consumer.getWorld().spawnEntity(at, type);
            entity.setPersistent(false);
            if (entity instanceof LivingEntity living) {
                living.setInvulnerable(true);
                living.setRemoveWhenFarAway(true);
                living.setSilent(true);
            }
            if (entity instanceof Mob mob) {
                mob.setAware(false); // no targeting, no real threat (§9.4 safety)
            }
            // Hide from everyone, then reveal only to the consumer.
            for (Player other : Bukkit.getOnlinePlayers()) {
                other.hideEntity(plugin, entity);
            }
            consumer.showEntity(plugin, entity);
            spawned.add(entity);
        }
        tripEntities.put(consumer.getUniqueId(), spawned);
        messages.send(consumer, good ? "crime.trip-good" : "crime.trip-bad");
        Bukkit.getScheduler().runTaskLater(plugin, () -> endTrip(consumer.getUniqueId()),
                20L * config.tripSeconds());
    }

    public void endTrip(UUID consumer) {
        List<Entity> spawned = tripEntities.remove(consumer);
        if (spawned != null) {
            spawned.forEach(Entity::remove);
        }
    }

    // --- gang street demand ---

    private void broadcastDemand() {
        if (!databaseManager.ready()) {
            return;
        }
        List<Poi> zones = poiService.byTypeAndStatus(config.saleZonePoiTypes(), "ACTIVE");
        if (zones.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!jobSessions.isOnDuty(player.getUniqueId(), CrimeService.JOB_GANG)) {
                continue;
            }
            for (Poi zone : zones) {
                var location = zone.location();
                if (location != null && zone.world().equals(player.getWorld().getName())
                        && location.distanceSquared(player.getLocation()) <= 400) {
                    messages.send(player, "crime.demand",
                            Placeholder.unparsed("zone", zone.name()));
                    break;
                }
            }
        }
    }

    public boolean inSaleZone(Player player) {
        for (Poi zone : poiService.byTypeAndStatus(config.saleZonePoiTypes(), "ACTIVE")) {
            var location = zone.location();
            if (location != null && zone.world().equals(player.getWorld().getName())
                    && location.distanceSquared(player.getLocation()) <= 400) {
                return true;
            }
        }
        return false;
    }

    public Poi nearestSaleZone(Player player) {
        for (Poi zone : poiService.byTypeAndStatus(config.saleZonePoiTypes(), "ACTIVE")) {
            var location = zone.location();
            if (location != null && zone.world().equals(player.getWorld().getName())
                    && location.distanceSquared(player.getLocation()) <= 400) {
                return zone;
            }
        }
        return null;
    }

    // --- ATM hack channel ---

    public void runHack(Player hacker, Mission mission, Poi atm) {
        int duration = config.hackChannelSeconds();
        double[] elapsed = {0};
        Location atmLocation = atm.location();
        if (config.hackAlert()) {
            policeService.raiseAlert("ATM_HACK", district(atm), atm.world(), "atm_hack");
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            BukkitTask self = hackTasks.get(mission.id());
            boolean missionGone = missionService.cachedActiveOfType(hacker.getUniqueId(),
                    CrimeService.MISSION_ATM_HACK).isEmpty();
            if (atmLocation == null || !hacker.isOnline() || missionGone
                    || !hacker.getWorld().getName().equals(atm.world())
                    || hacker.getLocation().distanceSquared(atmLocation) > 9) {
                hackTasks.remove(mission.id());
                if (self != null) {
                    self.cancel();
                }
                missionService.end(mission, "CANCELLED", "hack-interrupted");
                if (hacker.isOnline()) {
                    messages.send(hacker, "crime.hack-interrupted");
                }
                return;
            }
            hacker.getWorld().spawnParticle(org.bukkit.Particle.ELECTRIC_SPARK,
                    atmLocation.clone().add(0, 1, 0), 8, 0.3, 0.5, 0.3);
            if (++elapsed[0] >= duration) {
                hackTasks.remove(mission.id());
                if (self != null) {
                    self.cancel();
                }
                crimeService.finishHack(mission).thenAccept(result ->
                        databaseManager.db().onMain(() -> {
                            if (!result.rewarded() || !hacker.isOnline()) {
                                return;
                            }
                            for (var note : result.notes()) {
                                hacker.getInventory().addItem(com.afterlife.rp.module.banking
                                                .BankingItems.toStack(itemService, note)).values()
                                        .forEach(rest -> hacker.getWorld()
                                                .dropItemNaturally(hacker.getLocation(), rest));
                            }
                            messages.send(hacker, "crime.hack-done",
                                    Placeholder.unparsed("amount",
                                            com.afterlife.rp.shared.economy.Money.format(
                                                    result.dirtyCents())));
                        }));
            }
        }, 20L, 20L);
        hackTasks.put(mission.id(), task);
    }

    private String district(Poi poi) {
        if (poi.regionId() != null && !poi.regionId().isBlank()) {
            return poi.regionId();
        }
        return "zona " + (((int) poi.x()) / 100 * 100) + "," + (((int) poi.z()) / 100 * 100);
    }
}
