package com.afterlife.rp.command;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.shared.ManualService;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /manuale [argomento] — hands out an in-game manual book. */
public final class ManualCommand implements CommandExecutor, TabCompleter {

    private final ManualService manuals;
    private final Messages messages;

    public ManualCommand(ManualService manuals, Messages messages) {
        this.manuals = manuals;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return true;
        }
        if (args.length == 0) {
            messages.send(player, "manual.list-header",
                    Placeholder.unparsed("topics", String.join(", ", manuals.topics())));
            return true;
        }
        String topic = args[0].toLowerCase(Locale.ROOT);
        manuals.book(topic, messages.languageFor(player)).ifPresentOrElse(book -> {
            player.getInventory().addItem(book).values().forEach(rest ->
                    player.getWorld().dropItemNaturally(player.getLocation(), rest));
            messages.send(player, "manual.given", Placeholder.unparsed("topic", topic));
        }, () -> messages.send(player, "manual.unknown",
                Placeholder.unparsed("topics", String.join(", ", manuals.topics()))));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, String @NotNull [] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return manuals.topics().stream().filter(t -> t.startsWith(prefix)).toList();
    }
}
