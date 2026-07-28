package com.afterlife.rp.module.police;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.missions.JobSessionService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * K-9 scanning (§9.3): deployed, on-duty units periodically flag nearby players
 * carrying configured contraband within the radius. Items marked odor-proof
 * (sealed) defeat an ordinary scan. Detection is an indication to the handler,
 * not an automatic seizure.
 */
public final class K9Runtime {

    public static final String JOB_K9 = "K9";

    private final Plugin plugin;
    private final PoliceConfig config;
    private final JobSessionService jobSessions;
    private final SerializedItemService itemService;
    private final Messages messages;
    private final Set<UUID> deployed = ConcurrentHashMap.newKeySet();
    private BukkitTask task;

    public K9Runtime(Plugin plugin, PoliceConfig config, JobSessionService jobSessions,
            SerializedItemService itemService, Messages messages) {
        this.plugin = plugin;
        this.config = config;
        this.jobSessions = jobSessions;
        this.itemService = itemService;
        this.messages = messages;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::scan,
                20L * config.k9ScanIntervalSeconds(), 20L * config.k9ScanIntervalSeconds());
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        deployed.clear();
    }

    public boolean toggleDeploy(Player handler) {
        if (deployed.remove(handler.getUniqueId())) {
            return false;
        }
        deployed.add(handler.getUniqueId());
        return true;
    }

    public void undeploy(UUID handler) {
        deployed.remove(handler);
    }

    /** Pure detection: contraband item types carried, minus odor-proof ones. */
    public Set<String> detectContraband(Player target) {
        Set<String> found = new HashSet<>();
        for (var stack : target.getInventory().getContents()) {
            if (stack == null) {
                continue;
            }
            var data = itemService.readVerified(stack).orElse(null);
            if (data == null) {
                continue;
            }
            if (config.k9OdorproofTypes().contains(data.itemType())) {
                continue;
            }
            if (config.k9ContrabandTypes().contains(data.itemType())) {
                found.add(data.itemType());
            }
        }
        return found;
    }

    private void scan() {
        if (deployed.isEmpty()) {
            return;
        }
        double radiusSquared = config.k9RadiusBlocks() * config.k9RadiusBlocks();
        for (UUID handlerId : deployed) {
            Player handler = Bukkit.getPlayer(handlerId);
            if (handler == null || !jobSessions.isOnDuty(handlerId, JOB_K9)) {
                deployed.remove(handlerId);
                continue;
            }
            for (Player nearby : handler.getWorld().getPlayers()) {
                if (nearby.getUniqueId().equals(handlerId)
                        || nearby.getLocation().distanceSquared(handler.getLocation())
                                > radiusSquared) {
                    continue;
                }
                Set<String> contraband = detectContraband(nearby);
                if (!contraband.isEmpty()) {
                    messages.send(handler, "police.k9-indication",
                            Placeholder.unparsed("player", nearby.getName()),
                            Placeholder.unparsed("types", String.join(", ", contraband)));
                }
            }
        }
    }
}
