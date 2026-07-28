package com.afterlife.rp.integration;

import java.util.Optional;
import java.util.UUID;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
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
        return available ? "permessi e gruppi" : "installa LuckPerms per gruppi e permessi";
    }

    public Optional<String> primaryGroup(UUID uuid) {
        if (!available) {
            return Optional.empty();
        }
        return Hook.primaryGroup(uuid);
    }

    /** Isolated so LuckPerms classes never load when the plugin is absent. */
    private static final class Hook {
        static Optional<String> primaryGroup(UUID uuid) {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getUserManager().getUser(uuid);
            return user == null ? Optional.empty() : Optional.of(user.getPrimaryGroup());
        }
    }
}
