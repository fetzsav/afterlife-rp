package com.afterlife.rp.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Locale-aware player-facing messages (MiniMessage). Each language is a
 * messages_&lt;code&gt;.yml file; a message renders in the recipient's chosen
 * language, falling back to the default language, then to a visible marker.
 * Keys and config are English; only the rendered text is localized (§1, §11).
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Map<String, YamlConfiguration> byLanguage;
    private final String defaultLanguage;
    // Resolves a player's chosen language; defaults to the default language
    // until identity is wired in. Never returns null → default.
    private volatile Function<UUID, String> localeResolver;

    private Messages(Map<String, YamlConfiguration> byLanguage, String defaultLanguage) {
        this.byLanguage = byLanguage;
        this.defaultLanguage = defaultLanguage;
        this.localeResolver = uuid -> defaultLanguage;
    }

    public static Messages load(JavaPlugin plugin) {
        String defaultLanguage = plugin.getConfig().getString("language.default", "en")
                .toLowerCase(Locale.ROOT);
        Set<String> languages = new LinkedHashSet<>();
        languages.add(defaultLanguage);
        for (String lang : plugin.getConfig().getStringList("language.available")) {
            languages.add(lang.toLowerCase(Locale.ROOT));
        }
        Map<String, YamlConfiguration> byLanguage = new HashMap<>();
        for (String lang : languages) {
            String resource = "messages_" + lang + ".yml";
            File file = new File(plugin.getDataFolder(), resource);
            if (!file.exists() && plugin.getResource(resource) != null) {
                plugin.saveResource(resource, false);
            }
            if (file.exists()) {
                byLanguage.put(lang, loadWithNewKeys(plugin, file, resource));
            } else {
                plugin.getLogger().warning("Missing translation file " + resource
                        + " — language '" + lang + "' will be unavailable.");
            }
        }
        if (!byLanguage.containsKey(defaultLanguage)) {
            throw new IllegalStateException(
                    "Default language file messages_" + defaultLanguage + ".yml is missing");
        }
        return new Messages(byLanguage, defaultLanguage);
    }

    /**
     * Loads a translation file and adds any message the bundled version has and
     * the file does not. An upgrade that ships new keys must not leave holes in
     * an already-customized file, and edited texts are never overwritten.
     */
    private static YamlConfiguration loadWithNewKeys(JavaPlugin plugin, File file, String resource) {
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
                plugin.getLogger().info("Added " + added + " new message(s) to " + resource);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not merge new messages into " + resource
                    + ": " + e.getMessage());
        }
        return loaded;
    }

    /** Wires per-player language lookup once identity is available. */
    public void setLocaleResolver(Function<UUID, String> resolver) {
        this.localeResolver = resolver;
    }

    public String defaultLanguage() {
        return defaultLanguage;
    }

    public Set<String> languages() {
        return new LinkedHashSet<>(byLanguage.keySet());
    }

    public boolean hasLanguage(String language) {
        return language != null && byLanguage.containsKey(language.toLowerCase(Locale.ROOT));
    }

    /** The effective language for a recipient (players get their choice). */
    public String languageFor(CommandSender to) {
        if (!(to instanceof Player player)) {
            return defaultLanguage;
        }
        String lang = localeResolver.apply(player.getUniqueId());
        return lang != null && byLanguage.containsKey(lang) ? lang : defaultLanguage;
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        String language = languageFor(to);
        to.sendMessage(MINI.deserialize(prefix(language) + raw(language, key), resolvers));
    }

    /** Renders in the default language, with prefix. */
    public Component msg(String key, TagResolver... resolvers) {
        return MINI.deserialize(prefix(defaultLanguage) + raw(defaultLanguage, key), resolvers);
    }

    /** Renders in the default language, without the prefix (kick screens, lore). */
    public Component bare(String key, TagResolver... resolvers) {
        return MINI.deserialize(raw(defaultLanguage, key), resolvers);
    }

    /** Renders in the recipient's language, without the prefix (inline fragments). */
    public Component bareFor(CommandSender to, String key, TagResolver... resolvers) {
        return MINI.deserialize(raw(languageFor(to), key), resolvers);
    }

    private String prefix(String language) {
        YamlConfiguration config = byLanguage.get(language);
        return config == null ? "" : config.getString("prefix", "");
    }

    private String raw(String language, String key) {
        YamlConfiguration config = byLanguage.get(language);
        String value = config == null ? null : config.getString(key);
        if (value == null && !language.equals(defaultLanguage)) {
            value = byLanguage.get(defaultLanguage).getString(key);
        }
        if (value == null) {
            return "<red>Missing message: " + key + "</red>";
        }
        return value;
    }
}
