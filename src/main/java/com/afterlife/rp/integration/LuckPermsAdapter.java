package com.afterlife.rp.integration;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;

/** LuckPerms-backed group lookup. API classes are only touched when the plugin is present. */
public final class LuckPermsAdapter implements Adapter {

    private final boolean available;

    public LuckPermsAdapter() {
        this.available = Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
    }

    @Override
    public String name() {
        return "LuckPerms";
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String detail() {
        return available ? "groups and permissions" : "install LuckPerms for groups and permissions";
    }

    public Optional<String> primaryGroup(UUID uuid) {
        if (!available) {
            return Optional.empty();
        }
        return Hook.primaryGroup(uuid);
    }

    /**
     * Every permission granted by any group, used by the setup checklist to tell
     * whether a job is reachable by players. Empty (not an empty set) when
     * LuckPerms is absent: unknown, not "nothing is granted".
     */
    public Optional<Set<String>> grantedGroupNodes() {
        if (!available) {
            return Optional.empty();
        }
        try {
            return Optional.of(Hook.groupNodes());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** Isolated so LuckPerms classes never load when the plugin is absent. */
    private static final class Hook {
        static Optional<String> primaryGroup(UUID uuid) {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getUserManager().getUser(uuid);
            return user == null ? Optional.empty() : Optional.of(user.getPrimaryGroup());
        }

        static Set<String> groupNodes() {
            Set<String> nodes = new HashSet<>();
            for (Group group : LuckPermsProvider.get().getGroupManager().getLoadedGroups()) {
                for (Node node : group.getNodes()) {
                    if (node.getValue()) {
                        nodes.add(node.getKey());
                    }
                }
            }
            return nodes;
        }
    }
}
