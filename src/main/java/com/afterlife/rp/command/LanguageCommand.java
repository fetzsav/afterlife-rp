package com.afterlife.rp.command;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.identity.IdentityService;
import com.afterlife.rp.shared.identity.NametagService;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /language [code] — show or set the player's viewing language. */
public final class LanguageCommand implements CommandExecutor, TabCompleter {

    private final DatabaseManager databaseManager;
    private final IdentityService identityService;
    private final NametagService nametagService;
    private final Messages messages;

    public LanguageCommand(DatabaseManager databaseManager, IdentityService identityService,
            NametagService nametagService, Messages messages) {
        this.databaseManager = databaseManager;
        this.identityService = identityService;
        this.nametagService = nametagService;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return true;
        }
        String langs = String.join(", ", messages.languages());
        if (args.length == 0) {
            messages.send(player, "language.current",
                    Placeholder.unparsed("lang", messages.languageFor(player)),
                    Placeholder.unparsed("langs", langs));
            return true;
        }
        if (!databaseManager.ready()) {
            messages.send(player, "general.db-unavailable");
            return true;
        }
        String choice = args[0].toLowerCase(Locale.ROOT);
        if (!messages.hasLanguage(choice)) {
            messages.send(player, "language.invalid", Placeholder.unparsed("langs", langs));
            return true;
        }
        identityService.setLocale(player.getUniqueId(), choice).thenAccept(identity ->
                databaseManager.db().onMain(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    // Nametag suffix/prefix text is language-neutral, but refresh
                    // so a VIP nickname stays applied after the identity reload.
                    nametagService.apply(player, identity);
                    messages.send(player, "language.set", Placeholder.unparsed("lang", choice));
                }));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, String @NotNull [] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return messages.languages().stream().filter(l -> l.startsWith(prefix)).toList();
    }
}
