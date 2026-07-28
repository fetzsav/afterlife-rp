package com.afterlife.rp.config;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Loads per-module configuration files from plugins/AfterLifeRP/modules/ (§11). */
public final class ModuleConfigs {

    private ModuleConfigs() {}

    public static YamlConfiguration load(JavaPlugin plugin, String moduleName) {
        String path = "modules/" + moduleName + ".yml";
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            plugin.saveResource(path, false);
        }
        return YamlConfiguration.loadConfiguration(file);
    }
}
