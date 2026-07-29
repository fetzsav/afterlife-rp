package com.afterlife.rp.setup;

import java.util.List;

/**
 * One thing an admin must register before a module can actually be played
 * (§12). Modules declare their requirements from their live config at boot, so
 * the checklist always matches what the module will really look for at runtime.
 *
 * @param id message-key suffix: {@code setup.requirement.<id>}
 * @param targets POI types, region name, permission nodes, or plugin name
 * @param minimum how many are needed (counted requirements only)
 * @param optional advisory: missing means "content is thinner", not "broken"
 * @param group suggested LuckPerms group for PERMISSION, otherwise null
 */
public record SetupRequirement(
        Kind kind,
        String id,
        List<String> targets,
        int minimum,
        boolean optional,
        String group) {

    public enum Kind { POI, REGION, PERMISSION, PROPERTY, PLUGIN }

    public static SetupRequirement poi(String id, List<String> types, int minimum) {
        return new SetupRequirement(Kind.POI, id, List.copyOf(types), minimum, false, null);
    }

    public static SetupRequirement optionalPoi(String id, List<String> types) {
        return new SetupRequirement(Kind.POI, id, List.copyOf(types), 1, true, null);
    }

    public static SetupRequirement region(String id, String regionName) {
        return new SetupRequirement(Kind.REGION, id, List.of(regionName), 1, false, null);
    }

    public static SetupRequirement permissions(String id, String group, String... nodes) {
        return new SetupRequirement(Kind.PERMISSION, id, List.of(nodes), 1, false, group);
    }

    public static SetupRequirement property(String id, int minimum) {
        return new SetupRequirement(Kind.PROPERTY, id, List.of(), minimum, false, null);
    }

    public static SetupRequirement plugin(String id, String pluginName, boolean optional) {
        return new SetupRequirement(Kind.PLUGIN, id, List.of(pluginName), 1, optional, null);
    }

    /** First target, used by single-target kinds (region, plugin). */
    public String target() {
        return targets.isEmpty() ? "" : targets.get(0);
    }
}
