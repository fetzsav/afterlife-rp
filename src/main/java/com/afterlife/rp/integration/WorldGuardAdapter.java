package com.afterlife.rp.integration;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import org.bukkit.Bukkit;
import org.bukkit.World;

/** WorldGuard region checks for POI binding. Classes load only when present. */
public final class WorldGuardAdapter implements Adapter {

    private final boolean available;

    public WorldGuardAdapter() {
        this.available = Bukkit.getPluginManager().isPluginEnabled("WorldGuard");
    }

    @Override
    public String name() {
        return "WorldGuard";
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String detail() {
        return available ? "regioni collegabili ai POI" : "installa WorldGuard per le regioni";
    }

    public boolean regionExists(World world, String regionId) {
        if (!available) {
            return false;
        }
        return Hook.regionExists(world, regionId);
    }

    private static final class Hook {
        static boolean regionExists(World world, String regionId) {
            RegionManager manager = WorldGuard.getInstance().getPlatform()
                    .getRegionContainer().get(BukkitAdapter.adapt(world));
            return manager != null && manager.hasRegion(regionId);
        }
    }
}
