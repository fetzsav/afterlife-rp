package com.afterlife.rp.shared.identity;

import java.util.HashSet;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Renders nametags via main-scoreboard teams: standard "[#12] Name",
 * VIP "[#12] Name (Nick)" (master plan §8.1). Main-thread only.
 */
public final class NametagService {

    private final Set<String> managedTeams = new HashSet<>();

    public void apply(Player player, PlayerIdentity identity) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = teamName(identity);
        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }
        team.prefix(Component.text("[#" + identity.publicId() + "] ", NamedTextColor.GRAY));
        if (identity.nickname() != null && !identity.nickname().isBlank()) {
            team.suffix(Component.text(" (" + identity.nickname() + ")", NamedTextColor.AQUA));
        } else {
            team.suffix(Component.empty());
        }
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }
        managedTeams.add(teamName);
        player.playerListName(Component.text("[#" + identity.publicId() + "] ")
                .color(NamedTextColor.DARK_GRAY)
                .append(Component.text(player.getName(), NamedTextColor.WHITE)));
    }

    public void remove(Player player, PlayerIdentity identity) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = teamName(identity);
        Team team = scoreboard.getTeam(teamName);
        if (team != null) {
            team.removeEntry(player.getName());
            if (team.getEntries().isEmpty()) {
                // Read nothing off the team after unregister: CraftTeam throws
                // IllegalStateException from every accessor, getName() included.
                team.unregister();
                managedTeams.remove(teamName);
            }
        }
    }

    /** Removes every team this service created; called on plugin disable. */
    public void clearAll() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        for (String teamName : Set.copyOf(managedTeams)) {
            Team team = scoreboard.getTeam(teamName);
            if (team != null) {
                team.unregister();
            }
        }
        managedTeams.clear();
    }

    private String teamName(PlayerIdentity identity) {
        return "alrp_" + identity.publicId();
    }
}
