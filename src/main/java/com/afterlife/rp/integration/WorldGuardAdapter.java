package com.afterlife.rp.integration;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
        return available ? "regions bindable to POIs" : "install WorldGuard for regions";
    }

    public boolean regionExists(World world, String regionId) {
        if (!available) {
            return false;
        }
        return Hook.regionExists(world, regionId);
    }

    /** Region ids covering a location (empty when WorldGuard is absent). */
    public Set<String> regionsAt(Location location) {
        if (!available) {
            return Set.of();
        }
        return Hook.regionsAt(location);
    }

    private static final class Hook {
        static boolean regionExists(World world, String regionId) {
            RegionManager manager = WorldGuard.getInstance().getPlatform()
                    .getRegionContainer().get(BukkitAdapter.adapt(world));
            return manager != null && manager.hasRegion(regionId);
        }

        static Set<String> regionsAt(Location location) {
            ApplicableRegionSet regions = WorldGuard.getInstance().getPlatform()
                    .getRegionContainer().createQuery()
                    .getApplicableRegions(BukkitAdapter.adapt(location));
            Set<String> ids = new HashSet<>();
            for (ProtectedRegion region : regions) {
                ids.add(region.getId());
            }
            return ids;
        }
    }
}
