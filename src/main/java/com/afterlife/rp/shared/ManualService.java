package com.afterlife.rp.shared;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.config.YamlResources;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Data-driven, localized in-game manuals (§9.8, launch content). Books are
 * defined per language in manuals_&lt;code&gt;.yml so staff can edit titles,
 * authors, and pages without code; unavailable languages fall back to the
 * default.
 */
public final class ManualService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Map<String, YamlConfiguration> byLanguage;
    private final String defaultLanguage;

    private ManualService(Map<String, YamlConfiguration> byLanguage, String defaultLanguage) {
        this.byLanguage = byLanguage;
        this.defaultLanguage = defaultLanguage;
    }

    public static ManualService load(JavaPlugin plugin, Messages messages) {
        Map<String, YamlConfiguration> byLanguage = new HashMap<>();
        for (String lang : messages.languages()) {
            String resource = "manuals_" + lang + ".yml";
            File file = new File(plugin.getDataFolder(), resource);
            if (!file.exists() && plugin.getResource(resource) != null) {
                plugin.saveResource(resource, false);
            }
            if (file.exists()) {
                byLanguage.put(lang, YamlResources.loadWithNewKeys(plugin, file, resource));
            }
        }
        return new ManualService(byLanguage, messages.defaultLanguage());
    }

    private YamlConfiguration configFor(String language) {
        YamlConfiguration config = byLanguage.get(language);
        return config != null ? config : byLanguage.get(defaultLanguage);
    }

    public Set<String> topics() {
        ConfigurationSection manuals = configFor(defaultLanguage) == null
                ? null : configFor(defaultLanguage).getConfigurationSection("manuals");
        return manuals == null ? Set.of() : new TreeSet<>(manuals.getKeys(false));
    }

    /** Builds the written book for a topic in a language, empty when unknown. */
    public Optional<ItemStack> book(String topic, String language) {
        YamlConfiguration config = configFor(language);
        ConfigurationSection section = config == null ? null
                : config.getConfigurationSection("manuals." + topic.toLowerCase(Locale.ROOT));
        if (section == null && config != configFor(defaultLanguage)) {
            config = configFor(defaultLanguage);
            section = config == null ? null
                    : config.getConfigurationSection("manuals." + topic.toLowerCase(Locale.ROOT));
        }
        if (section == null) {
            return Optional.empty();
        }
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.title(Component.text(section.getString("title", "AfterLife")));
        meta.author(Component.text(section.getString("author", "AfterLife")));
        List<Component> pages = new ArrayList<>();
        for (String page : section.getStringList("pages")) {
            pages.add(MINI.deserialize(page));
        }
        meta.pages(pages);
        book.setItemMeta(meta);
        return Optional.of(book);
    }
}
