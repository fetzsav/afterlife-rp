package com.afterlife.rp.command;

import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;

/**
 * Tab-completion helpers. Command executors must register a TabCompleter and
 * return a (possibly empty) list — never null — so Bukkit does not fall back to
 * suggesting online player names for every argument.
 */
public final class TabComplete {

    private TabComplete() {}

    /** Options starting with the current token (case-insensitive). */
    public static List<String> filter(List<String> options, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }

    /** Online player names starting with the current token. */
    public static List<String> players(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(player -> player.getName())
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
