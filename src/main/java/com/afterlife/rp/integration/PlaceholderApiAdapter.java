package com.afterlife.rp.integration;

import com.afterlife.rp.shared.identity.IdentityService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/** Registers %afterlife_public_id% and %afterlife_nickname% when PAPI is present. */
public final class PlaceholderApiAdapter implements Adapter {

    private final boolean available;
    private boolean registered;

    public PlaceholderApiAdapter(Plugin plugin, IdentityService identityService) {
        this.available = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        if (available) {
            registered = Hook.register(plugin, identityService);
        }
    }

    @Override
    public String name() {
        return "PlaceholderAPI";
    }

    @Override
    public boolean available() {
        return available && registered;
    }

    @Override
    public String detail() {
        if (!available) {
            return "install PlaceholderAPI for placeholders";
        }
        return registered ? "%afterlife_public_id%, %afterlife_nickname%" : "registration failed";
    }

    private static final class Hook {
        static boolean register(Plugin plugin, IdentityService identityService) {
            return new PlaceholderExpansion() {
                @Override
                public @NotNull String getIdentifier() {
                    return "afterlife";
                }

                @Override
                public @NotNull String getAuthor() {
                    return String.join(", ", plugin.getPluginMeta().getAuthors());
                }

                @Override
                public @NotNull String getVersion() {
                    return plugin.getPluginMeta().getVersion();
                }

                @Override
                public boolean persist() {
                    return true;
                }

                @Override
                public String onRequest(OfflinePlayer player, @NotNull String params) {
                    if (player == null) {
                        return null;
                    }
                    return identityService.cached(player.getUniqueId()).map(identity ->
                            switch (params) {
                                case "public_id" -> String.valueOf(identity.publicId());
                                case "nickname" -> identity.nickname() == null ? "" : identity.nickname();
                                default -> null;
                            }).orElse(null);
                }
            }.register();
        }
    }
}
