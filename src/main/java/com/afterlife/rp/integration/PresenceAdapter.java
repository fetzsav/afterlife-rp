package com.afterlife.rp.integration;

import org.bukkit.Bukkit;

/** Presence-only adapter for plugins whose APIs are wired in later milestones. */
public final class PresenceAdapter implements Adapter {

    private final String pluginName;
    private final String activeDetail;
    private final String missingDetail;
    private final boolean available;

    /** Extra names cover drop-in successors (e.g. VaultUnlocked for Vault). */
    public PresenceAdapter(
            String pluginName, String activeDetail, String missingDetail, String... alternateNames) {
        this.pluginName = pluginName;
        this.activeDetail = activeDetail;
        this.missingDetail = missingDetail;
        boolean found = Bukkit.getPluginManager().isPluginEnabled(pluginName);
        for (String alternate : alternateNames) {
            found = found || Bukkit.getPluginManager().isPluginEnabled(alternate);
        }
        this.available = found;
    }

    @Override
    public String name() {
        return pluginName;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String detail() {
        return available ? activeDetail : missingDetail;
    }
}
