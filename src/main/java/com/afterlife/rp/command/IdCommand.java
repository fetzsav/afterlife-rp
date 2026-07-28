package com.afterlife.rp.command;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.shared.identity.IdentityService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /id — shows the caller's permanent public ID (master plan §8.1). */
public final class IdCommand implements CommandExecutor {

    private final IdentityService identityService;
    private final Messages messages;

    public IdCommand(IdentityService identityService, Messages messages) {
        this.identityService = identityService;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return true;
        }
        identityService.cached(player.getUniqueId()).ifPresentOrElse(
                identity -> messages.send(player, "identity.id-self",
                        Placeholder.unparsed("id", String.valueOf(identity.publicId()))),
                () -> messages.send(player, "general.db-unavailable"));
        return true;
    }
}
