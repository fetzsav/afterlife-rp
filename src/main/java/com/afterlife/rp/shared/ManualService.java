package com.afterlife.rp.shared;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * Data-driven in-game manuals (§9.8 manual, launch content). Books are defined
 * in manuals_it.yml so staff can edit titles, authors, and pages without code.
 */
public final class ManualService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final YamlConfiguration config;

    private ManualService(YamlConfiguration config) {
        this.config = config;
    }

    public static ManualService load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "manuals_it.yml");
        if (!file.exists()) {
            plugin.saveResource("manuals_it.yml", false);
        }
        return new ManualService(YamlConfiguration.loadConfiguration(file));
    }

    public Set<String> topics() {
        ConfigurationSection manuals = config.getConfigurationSection("manuals");
        return manuals == null ? Set.of() : new TreeSet<>(manuals.getKeys(false));
    }

    /** Builds the written book for a topic, or empty when the topic is unknown. */
    public Optional<ItemStack> book(String topic) {
        ConfigurationSection section = config.getConfigurationSection(
                "manuals." + topic.toLowerCase(Locale.ROOT));
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
