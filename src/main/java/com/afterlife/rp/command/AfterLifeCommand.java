package com.afterlife.rp.command;

import com.afterlife.rp.config.CoreConfig;
import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.integration.Adapter;
import com.afterlife.rp.integration.IntegrationManager;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.regions.Poi;
import com.afterlife.rp.shared.regions.PoiService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/** /afterlife <version|health|setup poi ...|debug item ...> (master plan §12, §16). */
public final class AfterLifeCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "afterlife.admin";
    private static final String SETUP = "afterlife.admin.setup";

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final IntegrationManager integrationManager;
    private final PoiService poiService;
    private final SerializedItemService itemService;
    private final CoreConfig coreConfig;
    private final Messages messages;

    public AfterLifeCommand(
            JavaPlugin plugin,
            DatabaseManager databaseManager,
            IntegrationManager integrationManager,
            PoiService poiService,
            SerializedItemService itemService,
            CoreConfig coreConfig,
            Messages messages) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.integrationManager = integrationManager;
        this.poiService = poiService;
        this.itemService = itemService;
        this.coreConfig = coreConfig;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "version" -> version(sender);
            case "health" -> health(sender);
            case "setup" -> setup(sender, args);
            case "debug" -> debug(sender, args);
            default -> sendUsage(sender, label);
        }
        return true;
    }

    private void sendUsage(CommandSender sender, String label) {
        messages.send(sender, "general.unknown-subcommand",
                Placeholder.unparsed("usage", "/" + label + " <version|health|setup|debug>"));
    }

    private void version(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            messages.send(sender, "general.no-permission");
            return;
        }
        messages.send(sender, "health.version",
                Placeholder.unparsed("version", plugin.getPluginMeta().getVersion()),
                Placeholder.unparsed("paper", Bukkit.getVersion()));
    }

    private void health(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            messages.send(sender, "general.no-permission");
            return;
        }
        messages.send(sender, "health.header");
        databaseManager.health().thenAccept(health -> {
            if (health.ok()) {
                messages.send(sender, "health.db-ok",
                        Placeholder.unparsed("latency", String.valueOf(health.latencyMs())));
            } else {
                messages.send(sender, "health.db-fail",
                        Placeholder.unparsed("detail", String.valueOf(health.detail())));
            }
            for (Adapter adapter : integrationManager.adapters()) {
                messages.send(sender, adapter.available() ? "health.adapter-ok" : "health.adapter-missing",
                        Placeholder.unparsed("name", adapter.name()),
                        Placeholder.unparsed("detail", adapter.detail()));
            }
        });
    }

    private void setup(CommandSender sender, String[] args) {
        if (!sender.hasPermission(SETUP)) {
            messages.send(sender, "general.no-permission");
            return;
        }
        if (!databaseManager.ready()) {
            messages.send(sender, "general.db-unavailable");
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("poi")) {
            messages.send(sender, "poi.usage");
            return;
        }
        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "create" -> poiCreate(sender, args);
            case "remove" -> poiRemove(sender, args);
            case "list" -> poiList(sender);
            case "report" -> poiReport(sender);
            default -> messages.send(sender, "poi.usage");
        }
    }

    private void poiCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return;
        }
        if (args.length < 5) {
            messages.send(sender, "poi.usage");
            return;
        }
        String type = args[3].toUpperCase(Locale.ROOT);
        String name = args[4];
        String regionId = args.length >= 6 ? args[5] : null;

        if (!coreConfig.poiTypes().contains(type)) {
            messages.send(sender, "poi.invalid-type",
                    Placeholder.unparsed("types", String.join(", ", coreConfig.poiTypes())));
            return;
        }
        if (poiService.byName(name).isPresent()) {
            messages.send(sender, "poi.already-exists", Placeholder.unparsed("name", name));
            return;
        }
        if (regionId != null) {
            if (integrationManager.worldGuard() == null || !integrationManager.worldGuard().available()) {
                messages.send(sender, "poi.worldguard-missing");
                return;
            }
            if (!integrationManager.worldGuard().regionExists(player.getWorld(), regionId)) {
                messages.send(sender, "poi.region-not-found", Placeholder.unparsed("region", regionId));
                return;
            }
        }
        poiService.create(name, type, player.getLocation(), regionId, player.getUniqueId(), player.getName())
                .whenComplete((poi, error) -> {
                    if (error != null) {
                        messages.send(player, "general.internal-error");
                        plugin.getLogger().warning("POI create failed: " + error.getMessage());
                        return;
                    }
                    messages.send(player, "poi.created",
                            Placeholder.unparsed("name", poi.name()),
                            Placeholder.unparsed("type", poi.type()));
                });
    }

    private void poiRemove(CommandSender sender, String[] args) {
        if (args.length < 4) {
            messages.send(sender, "poi.usage");
            return;
        }
        String name = args[3];
        java.util.UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        poiService.remove(name, actor, sender.getName()).thenAccept(removed -> {
            if (removed) {
                messages.send(sender, "poi.removed", Placeholder.unparsed("name", name));
            } else {
                messages.send(sender, "poi.not-found", Placeholder.unparsed("name", name));
            }
        });
    }

    private void poiList(CommandSender sender) {
        var pois = poiService.all();
        messages.send(sender, "poi.list-header",
                Placeholder.unparsed("count", String.valueOf(pois.size())));
        for (Poi poi : pois) {
            messages.send(sender, "poi.list-entry",
                    Placeholder.unparsed("name", poi.name()),
                    Placeholder.unparsed("type", poi.type()),
                    Placeholder.unparsed("world", poi.world()),
                    Placeholder.unparsed("x", String.valueOf((int) poi.x())),
                    Placeholder.unparsed("y", String.valueOf((int) poi.y())),
                    Placeholder.unparsed("z", String.valueOf((int) poi.z())),
                    Placeholder.unparsed("region",
                            poi.regionId() == null ? "" : " region=" + poi.regionId()));
        }
    }

    private void poiReport(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        lines.add("AfterLifeRP setup report — " + Instant.now());
        lines.add("");
        var byType = poiService.all().stream().collect(Collectors.groupingBy(Poi::type));
        for (String type : coreConfig.poiTypes()) {
            List<Poi> pois = byType.getOrDefault(type, List.of());
            lines.add("[" + type + "] " + pois.size() + " POI");
            for (Poi poi : pois) {
                lines.add("  - " + poi.name() + " @ " + poi.world() + " "
                        + (int) poi.x() + "," + (int) poi.y() + "," + (int) poi.z()
                        + (poi.regionId() == null ? "" : " region=" + poi.regionId()));
            }
        }
        Path file = plugin.getDataFolder().toPath().resolve("setup-report.txt");
        // File IO off the main thread; confirmation returns via the DB main executor.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Files.write(file, lines, StandardCharsets.UTF_8);
                databaseManager.db().onMain(() -> messages.send(sender, "poi.report-saved",
                        Placeholder.unparsed("file", file.toString())));
            } catch (IOException e) {
                plugin.getLogger().warning("Setup report write failed: " + e.getMessage());
            }
        });
    }

    private void debug(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            messages.send(sender, "general.no-permission");
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return;
        }
        if (!databaseManager.ready()) {
            messages.send(sender, "general.db-unavailable");
            return;
        }
        if (args.length < 3 || !args[1].equalsIgnoreCase("item")) {
            messages.send(sender, "debug.item-usage");
            return;
        }
        String type = args[2].toLowerCase(Locale.ROOT);
        Long denomination = null;
        if (args.length >= 4) {
            try {
                denomination = Long.parseLong(args[3]);
                if (denomination <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                messages.send(sender, "debug.item-usage");
                return;
            }
        }
        itemService.issue(type, Material.PAPER, Component.text("AfterLife: " + type),
                        player.getUniqueId(), denomination, player.getUniqueId())
                .whenComplete((stack, error) -> {
                    if (error != null) {
                        messages.send(player, "general.internal-error");
                        return;
                    }
                    databaseManager.db().onMain(() -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        // Full inventory falls back to a natural drop; M2 adds durable pending delivery.
                        player.getInventory().addItem(stack).values()
                                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
                        messages.send(player, "debug.item-issued",
                                Placeholder.unparsed("type", type),
                                Placeholder.unparsed("serial",
                                        String.valueOf(stack.getItemMeta().getPersistentDataContainer()
                                                .get(com.afterlife.rp.shared.items.ItemKeys.SERIAL,
                                                        org.bukkit.persistence.PersistentDataType.STRING))));
                    });
                });
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return filter(List.of("version", "health", "setup", "debug"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setup")) {
            return filter(List.of("poi"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setup")) {
            return filter(List.of("create", "remove", "list", "report"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("setup") && args[2].equalsIgnoreCase("create")) {
            return filter(List.copyOf(coreConfig.poiTypes()), args[3]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("setup") && args[2].equalsIgnoreCase("remove")) {
            return filter(poiService.all().stream().map(Poi::name).toList(), args[3]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return filter(List.of("item"), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
