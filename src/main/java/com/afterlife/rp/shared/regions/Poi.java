package com.afterlife.rp.shared.regions;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** Admin-defined named point of interest (master plan §12). */
public record Poi(
        UUID id,
        String name,
        String type,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String regionId,
        String status,
        UUID createdBy) {

    /** Null when the world is not loaded. */
    public Location location() {
        World bukkitWorld = Bukkit.getWorld(world);
        return bukkitWorld == null ? null : new Location(bukkitWorld, x, y, z, yaw, pitch);
    }
}
