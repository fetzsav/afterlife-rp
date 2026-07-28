package com.afterlife.rp.command;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.config.CoreConfig;
import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.identity.IdentityService;
import com.afterlife.rp.shared.identity.NametagService;
import com.afterlife.rp.shared.identity.NicknameSanitizer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /setnick <text|reset|off> — VIP nickname management (master plan §8.1). */
public final class SetNickCommand implements CommandExecutor {

    private static final String PERMISSION = "afterlife.vip.nickname";

    private final DatabaseManager databaseManager;
    private final IdentityService identityService;
    private final NametagService nametagService;
    private final AuditService auditService;
    private final CoreConfig coreConfig;
    private final Messages messages;

    public SetNickCommand(
            DatabaseManager databaseManager,
            IdentityService identityService,
            NametagService nametagService,
            AuditService auditService,
            CoreConfig coreConfig,
            Messages messages) {
        this.databaseManager = databaseManager;
        this.identityService = identityService;
        this.nametagService = nametagService;
        this.auditService = auditService;
        this.coreConfig = coreConfig;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            messages.send(player, "general.no-permission");
            return true;
        }
        if (!databaseManager.ready()) {
            messages.send(player, "general.db-unavailable");
            return true;
        }
        if (args.length == 0) {
            messages.send(player, "nickname.usage");
            return true;
        }

        String argument = String.join(" ", args);
        boolean clearing = args.length == 1
                && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("off"));

        String nickname;
        if (clearing) {
            nickname = null;
        } else {
            Optional<String> sanitized = NicknameSanitizer.sanitize(
                    argument, coreConfig.nicknameMinLength(), coreConfig.nicknameMaxLength());
            if (sanitized.isEmpty()) {
                messages.send(player, "nickname.invalid-length",
                        Placeholder.unparsed("min", String.valueOf(coreConfig.nicknameMinLength())),
                        Placeholder.unparsed("max", String.valueOf(coreConfig.nicknameMaxLength())));
                return true;
            }
            nickname = sanitized.get();
        }

        identityService.setNickname(player.getUniqueId(), nickname).whenComplete((updated, error) -> {
            if (error != null) {
                messages.send(player, "general.internal-error");
                return;
            }
            // Return to the server thread for the nametag mutation (rule 2).
            databaseManager.db().onMain(() -> {
                if (!player.isOnline()) {
                    return;
                }
                nametagService.apply(player, updated);
                if (nickname == null) {
                    messages.send(player, "nickname.removed");
                } else {
                    messages.send(player, "nickname.set",
                            Placeholder.unparsed("nickname", nickname));
                }
            });
            auditService.log(player.getUniqueId(), player.getName(),
                    nickname == null ? "NICKNAME_CLEARED" : "NICKNAME_SET",
                    player.getUniqueId().toString(),
                    nickname == null ? Map.of() : Map.of("nickname", nickname.toLowerCase(Locale.ROOT)));
        });
        return true;
    }
}
