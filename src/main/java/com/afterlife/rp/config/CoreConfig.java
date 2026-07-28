package com.afterlife.rp.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Validated core configuration (non-database sections of config.yml). */
public record CoreConfig(
        int nicknameMinLength,
        int nicknameMaxLength,
        int guiSessionTimeoutSeconds,
        Set<String> poiTypes,
        Map<String, String> customItemCatalog) {

    public static CoreConfig from(FileConfiguration config) {
        int min = config.getInt("identity.nickname.min-length", 1);
        int max = config.getInt("identity.nickname.max-length", 10);
        int guiTimeout = config.getInt("gui.session-timeout-seconds", 120);
        Set<String> poiTypes = new TreeSet<>();
        for (String type : config.getStringList("poi.types")) {
            poiTypes.add(type.toUpperCase(Locale.ROOT));
        }
        Map<String, String> catalog = new HashMap<>();
        ConfigurationSection customItems = config.getConfigurationSection("custom-items");
        if (customItems != null) {
            for (String type : customItems.getKeys(false)) {
                String id = customItems.getString(type);
                if (id != null && !id.isBlank()) {
                    catalog.put(type.toLowerCase(Locale.ROOT), id);
                }
            }
        }

        List<String> errors = new ArrayList<>();
        if (min < 1) {
            errors.add("identity.nickname.min-length must be at least 1");
        }
        if (max < min || max > 32) {
            errors.add("identity.nickname.max-length must be between min-length and 32");
        }
        if (guiTimeout < 10) {
            errors.add("gui.session-timeout-seconds must be at least 10");
        }
        if (poiTypes.isEmpty()) {
            errors.add("poi.types must list at least one POI type");
        }
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return new CoreConfig(min, max, guiTimeout, Set.copyOf(poiTypes), Map.copyOf(catalog));
    }
}
