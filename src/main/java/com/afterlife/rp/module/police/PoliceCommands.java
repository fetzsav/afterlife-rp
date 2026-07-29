package com.afterlife.rp.module.police;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.missions.JobSessionService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import com.afterlife.rp.command.TabComplete;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /polizia and /k9 (§9.3): mandato, perquisisci, consenso, sequestra,
 * controlloconto, allerte, rispondi; K-9 turno/schiera/fiuta.
 */
public final class PoliceCommands implements CommandExecutor, TabCompleter {

    private static final String PERM_OFFICER = "afterlife.police.officer";
    private static final String PERM_K9 = "afterlife.police.k9";

    private record Consent(UUID officer, Instant grantedAt) {}

    private final DatabaseManager databaseManager;
    private final PoliceService service;
    private final K9Runtime k9Runtime;
    private final JobSessionService jobSessions;
    private final SerializedItemService itemService;
    private final Messages messages;
    // target -> consent granted to a specific officer, valid briefly.
    private final Map<UUID, Consent> consents = new ConcurrentHashMap<>();

    public PoliceCommands(DatabaseManager databaseManager, PoliceService service,
            K9Runtime k9Runtime, JobSessionService jobSessions, SerializedItemService itemService,
            Messages messages) {
        this.databaseManager = databaseManager;
        this.service = service;
        this.k9Runtime = k9Runtime;
        this.jobSessions = jobSessions;
        this.itemService = itemService;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return true;
        }
        if (!databaseManager.ready()) {
            messages.send(player, "general.db-unavailable");
            return true;
        }
        if (command.getName().equalsIgnoreCase("k9")) {
            return k9(player, args);
        }
        if (command.getName().equalsIgnoreCase("acconsenti")) {
            consent(player, args);
            return true;
        }
        if (!player.hasPermission(PERM_OFFICER)) {
            messages.send(player, "general.no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "mandato" -> warrant(player, args);
            case "perquisisci" -> search(player, args);
            case "sequestra" -> seize(player, args);
            case "controlloconto" -> checkAccount(player, args);
            case "allerte" -> alerts(player);
            case "rispondi" -> respond(player, args);
            default -> messages.send(player, "police.usage");
        }
        return true;
    }

    private void warrant(Player officer, String[] args) {
        if (args.length < 3) {
            messages.send(officer, "police.mandato-usage");
            return;
        }
        String type = args[1].toUpperCase(Locale.ROOT);
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null || (!type.equals("SEARCH") && !type.equals("ARREST"))) {
            messages.send(officer, "police.mandato-usage");
            return;
        }
        service.issueWarrant(type, target.getUniqueId(), officer.getUniqueId(), officer.getName(),
                        args.length > 3 ? String.join(" ", List.of(args).subList(3, args.length)) : null,
                        null)
                .thenAccept(id -> messages.send(officer, "police.warrant-issued",
                        Placeholder.unparsed("type", type),
                        Placeholder.unparsed("player", target.getName())));
    }

    private void search(Player officer, String[] args) {
        if (args.length < 3) {
            messages.send(officer, "police.perquisisci-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || target.getLocation().distanceSquared(officer.getLocation())
                > service.config().searchRangeBlocks() * service.config().searchRangeBlocks()) {
            messages.send(officer, "police.target-too-far");
            return;
        }
        PoliceService.SearchAuthority authority;
        try {
            authority = PoliceService.SearchAuthority.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            messages.send(officer, "police.perquisisci-usage");
            return;
        }
        boolean consentGiven = false;
        if (authority == PoliceService.SearchAuthority.CONSENT) {
            Consent consent = consents.get(target.getUniqueId());
            consentGiven = consent != null && consent.officer().equals(officer.getUniqueId())
                    && Duration.between(consent.grantedAt(), Instant.now()).getSeconds() < 60;
        }
        service.authorizeSearch(officer.getUniqueId(), officer.getName(), target.getUniqueId(),
                        authority, consentGiven)
                .thenAccept(decision -> databaseManager.db().onMain(() -> {
                    if (decision == PoliceService.SearchDecision.DENIED) {
                        messages.send(officer, "police.search-denied");
                        return;
                    }
                    consents.remove(target.getUniqueId());
                    List<String> items = new java.util.ArrayList<>();
                    for (var stack : target.getInventory().getContents()) {
                        if (stack == null) {
                            continue;
                        }
                        itemService.readVerified(stack)
                                .ifPresent(data -> items.add(data.itemType()
                                        + " (" + data.serial().toString().substring(0, 8) + ")"));
                    }
                    messages.send(officer, "police.search-result",
                            Placeholder.unparsed("player", target.getName()),
                            Placeholder.unparsed("items",
                                    items.isEmpty() ? "nessun oggetto tracciato" : String.join(", ", items)));
                }));
    }

    private void consent(Player target, String[] args) {
        if (args.length != 1) {
            messages.send(target, "police.acconsenti-usage");
            return;
        }
        Player officer = Bukkit.getPlayerExact(args[0]);
        if (officer == null) {
            messages.send(target, "bank.target-not-found");
            return;
        }
        consents.put(target.getUniqueId(), new Consent(officer.getUniqueId(), Instant.now()));
        messages.send(target, "police.consent-given",
                Placeholder.unparsed("player", officer.getName()));
        messages.send(officer, "police.consent-received",
                Placeholder.unparsed("player", target.getName()));
    }

    private void seize(Player officer, String[] args) {
        var held = itemService.readVerified(officer.getInventory().getItemInMainHand()).orElse(null);
        if (held == null) {
            messages.send(officer, "police.seize-hold");
            return;
        }
        String description = args.length > 1
                ? String.join(" ", List.of(args).subList(1, args.length))
                : held.itemType();
        service.seize(description, held.serial(), officer.getUniqueId(), officer.getName())
                .thenAccept(id -> databaseManager.db().onMain(() -> {
                    if (officer.isOnline()) {
                        officer.getInventory().setItemInMainHand(null);
                        messages.send(officer, "police.seized",
                                Placeholder.unparsed("id", String.valueOf(id)));
                    }
                }));
    }

    private void checkAccount(Player officer, String[] args) {
        if (args.length != 2) {
            messages.send(officer, "police.controllo-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(officer, "bank.target-not-found");
            return;
        }
        service.checkAccount(officer.getUniqueId(), officer.getName(), target.getUniqueId(),
                        target.getName())
                .thenAccept(check -> messages.send(officer, check.isPresent()
                                ? "police.controllo-result" : "police.controllo-none",
                        Placeholder.unparsed("player", target.getName()),
                        Placeholder.unparsed("band", check.map(PoliceService.AccountCheck::balanceBand)
                                .orElse("-")),
                        Placeholder.unparsed("status", check.map(c -> c.frozen()
                                ? "CONGELATO" : "attivo").orElse("-"))));
    }

    private void alerts(Player officer) {
        service.openAlerts().thenAccept(alerts -> {
            messages.send(officer, "police.alerts-header",
                    Placeholder.unparsed("count", String.valueOf(alerts.size())));
            for (var alert : alerts) {
                messages.send(officer, "police.alerts-entry",
                        Placeholder.unparsed("id", String.valueOf(alert.id())),
                        Placeholder.unparsed("type", alert.type()),
                        Placeholder.unparsed("district", alert.district()),
                        Placeholder.unparsed("date", alert.createdAt()));
            }
        });
    }

    private void respond(Player officer, String[] args) {
        if (args.length != 2) {
            messages.send(officer, "police.rispondi-usage");
            return;
        }
        long id;
        try {
            id = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            messages.send(officer, "police.rispondi-usage");
            return;
        }
        service.respondAlert(id, officer.getUniqueId(), officer.getName())
                .thenAccept(responded -> messages.send(officer,
                        responded ? "police.alert-responded" : "police.alert-not-open"));
    }

    private boolean k9(Player handler, String[] args) {
        if (!handler.hasPermission(PERM_K9)) {
            messages.send(handler, "general.no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "turno" -> jobSessions.start(handler.getUniqueId(), K9Runtime.JOB_K9)
                    .thenAccept(started -> messages.send(handler,
                            started ? "police.k9-duty-on" : "police.k9-duty-already"));
            case "fine" -> {
                k9Runtime.undeploy(handler.getUniqueId());
                jobSessions.end(handler.getUniqueId(), K9Runtime.JOB_K9)
                        .thenAccept(ended -> messages.send(handler,
                                ended ? "police.k9-duty-off" : "police.k9-duty-not-on"));
            }
            case "schiera" -> {
                if (!jobSessions.isOnDuty(handler.getUniqueId(), K9Runtime.JOB_K9)) {
                    messages.send(handler, "police.k9-duty-required");
                    return true;
                }
                boolean deployed = k9Runtime.toggleDeploy(handler);
                messages.send(handler, deployed ? "police.k9-deployed" : "police.k9-recalled");
            }
            case "fiuta" -> {
                if (args.length != 2) {
                    messages.send(handler, "police.k9-usage");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null || target.getLocation().distanceSquared(handler.getLocation())
                        > service.config().k9RadiusBlocks() * service.config().k9RadiusBlocks()) {
                    messages.send(handler, "police.target-too-far");
                    return true;
                }
                var contraband = k9Runtime.detectContraband(target);
                if (contraband.isEmpty()) {
                    messages.send(handler, "police.k9-clean",
                            Placeholder.unparsed("player", target.getName()));
                } else {
                    messages.send(handler, "police.k9-indication",
                            Placeholder.unparsed("player", target.getName()),
                            Placeholder.unparsed("types", String.join(", ", contraband)));
                }
            }
            default -> messages.send(handler, "police.k9-usage");
        }
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(@org.jetbrains.annotations.NotNull CommandSender sender,
            @org.jetbrains.annotations.NotNull Command command,
            @org.jetbrains.annotations.NotNull String alias, String @org.jetbrains.annotations.NotNull [] args) {
        String cmd = command.getName().toLowerCase(java.util.Locale.ROOT);
        switch (cmd) {
            case "polizia" -> {
                if (args.length == 1) {
                    return TabComplete.filter(java.util.List.of("mandato", "perquisisci", "sequestra",
                            "controlloconto", "allerte", "rispondi"), args[0]);
                }
                if (args.length == 2) {
                    String sub = args[0].toLowerCase(java.util.Locale.ROOT);
                    if (sub.equals("mandato")) {
                        return TabComplete.filter(java.util.List.of("SEARCH", "ARREST"), args[1]);
                    }
                    if (sub.equals("perquisisci") || sub.equals("controlloconto")) {
                        return TabComplete.players(args[1]);
                    }
                }
                if (args.length == 3 && args[0].equalsIgnoreCase("perquisisci")) {
                    return TabComplete.filter(java.util.List.of("CONSENT", "WARRANT", "EXIGENT"),
                            args[2]);
                }
            }
            case "k9" -> {
                if (args.length == 1) {
                    return TabComplete.filter(java.util.List.of("turno", "fine", "schiera", "fiuta"),
                            args[0]);
                }
                if (args.length == 2 && args[0].equalsIgnoreCase("fiuta")) {
                    return TabComplete.players(args[1]);
                }
            }
            case "acconsenti" -> {
                if (args.length == 1) {
                    return TabComplete.players(args[0]);
                }
            }
            default -> { }
        }
        return java.util.List.of();
    }

}
