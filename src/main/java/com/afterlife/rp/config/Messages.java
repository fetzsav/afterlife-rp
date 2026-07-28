package com.afterlife.rp.config;

import java.io.File;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Italian player-facing messages rendered through MiniMessage (master plan §1, §11). */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final YamlConfiguration messages;
    private final String prefix;

    private Messages(YamlConfiguration messages) {
        this.messages = messages;
        this.prefix = messages.getString("prefix", "");
    }

    public static Messages load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages_it.yml");
        if (!file.exists()) {
            plugin.saveResource("messages_it.yml", false);
        }
        return new Messages(YamlConfiguration.loadConfiguration(file));
    }

    /** Renders a message with the global prefix. */
    public Component msg(String key, TagResolver... resolvers) {
        return MINI.deserialize(prefix + raw(key), resolvers);
    }

    /** Renders a message without the global prefix (kick screens, lore, reports). */
    public Component bare(String key, TagResolver... resolvers) {
        return MINI.deserialize(raw(key), resolvers);
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        to.sendMessage(msg(key, resolvers));
    }

    private String raw(String key) {
        String value = messages.getString(key);
        if (value == null) {
            return "<red>Messaggio mancante: " + key + "</red>";
        }
        return value;
    }
}
