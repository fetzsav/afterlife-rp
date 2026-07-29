package com.afterlife.rp.setup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What each module did at boot and what it still needs from the admin.
 * Populated once during onEnable; read by {@code /afterlife setup status} so
 * the in-game checklist reflects the running server, never a stale document.
 */
public final class SetupRegistry {

    /** Why a module is or is not running. */
    public enum State {
        /** Loaded, wired, and taking commands. */
        ACTIVE,
        /** Turned off with {@code enabled: false} in its module config. */
        DISABLED,
        /** Its config failed validation; the module is off until it is fixed. */
        CONFIG_ERROR,
        /** Another module it depends on is not running. */
        BLOCKED
    }

    /** A module entry: its state, a one-line reason, and its requirements. */
    public record Module(String key, State state, String detail, List<SetupRequirement> requirements) {

        public Module {
            requirements = List.copyOf(requirements);
        }

        public boolean active() {
            return state == State.ACTIVE;
        }
    }

    private final Map<String, Module> modules = new LinkedHashMap<>();

    public void active(String key, List<SetupRequirement> requirements) {
        modules.put(key, new Module(key, State.ACTIVE, "", requirements));
    }

    public void disabled(String key) {
        modules.put(key, new Module(key, State.DISABLED, "modules/" + key + ".yml", List.of()));
    }

    public void configError(String key, String detail) {
        modules.put(key, new Module(key, State.CONFIG_ERROR, detail, List.of()));
    }

    public void blocked(String key, String requiredModules) {
        modules.put(key, new Module(key, State.BLOCKED, requiredModules, List.of()));
    }

    public List<Module> modules() {
        return List.copyOf(modules.values());
    }

    /**
     * Every POI type an active module can ask for. Unioned with the configured
     * allow-list so a module can never require a type the setup command rejects.
     */
    public Set<String> requiredPoiTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (Module module : modules.values()) {
            if (!module.active()) {
                continue;
            }
            for (SetupRequirement requirement : module.requirements()) {
                if (requirement.kind() == SetupRequirement.Kind.POI) {
                    types.addAll(requirement.targets());
                }
            }
        }
        return types;
    }

    /** Convenience builder so module wiring stays a single readable statement. */
    public static List<SetupRequirement> requirements(SetupRequirement... entries) {
        List<SetupRequirement> list = new ArrayList<>(entries.length);
        for (SetupRequirement entry : entries) {
            if (entry != null) {
                list.add(entry);
            }
        }
        return list;
    }
}
