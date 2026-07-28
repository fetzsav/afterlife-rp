package com.afterlife.rp.shared.identity;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Loads identity during async pre-login, applies nametags on join, cleans up on quit. */
public final class IdentityListener implements Listener {

    private static final String VIP_NICKNAME_PERMISSION = "afterlife.vip.nickname";

    private final DatabaseManager databaseManager;
    private final IdentityService identityService;
    private final NametagService nametagService;
    private final AuditService auditService;
    private final Messages messages;
    private final Logger logger;

    public IdentityListener(
            DatabaseManager databaseManager,
            IdentityService identityService,
            NametagService nametagService,
            AuditService auditService,
            Messages messages,
            Logger logger) {
        this.databaseManager = databaseManager;
        this.identityService = identityService;
        this.nametagService = nametagService;
        this.auditService = auditService;
        this.messages = messages;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        if (!databaseManager.ready()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    messages.bare("general.maintenance-kick"));
            return;
        }
        try {
            // Async pre-login thread: blocking here is allowed and keeps join ordering simple.
            identityService.loadAndCache(event.getUniqueId(), event.getName())
                    .get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Identity load failed for " + event.getName(), e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    messages.bare("general.maintenance-kick"));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        identityService.cached(player.getUniqueId()).ifPresent(identity -> {
            // Rule from §8.1: losing the VIP permission removes the visible nickname.
            if (identity.nickname() != null && !player.hasPermission(VIP_NICKNAME_PERMISSION)) {
                identityService.setNickname(player.getUniqueId(), null);
                identity = identity.withNickname(null);
                auditService.log(player.getUniqueId(), player.getName(),
                        "NICKNAME_AUTO_REMOVED", player.getUniqueId().toString(),
                        Map.of("reason", "missing_permission"));
            }
            nametagService.apply(player, identity);
            messages.send(player, "identity.welcome",
                    Placeholder.unparsed("name", player.getName()),
                    Placeholder.unparsed("id", String.valueOf(identity.publicId())));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        identityService.cached(event.getPlayer().getUniqueId())
                .ifPresent(identity -> nametagService.remove(event.getPlayer(), identity));
        if (databaseManager.ready()) {
            identityService.handleQuit(event.getPlayer().getUniqueId());
        }
    }
}
