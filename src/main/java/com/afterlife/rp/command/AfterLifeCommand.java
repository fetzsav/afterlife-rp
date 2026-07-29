package com.afterlife.rp.command;

import com.afterlife.rp.config.CoreConfig;
import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.integration.Adapter;
import com.afterlife.rp.integration.IntegrationManager;
import com.afterlife.rp.module.banking.BankingItems;
import com.afterlife.rp.module.banking.BankingService;
import com.afterlife.rp.module.legal.LegalService;
import com.afterlife.rp.module.realestate.RealEstateService;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.EconomyReportService;
import com.afterlife.rp.shared.economy.Money;
import com.afterlife.rp.shared.economy.ReconciliationService;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.regions.Poi;
import com.afterlife.rp.shared.regions.PoiService;
import com.afterlife.rp.setup.SetupBlueprintService;
import com.afterlife.rp.setup.SetupRegistry;
import com.afterlife.rp.setup.SetupRequirement;
import com.afterlife.rp.setup.SetupStatusService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
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
    private final ReconciliationService reconciliationService;
    private final AccountService accountService;
    private final BankingService bankingService;
    private final LegalService legalService;
    private final RealEstateService realEstateService;
    private final EconomyReportService economyReportService;
    private final SetupRegistry setupRegistry;
    private final SetupStatusService setupStatusService;
    private final SetupBlueprintService setupBlueprintService;

    public AfterLifeCommand(
            JavaPlugin plugin,
            DatabaseManager databaseManager,
            IntegrationManager integrationManager,
            PoiService poiService,
            SerializedItemService itemService,
            CoreConfig coreConfig,
            Messages messages,
            ReconciliationService reconciliationService,
            AccountService accountService,
            BankingService bankingService,
            LegalService legalService,
            RealEstateService realEstateService,
            EconomyReportService economyReportService,
            SetupRegistry setupRegistry,
            SetupStatusService setupStatusService,
            SetupBlueprintService setupBlueprintService) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.integrationManager = integrationManager;
        this.poiService = poiService;
        this.itemService = itemService;
        this.coreConfig = coreConfig;
        this.messages = messages;
        this.reconciliationService = reconciliationService;
        this.accountService = accountService;
        this.bankingService = bankingService;
        this.legalService = legalService;
        this.realEstateService = realEstateService;
        this.economyReportService = economyReportService;
        this.setupRegistry = setupRegistry;
        this.setupStatusService = setupStatusService;
        this.setupBlueprintService = setupBlueprintService;
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
            case "reconcile" -> reconcile(sender);
            case "economy" -> economy(sender);
            default -> sendUsage(sender, label);
        }
        return true;
    }

    private void sendUsage(CommandSender sender, String label) {
        messages.send(sender, "general.unknown-subcommand",
                Placeholder.unparsed("usage",
                        "/" + label + " <version|health|setup|debug|reconcile|economy>"));
    }

    private void economy(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            messages.send(sender, "general.no-permission");
            return;
        }
        if (!databaseManager.ready()) {
            messages.send(sender, "general.db-unavailable");
            return;
        }
        messages.send(sender, "economy.header");
        economyReportService.report(24).thenAccept(report -> {
            report.flows().forEach(flow -> messages.send(sender, "economy.entry",
                    Placeholder.unparsed("reason", flow.reason()),
                    Placeholder.unparsed("sign", flow.netToPlayers() >= 0 ? "+" : ""),
                    Placeholder.unparsed("amount", Money.format(flow.netToPlayers())),
                    Placeholder.unparsed("txns", String.valueOf(flow.transactions()))));
            messages.send(sender, "economy.summary",
                    Placeholder.unparsed("created", Money.format(report.totalCreated())),
                    Placeholder.unparsed("destroyed", Money.format(report.totalDestroyed())));
        });
    }

    private void reconcile(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            messages.send(sender, "general.no-permission");
            return;
        }
        if (!databaseManager.ready()) {
            messages.send(sender, "general.db-unavailable");
            return;
        }
        messages.send(sender, "reconcile.header");
        reconciliationService.run("command").thenAccept(report -> {
            if (report.clean()) {
                messages.send(sender, "reconcile.ok",
                        Placeholder.unparsed("transactions", String.valueOf(report.transactionsChecked())),
                        Placeholder.unparsed("accounts", String.valueOf(report.accountsChecked())));
            } else {
                for (String defect : report.defects()) {
                    messages.send(sender, "reconcile.defect", Placeholder.unparsed("detail", defect));
                }
            }
        });
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
        String section = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        // The checklist is a diagnostic: it stays available while the database is
        // down, and simply reports the parts it cannot verify as unknown.
        if (section.equals("status")) {
            setupStatus(sender);
            return;
        }
        if (section.isEmpty() || section.equals("help")) {
            setupMenu(sender);
            return;
        }
        if (!databaseManager.ready()) {
            messages.send(sender, "general.db-unavailable");
            return;
        }
        switch (section) {
            case "org" -> orgSetup(sender, args);
            case "license" -> licenseSetup(sender, args);
            case "property" -> propertySetup(sender, args);
            case "export" -> setupExport(sender, args);
            case "import" -> setupImport(sender, args);
            case "poi" -> {
                String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
                switch (action) {
                    case "create" -> poiCreate(sender, args);
                    case "remove" -> poiRemove(sender, args);
                    case "list" -> poiList(sender);
                    case "report" -> poiReport(sender);
                    default -> messages.send(sender, "poi.usage");
                }
            }
            default -> setupMenu(sender);
        }
    }

    /** What setup can do, each line click-to-fill so nothing has to be typed. */
    private void setupMenu(CommandSender sender) {
        messages.send(sender, "setup.menu-header");
        menuEntry(sender, "status", "/afterlife setup status");
        menuEntry(sender, "poi", "/afterlife setup poi create ");
        menuEntry(sender, "poi-list", "/afterlife setup poi list");
        menuEntry(sender, "property", "/afterlife setup property create HOUSE ");
        menuEntry(sender, "org", "/afterlife setup org create ");
        menuEntry(sender, "license", "/afterlife setup license grant ");
        menuEntry(sender, "export", "/afterlife setup export city");
        menuEntry(sender, "import", "/afterlife setup import city");
        messages.send(sender, "setup.menu-footer");
    }

    private void menuEntry(CommandSender sender, String id, String command) {
        messages.send(sender, "setup.menu-entry",
                Placeholder.component("label", messages.bareFor(sender, "setup.menu." + id)),
                Placeholder.component("command", clickable(sender, command)));
    }

    /** Live readiness checklist: every gap carries the command that closes it. */
    private void setupStatus(CommandSender sender) {
        String world = sender instanceof Player player
                ? player.getWorld().getName()
                : (Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName());
        setupStatusService.evaluate(world).whenComplete((report, error) -> onMain(() -> {
            if (error != null) {
                messages.send(sender, "general.internal-error");
                plugin.getLogger().warning("Setup status failed: " + error.getMessage());
                return;
            }
            messages.send(sender, "setup.status-header",
                    Placeholder.unparsed("ready", String.valueOf(report.readyModules())),
                    Placeholder.unparsed("total", String.valueOf(report.activeModules())));
            for (SetupStatusService.ModuleReport module : report.modules()) {
                sendModuleLine(sender, module);
                for (SetupStatusService.Check check : module.checks()) {
                    sendCheckLine(sender, check);
                }
            }
            List<SetupStatusService.Check> blocking = report.blocking();
            if (blocking.isEmpty()) {
                messages.send(sender, "setup.all-ready");
                return;
            }
            SetupStatusService.Check next = blocking.get(0);
            messages.send(sender, "setup.next-step",
                    Placeholder.unparsed("count", String.valueOf(blocking.size())),
                    Placeholder.component("label", requirementLabel(sender, next.requirement())),
                    Placeholder.component("command", clickable(sender, next.fix())));
        }));
    }

    private void sendModuleLine(CommandSender sender, SetupStatusService.ModuleReport module) {
        Component name = messages.bareFor(sender, "setup.module." + module.module().key());
        String key = switch (module.module().state()) {
            case ACTIVE -> module.ready() ? "setup.module-ready" : "setup.module-incomplete";
            case DISABLED -> "setup.module-disabled";
            case CONFIG_ERROR -> "setup.module-error";
            case BLOCKED -> "setup.module-blocked";
        };
        messages.send(sender, key,
                Placeholder.component("module", name),
                Placeholder.unparsed("detail", module.module().detail()));
    }

    private void sendCheckLine(CommandSender sender, SetupStatusService.Check check) {
        String key = switch (check.status()) {
            case OK -> "setup.check-ok";
            case UNKNOWN -> "setup.check-unknown";
            case MISSING -> check.requirement().optional()
                    ? "setup.check-optional" : "setup.check-missing";
        };
        messages.send(sender, key,
                Placeholder.component("label", requirementLabel(sender, check.requirement())),
                Placeholder.unparsed("detail", check.detail()),
                Placeholder.component("command", clickable(sender, check.fix())));
    }

    private Component requirementLabel(CommandSender sender, SetupRequirement requirement) {
        return messages.bareFor(sender, "setup.requirement." + requirement.id());
    }

    /** Click-to-fill chat suggestion; empty when there is nothing to run. */
    private Component clickable(CommandSender sender, String command) {
        if (command == null || command.isBlank()) {
            return Component.empty();
        }
        return Component.text(command)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(messages.bareFor(sender, "setup.click-hint")));
    }

    private void setupExport(CommandSender sender, String[] args) {
        String name = args.length >= 3 ? args[2] : "city";
        Path file = setupBlueprintService.fileFor(name);
        setupBlueprintService.export(file).whenComplete((result, error) -> onMain(() -> {
            if (error != null) {
                messages.send(sender, "setup.export-failed",
                        Placeholder.unparsed("detail", String.valueOf(error.getMessage())));
                plugin.getLogger().warning("Setup export failed: " + error);
                return;
            }
            messages.send(sender, "setup.export-ok",
                    Placeholder.unparsed("file", result.file().toString()),
                    Placeholder.unparsed("pois", String.valueOf(result.pois())),
                    Placeholder.unparsed("properties", String.valueOf(result.properties())),
                    Placeholder.unparsed("organizations", String.valueOf(result.organizations())));
        }));
    }

    private void setupImport(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "setup.import-usage");
            return;
        }
        String name = args[2];
        // Dry run by default: the admin sees the effect before anything is written.
        boolean apply = args.length >= 4 && args[3].equalsIgnoreCase("apply");
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        setupBlueprintService.importFrom(setupBlueprintService.fileFor(name), apply, actor,
                        sender.getName())
                .whenComplete((result, error) -> onMain(() -> {
                    if (error != null) {
                        messages.send(sender, "setup.import-failed",
                                Placeholder.unparsed("detail", String.valueOf(error.getMessage())));
                        plugin.getLogger().warning("Setup import failed: " + error);
                        return;
                    }
                    messages.send(sender, apply ? "setup.import-applied" : "setup.import-preview",
                            Placeholder.unparsed("created", String.valueOf(result.created().size())),
                            Placeholder.unparsed("skipped", String.valueOf(result.skipped().size())),
                            Placeholder.unparsed("failed", String.valueOf(result.failed().size())));
                    for (SetupBlueprintService.Entry entry : result.failed()) {
                        messages.send(sender, "setup.import-entry-failed",
                                Placeholder.unparsed("kind", entry.kind()),
                                Placeholder.unparsed("name", entry.name()),
                                Placeholder.unparsed("detail", entry.note()));
                    }
                    if (!apply && !result.created().isEmpty()) {
                        messages.send(sender, "setup.import-confirm", Placeholder.component(
                                "command",
                                clickable(sender, "/afterlife setup import " + name + " apply")));
                    }
                }));
    }

    /** Async results land back on the server thread before touching Bukkit. */
    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * POI types an admin may register: the configured allow-list plus everything
     * an active module asks for, so a module can never need a type the command
     * rejects.
     */
    private Set<String> allowedPoiTypes() {
        Set<String> types = new LinkedHashSet<>(coreConfig.poiTypes());
        types.addAll(setupRegistry.requiredPoiTypes());
        return types;
    }

    private void licenseSetup(CommandSender sender, String[] args) {
        // /afterlife setup license <grant|revoke> <player> <type> [days]
        if (legalService == null || args.length < 5) {
            messages.send(sender, "legal.license-usage");
            return;
        }
        Player target = org.bukkit.Bukkit.getPlayerExact(args[3]);
        if (target == null) {
            messages.send(sender, "bank.target-not-found");
            return;
        }
        String type = args[4].toUpperCase(Locale.ROOT);
        java.util.UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        if (args[2].equalsIgnoreCase("grant")) {
            Integer days = null;
            if (args.length >= 6) {
                try {
                    days = Integer.parseInt(args[5]);
                } catch (NumberFormatException e) {
                    messages.send(sender, "legal.license-usage");
                    return;
                }
            }
            legalService.grantLicense(target.getUniqueId(), type, days, actor, sender.getName())
                    .thenAccept(id -> messages.send(sender, "legal.license-granted",
                            Placeholder.unparsed("type", type),
                            Placeholder.unparsed("player", target.getName())));
        } else if (args[2].equalsIgnoreCase("revoke")) {
            legalService.revokeLicense(target.getUniqueId(), type, actor, sender.getName())
                    .thenAccept(revoked -> messages.send(sender,
                            revoked ? "legal.license-revoked" : "legal.license-usage",
                            Placeholder.unparsed("type", type),
                            Placeholder.unparsed("player", target.getName())));
        } else {
            messages.send(sender, "legal.license-usage");
        }
    }

    private void propertySetup(CommandSender sender, String[] args) {
        // /afterlife setup property create <HOUSE|APARTMENT> <name> <price-euro> [region|-] [dirty]
        if (realEstateService == null || args.length < 6 || !args[2].equalsIgnoreCase("create")) {
            messages.send(sender, "estate.agenzia-usage");
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return;
        }
        String type = args[3].toUpperCase(Locale.ROOT);
        String name = args[4];
        Long priceCents = Money.parseWholeEuros(args[5]);
        if (priceCents == null) {
            messages.send(sender, "bank.invalid-amount");
            return;
        }
        String region = args.length >= 7 && !args[6].equals("-") ? args[6] : null;
        boolean dirty = args.length >= 8 && args[7].equalsIgnoreCase("dirty");
        if (region != null && integrationManager.worldGuard() != null
                && integrationManager.worldGuard().available()
                && !integrationManager.worldGuard().regionExists(player.getWorld(), region)) {
            messages.send(sender, "poi.region-not-found", Placeholder.unparsed("region", region));
            return;
        }
        var location = player.getLocation();
        realEstateService.createProperty(name, type, player.getWorld().getName(),
                        location.getX(), location.getY(), location.getZ(), region, priceCents, dirty,
                        player.getUniqueId(), player.getName())
                .whenComplete((property, error) -> {
                    if (error != null) {
                        messages.send(sender, "estate.not-available");
                        return;
                    }
                    messages.send(sender, "estate.list-entry",
                            Placeholder.unparsed("name", property.name()),
                            Placeholder.unparsed("type", property.type()),
                            Placeholder.unparsed("price", Money.format(property.price())),
                            Placeholder.unparsed("world", property.world()),
                            Placeholder.unparsed("x", String.valueOf((int) property.x())),
                            Placeholder.unparsed("z", String.valueOf((int) property.z())));
                });
    }

    private void orgSetup(CommandSender sender, String[] args) {
        // /afterlife setup org create <type> <name> [display name...]
        if (args.length < 5 || !args[2].equalsIgnoreCase("create")) {
            messages.send(sender, "org.usage");
            return;
        }
        String type = args[3].toUpperCase(Locale.ROOT);
        String name = args[4].toLowerCase(Locale.ROOT);
        String display = args.length > 5
                ? String.join(" ", List.of(args).subList(5, args.length))
                : args[4];
        java.util.UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        accountService.createOrganization(name, type, display, actor, sender.getName())
                .whenComplete((account, error) -> {
                    if (error != null) {
                        messages.send(sender, "general.internal-error");
                        plugin.getLogger().warning("Org create failed: " + error.getMessage());
                        return;
                    }
                    messages.send(sender, "org.created",
                            Placeholder.unparsed("name", display),
                            Placeholder.unparsed("iban", account.iban()));
                });
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

        if (!allowedPoiTypes().contains(type)) {
            messages.send(sender, "poi.invalid-type",
                    Placeholder.unparsed("types", String.join(", ", allowedPoiTypes())));
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
        for (String type : allowedPoiTypes()) {
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
        if (args.length >= 3 && args[1].equalsIgnoreCase("dirtymoney") && bankingService != null) {
            Long cents = Money.parseWholeEuros(args[2]);
            if (cents == null) {
                messages.send(sender, "bank.invalid-amount");
                return;
            }
            bankingService.issueDirty(player.getUniqueId(), cents, player.getUniqueId())
                    .thenAccept(notes -> databaseManager.db().onMain(() -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        notes.forEach(note -> player.getInventory()
                                .addItem(BankingItems.toStack(itemService, messages, note)).values()
                                .forEach(rest -> player.getWorld()
                                        .dropItemNaturally(player.getLocation(), rest)));
                        messages.send(player, "debug.dirty-issued",
                                Placeholder.unparsed("amount", Money.format(cents)),
                                Placeholder.unparsed("count", String.valueOf(notes.size())));
                    }));
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
            return filter(List.of("version", "health", "setup", "debug", "reconcile", "economy"),
                    args[0]);
        }
        if (args[0].equalsIgnoreCase("setup")) {
            return setupCompletions(args);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return filter(List.of("item", "dirtymoney"), args[1]);
        }
        return List.of();
    }

    private List<String> setupCompletions(String[] args) {
        if (args.length == 2) {
            return filter(List.of("status", "poi", "property", "org", "license",
                    "export", "import", "help"), args[1]);
        }
        String section = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3) {
            return switch (section) {
                case "poi" -> filter(List.of("create", "remove", "list", "report"), args[2]);
                case "property", "org" -> filter(List.of("create"), args[2]);
                case "license" -> filter(List.of("grant", "revoke"), args[2]);
                case "import" -> filter(setupBlueprintService.available(), args[2]);
                default -> List.of();
            };
        }
        if (args.length == 4) {
            if (section.equals("poi") && args[2].equalsIgnoreCase("create")) {
                return filter(List.copyOf(allowedPoiTypes()), args[3]);
            }
            if (section.equals("poi") && args[2].equalsIgnoreCase("remove")) {
                return filter(poiService.all().stream().map(Poi::name).toList(), args[3]);
            }
            if (section.equals("property") && args[2].equalsIgnoreCase("create")) {
                return filter(List.of("HOUSE", "APARTMENT"), args[3]);
            }
            if (section.equals("license")) {
                return TabComplete.players(args[3]);
            }
            if (section.equals("import")) {
                return filter(List.of("apply"), args[3]);
            }
        }
        // License types are free-form RP labels (master plan §6.3); these are the
        // conventional ones, and any other string is accepted.
        if (args.length == 5 && section.equals("license")) {
            return filter(List.of("MEDICAL", "FIREARM", "DRIVER", "LAW_DEGREE", "BUSINESS"),
                    args[4]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
