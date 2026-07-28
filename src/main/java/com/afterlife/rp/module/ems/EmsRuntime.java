package com.afterlife.rp.module.ems;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.missions.JobSessionService;
import com.afterlife.rp.shared.missions.Mission;
import com.afterlife.rp.shared.missions.MissionService;
import com.afterlife.rp.shared.regions.Poi;
import com.afterlife.rp.shared.regions.PoiService;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Bukkit-side EMS runtime: toxic-extraction channels (stand still, visible
 * green smoke, safe cancel) and Citizens NPC emergencies with cleanup on
 * completion, expiry, and restart (§9.8).
 */
public final class EmsRuntime {

    public static final String NPC_PREFIX = "[EMS] ";

    private record PendingEmergency(UUID poiId, int npcId, long expiresAtMs) {}

    private final Plugin plugin;
    private final DatabaseManager databaseManager;
    private final EmsService emsService;
    private final MissionService missionService;
    private final PoiService poiService;
    private final JobSessionService jobSessions;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final SerializedItemService itemService;
    private final Messages messages;
    private final SecureRandom random = new SecureRandom();

    private final Map<UUID, BukkitTask> extractionTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> emergencyNpcByMission = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> emergencyProgress = new ConcurrentHashMap<>();
    private volatile PendingEmergency pendingEmergency;

    public EmsRuntime(Plugin plugin, DatabaseManager databaseManager, EmsService emsService,
            MissionService missionService, PoiService poiService, JobSessionService jobSessions,
            AccountService accountService, LedgerService ledgerService,
            SerializedItemService itemService, Messages messages) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.emsService = emsService;
        this.missionService = missionService;
        this.poiService = poiService;
        this.jobSessions = jobSessions;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.itemService = itemService;
        this.messages = messages;
    }

    private boolean citizensAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("Citizens");
    }

    public void start() {
        if (citizensAvailable()) {
            CitizensHook.destroyStaleNpcs();
        }
        scheduleNextEmergency();
    }

    public void stop() {
        extractionTasks.values().forEach(BukkitTask::cancel);
        extractionTasks.clear();
        if (citizensAvailable()) {
            emergencyNpcByMission.values().forEach(CitizensHook::destroy);
            if (pendingEmergency != null) {
                CitizensHook.destroy(pendingEmergency.npcId());
            }
        }
    }

    // --- toxic extraction channel ---

    /** Runs the 45-60s channel: stand within radius or the mission cancels safely. */
    public void runExtraction(Player player, Mission mission, Poi barrel) {
        int duration = (int) mission.dataLong("duration", emsService.config().toxicDurationSecondsMin());
        double radiusSquared = emsService.config().toxicRadiusBlocks()
                * emsService.config().toxicRadiusBlocks();
        AtomicInteger elapsed = new AtomicInteger();
        Location barrelLocation = barrel.location();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            BukkitTask self = extractionTasks.get(mission.id());
            if (barrelLocation == null || !player.isOnline()
                    || missionService.cachedActiveOfType(player.getUniqueId(),
                            EmsService.MISSION_TOXIC).isEmpty()
                    || !player.getWorld().getName().equals(barrel.world())
                    || player.getLocation().distanceSquared(barrelLocation) > radiusSquared) {
                // Movement, disconnect, or incapacitation cancels safely (§9.8).
                cancelExtraction(mission, self);
                if (player.isOnline()) {
                    messages.send(player, "ems.extraction-cancelled");
                }
                return;
            }
            // Visible green smoke reveals the activity to bystanders.
            player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                    barrelLocation.clone().add(0, 1, 0), 12, 0.4, 0.6, 0.4);
            if (elapsed.incrementAndGet() >= duration) {
                extractionTasks.remove(mission.id());
                if (self != null) {
                    self.cancel();
                }
                emsService.finishExtraction(mission).thenAccept(chemical ->
                        databaseManager.db().onMain(() -> {
                            if (chemical.isEmpty() || !player.isOnline()) {
                                return;
                            }
                            var stack = EmsItems.toStack(itemService, chemical.get());
                            player.getInventory().addItem(stack).values().forEach(rest ->
                                    player.getWorld().dropItemNaturally(player.getLocation(), rest));
                            messages.send(player, "ems.extraction-done");
                        }));
            }
        }, 20L, 20L);
        extractionTasks.put(mission.id(), task);
    }

    private void cancelExtraction(Mission mission, BukkitTask task) {
        extractionTasks.remove(mission.id());
        if (task != null) {
            task.cancel();
        }
        missionService.end(mission, "CANCELLED", "extraction-interrupted");
    }

    // --- NPC emergencies ---

    private void scheduleNextEmergency() {
        int minutes = emsService.config().emergencyIntervalMinutesMin()
                + random.nextInt(1 + emsService.config().emergencyIntervalMinutesMax()
                        - emsService.config().emergencyIntervalMinutesMin());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            spawnEmergencyIfPossible();
            scheduleNextEmergency();
        }, 20L * 60 * minutes);
    }

    private void spawnEmergencyIfPossible() {
        if (!databaseManager.ready() || !citizensAvailable() || pendingEmergency != null) {
            return;
        }
        long medicsOnDuty = Bukkit.getOnlinePlayers().stream()
                .filter(p -> jobSessions.isOnDuty(p.getUniqueId(), EmsService.JOB))
                .count();
        if (medicsOnDuty < emsService.config().emergencyMinMedics()) {
            return;
        }
        List<Poi> points = poiService.byTypeAndStatus(emsService.config().emergencyPoiTypes(),
                "ACTIVE");
        if (points.isEmpty()) {
            return;
        }
        Poi poi = points.get(random.nextInt(points.size()));
        Location location = poi.location();
        if (location == null) {
            return;
        }
        int npcId = CitizensHook.spawnInjured(location);
        pendingEmergency = new PendingEmergency(poi.id(), npcId,
                System.currentTimeMillis()
                        + emsService.config().emergencyDeadlineMinutes() * 60_000L);
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> jobSessions.isOnDuty(p.getUniqueId(), EmsService.JOB))
                .forEach(p -> messages.send(p, "ems.emergency-broadcast",
                        Placeholder.unparsed("name", poi.name())));
        // Unclaimed emergencies clean themselves up at the deadline.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingEmergency pending = pendingEmergency;
            if (pending != null && pending.npcId() == npcId) {
                pendingEmergency = null;
                CitizensHook.destroy(npcId);
            }
        }, 20L * 60 * emsService.config().emergencyDeadlineMinutes());
    }

    /** A medic claims the pending emergency; the mission carries the NPC id. */
    public void claimEmergency(Player medic) {
        PendingEmergency pending = pendingEmergency;
        if (pending == null || System.currentTimeMillis() > pending.expiresAtMs()) {
            messages.send(medic, "ems.emergency-none");
            return;
        }
        pendingEmergency = null;
        missionService.claim(EmsService.MISSION_EMERGENCY, medic.getUniqueId(), pending.poiId(),
                        null, emsService.config().emergencyDeadlineMinutes() * 60,
                        emsService.config().emergencyRewardCents(),
                        "{\"current_target\":\"" + pending.poiId() + "\"}")
                .thenAccept(claimed -> databaseManager.db().onMain(() -> {
                    if (claimed.isEmpty()) {
                        pendingEmergency = pending;
                        messages.send(medic, "ems.emergency-busy");
                        return;
                    }
                    emergencyNpcByMission.put(claimed.get().id(), pending.npcId());
                    emergencyProgress.put(claimed.get().id(), new AtomicInteger());
                    messages.send(medic, "ems.emergency-claimed");
                }));
    }

    /** Each valid /ems cura_npc advances one step; the last one pays and cleans up. */
    public void treatEmergencyNpc(Player medic) {
        Mission mission = missionService
                .cachedActiveOfType(medic.getUniqueId(), EmsService.MISSION_EMERGENCY).orElse(null);
        if (mission == null) {
            messages.send(medic, "ems.emergency-none");
            return;
        }
        Poi poi = poiService.byId(mission.targetPoiId()).orElse(null);
        if (poi == null || poi.location() == null
                || !poi.world().equals(medic.getWorld().getName())
                || poi.location().distanceSquared(medic.getLocation()) > 16) {
            messages.send(medic, "ems.too-far");
            return;
        }
        AtomicInteger progress = emergencyProgress.computeIfAbsent(mission.id(),
                k -> new AtomicInteger());
        int step = progress.incrementAndGet();
        int total = emsService.config().emergencyTreatmentSteps();
        if (step < total) {
            messages.send(medic, "ems.emergency-step",
                    Placeholder.unparsed("step", String.valueOf(step)),
                    Placeholder.unparsed("total", String.valueOf(total)));
            return;
        }
        missionService.complete(mission.id()).thenAccept(won -> {
            if (!won) {
                return;
            }
            cleanupEmergency(mission);
            var account = accountService.cachedPersonal(medic.getUniqueId()).orElse(null);
            if (account == null) {
                return;
            }
            UUID government = accountService.system(AccountService.SYSTEM_GOVERNMENT).id();
            ledgerService.execute("emerg-" + mission.id(), "EMS_EMERGENCY_PAY",
                            medic.getUniqueId(), null,
                            List.of(new LedgerService.Line(government, -mission.rewardSnapshot()),
                                    new LedgerService.Line(account.id(), mission.rewardSnapshot())),
                            false)
                    .thenAccept(result -> databaseManager.db().onMain(() -> {
                        if (medic.isOnline()) {
                            messages.send(medic, "ems.emergency-done");
                        }
                    }));
        });
    }

    /** Framework hook target: NPCs never outlive their mission (§9.8 cleanup). */
    public void cleanupEmergency(Mission mission) {
        emergencyProgress.remove(mission.id());
        Integer npcId = emergencyNpcByMission.remove(mission.id());
        if (npcId != null && citizensAvailable()) {
            Bukkit.getScheduler().runTask(plugin, () -> CitizensHook.destroy(npcId));
        }
    }

    /** Citizens classes only load when the plugin is present. */
    private static final class CitizensHook {
        static int spawnInjured(Location location) {
            NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER,
                    NPC_PREFIX + "Ferito");
            npc.spawn(location);
            return npc.getId();
        }

        static void destroy(int npcId) {
            NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
            if (npc != null) {
                npc.destroy();
            }
        }

        static void destroyStaleNpcs() {
            List<NPC> stale = new java.util.ArrayList<>();
            CitizensAPI.getNPCRegistry().forEach(npc -> {
                if (npc.getName().startsWith(NPC_PREFIX)) {
                    stale.add(npc);
                }
            });
            stale.forEach(NPC::destroy);
        }
    }
}
