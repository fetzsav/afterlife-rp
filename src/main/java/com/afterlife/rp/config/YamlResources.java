package com.afterlife.rp.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Shared loading for generated YAML resources (messages, manuals). */
public final class YamlResources {

    private YamlResources() {}

    /**
     * Loads a generated resource and adds any key the bundled version has and the
     * file does not. {@code saveResource} never overwrites, so without this an
     * upgrade that ships new keys would leave holes in an already-generated file.
     * Text the admin edited is never touched.
     */
    public static YamlConfiguration loadWithNewKeys(JavaPlugin plugin, File file, String resource) {
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream bundledStream = plugin.getResource(resource)) {
            if (bundledStream == null) {
                return loaded;
            }
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(bundledStream, StandardCharsets.UTF_8));
            int added = 0;
            for (String key : bundled.getKeys(true)) {
                if (!bundled.isConfigurationSection(key) && !loaded.contains(key)) {
                    loaded.set(key, bundled.get(key));
                    added++;
                }
            }
            if (added > 0) {
                loaded.save(file);
                plugin.getLogger().info("Added " + added + " new entr(ies) to " + resource);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not merge new entries into " + resource
                    + ": " + e.getMessage());
        }
        return loaded;
    }
}
