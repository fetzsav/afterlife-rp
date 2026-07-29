package com.afterlife.rp.module.ems;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.missions.JobSessionService;
import com.afterlife.rp.shared.regions.Poi;
import com.afterlife.rp.shared.regions.PoiService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import com.afterlife.rp.command.TabComplete;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.jetbrains.annotations.NotNull;

/**
 * /ems <turno|fine|scan|cura|produci|traccia|certificato|manuale|emergenza|
 * cura_npc|estrai|converti> (§9.8). Alias /medico.
 */
public final class EmsCommands implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "afterlife.ems.medic";

    private final DatabaseManager databaseManager;
    private final EmsService service;
    private final EmsRuntime runtime;
    private final EmsListener listener;
    private final JobSessionService jobSessions;
    private final AccountService accountService;
    private final SerializedItemService itemService;
    private final PoiService poiService;
    private final Messages messages;

    public EmsCommands(
            DatabaseManager databaseManager,
            EmsService service,
            EmsRuntime runtime,
            EmsListener listener,
            JobSessionService jobSessions,
            AccountService accountService,
            SerializedItemService itemService,
            PoiService poiService,
            Messages messages) {
        this.databaseManager = databaseManager;
        this.service = service;
        this.runtime = runtime;
        this.listener = listener;
        this.jobSessions = jobSessions;
        this.accountService = accountService;
        this.itemService = itemService;
        this.poiService = poiService;
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
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        // /ems manuale is available to everyone; the rest is medic-only.
        if (sub.equals("manuale")) {
            giveManual(player);
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            messages.send(player, "general.no-permission");
            return true;
        }
        switch (sub) {
            case "turno" -> jobSessions.start(player.getUniqueId(), EmsService.JOB)
                    .thenAccept(started -> messages.send(player,
                            started ? "ems.duty-on" : "ems.duty-already"));
            case "fine" -> jobSessions.end(player.getUniqueId(), EmsService.JOB)
                    .thenAccept(ended -> messages.send(player,
                            ended ? "ems.duty-off" : "ems.duty-not-on"));
            case "scan" -> scan(player, args);
            case "cura" -> treat(player, args);
            case "produci" -> produce(player, args);
            case "traccia" -> trace(player);
            case "certificato" -> certificate(player, args);
            case "emergenza" -> runtime.claimEmergency(player);
            case "cura_npc" -> runtime.treatEmergencyNpc(player);
            case "estrai" -> extract(player);
            case "converti" -> convert(player);
            default -> messages.send(player, "ems.usage");
        }
        return true;
    }

    private Player target(Player medic, String[] args) {
        if (args.length < 2) {
            messages.send(medic, "ems.usage");
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(medic, "bank.target-not-found");
            return null;
        }
        return target;
    }

    private void scan(Player medic, String[] args) {
        Player patient = target(medic, args);
        if (patient == null) {
            return;
        }
        boolean hasScanner = holdsType(medic, "scanner");
        if (!hasScanner) {
            messages.send(medic, "ems.scanner-required");
            return;
        }
        service.activeInjuries(patient.getUniqueId()).thenAccept(injuries -> {
            messages.send(medic, "ems.scan-header",
                    Placeholder.unparsed("player", patient.getName()),
                    Placeholder.unparsed("count", String.valueOf(injuries.size())));
            for (EmsService.Injury injury : injuries) {
                String nextTool = service.config().nextTool(injury.type(), injury.step());
                messages.send(medic, "ems.scan-entry",
                        Placeholder.unparsed("type", injury.type()),
                        Placeholder.unparsed("severity", String.valueOf(injury.severity())),
                        Placeholder.unparsed("step", String.valueOf(injury.step())),
                        Placeholder.unparsed("total",
                                String.valueOf(service.config().sequenceLength(injury.type()))),
                        Placeholder.unparsed("tool", nextTool == null ? "-" : nextTool));
            }
        });
    }

    private void treat(Player medic, String[] args) {
        Player patient = target(medic, args);
        if (patient == null) {
            return;
        }
        if (patient.getLocation().distanceSquared(medic.getLocation()) > 16) {
            messages.send(medic, "ems.too-far");
            return;
        }
        var held = itemService.readVerified(medic.getInventory().getItemInMainHand()).orElse(null);
        if (held == null) {
            messages.send(medic, "ems.tool-required");
            return;
        }
        var patientAccount = accountService.cachedPersonal(patient.getUniqueId()).orElse(null);
        var medicAccount = accountService.cachedPersonal(medic.getUniqueId()).orElse(null);
        if (patientAccount == null || medicAccount == null) {
            messages.send(medic, "general.db-unavailable");
            return;
        }
        service.treat(medic.getUniqueId(), patient.getUniqueId(), patientAccount.id(),
                        medicAccount.id(), held.itemType(), held.serial())
                .thenAccept(result -> databaseManager.db().onMain(() -> {
                    if (!medic.isOnline()) {
                        return;
                    }
                    switch (result.status()) {
                        case STEP_DONE -> {
                            consumeIfNeeded(medic, result.consumedBatchSerial());
                            messages.send(medic, "ems.treat-step",
                                    Placeholder.unparsed("type", result.injuryType()),
                                    Placeholder.unparsed("tool",
                                            result.nextTool() == null ? "-" : result.nextTool()));
                            messages.send(patient, "ems.treat-step-patient",
                                    Placeholder.unparsed("type", result.injuryType()));
                        }
                        case HEALED -> {
                            consumeIfNeeded(medic, result.consumedBatchSerial());
                            listener.refresh(patient.getUniqueId());
                            messages.send(medic, "ems.treat-healed",
                                    Placeholder.unparsed("type", result.injuryType()));
                            messages.send(patient, "ems.treat-healed-patient",
                                    Placeholder.unparsed("type", result.injuryType()));
                        }
                        case WRONG_TOOL -> messages.send(medic, "ems.wrong-tool",
                                Placeholder.unparsed("type",
                                        result.injuryType() == null ? "-" : result.injuryType()),
                                Placeholder.unparsed("tool",
                                        result.nextTool() == null ? "-" : result.nextTool()));
                        case NO_INJURY -> messages.send(medic, "ems.no-injuries");
                        case PAYMENT_FAILED -> messages.send(medic, "bank.insufficient-funds");
                        default -> messages.send(medic, "ems.race-lost");
                    }
                }));
    }

    private void consumeIfNeeded(Player medic, UUID consumedSerial) {
        if (consumedSerial == null) {
            return;
        }
        var held = medic.getInventory().getItemInMainHand();
        var data = itemService.readVerified(held);
        if (data.isPresent() && data.get().serial().equals(consumedSerial)) {
            held.setAmount(held.getAmount() - 1);
        }
    }

    private void produce(Player medic, String[] args) {
        if (args.length < 2) {
            messages.send(medic, "ems.usage");
            return;
        }
        String medicineType = args[1].toLowerCase(Locale.ROOT);
        if (!nearPoiOfType(medic, service.config().workstationPoiTypes())) {
            messages.send(medic, "ems.workstation-required");
            return;
        }
        var account = accountService.cachedPersonal(medic.getUniqueId()).orElse(null);
        if (account == null) {
            messages.send(medic, "general.db-unavailable");
            return;
        }
        service.produce(medic.getUniqueId(), medic.getName(), account.id(), medicineType,
                        false, null)
                .thenAccept(produced -> databaseManager.db().onMain(() -> {
                    if (!medic.isOnline()) {
                        return;
                    }
                    if (produced.isEmpty()) {
                        messages.send(medic, "ems.produce-failed");
                        return;
                    }
                    give(medic, EmsItems.toStack(itemService, produced.get().item()));
                    messages.send(medic, "ems.produced",
                            Placeholder.unparsed("type", medicineType),
                            Placeholder.unparsed("batch", produced.get().batchCode()));
                }));
    }

    private void trace(Player player) {
        var held = itemService.readVerified(player.getInventory().getItemInMainHand()).orElse(null);
        if (held == null) {
            messages.send(player, "ems.tool-required");
            return;
        }
        databaseManager.db()
                .supply(connection -> new com.afterlife.rp.shared.items.SerializedItemRepository()
                        .find(connection, held.serial()))
                .thenAccept(record -> {
                    String code = record.map(r -> service.batchCodeFromMetadata(r.metadata()))
                            .orElse(null);
                    if (code == null) {
                        databaseManager.db().onMain(() ->
                                messages.send(player, "ems.trace-none"));
                        return;
                    }
                    service.traceBatch(code).thenAccept(info ->
                            databaseManager.db().onMain(() -> info.ifPresentOrElse(batch ->
                                    messages.send(player, "ems.trace-info",
                                            Placeholder.unparsed("code", batch.code()),
                                            Placeholder.unparsed("type", batch.medicineType()),
                                            Placeholder.unparsed("legality", batch.legality()),
                                            Placeholder.unparsed("date", batch.createdAt())),
                                    () -> messages.send(player, "ems.trace-none"))));
                });
    }

    private void certificate(Player medic, String[] args) {
        Player patient = target(medic, args);
        if (patient == null) {
            return;
        }
        var patientAccount = accountService.cachedPersonal(patient.getUniqueId()).orElse(null);
        var medicAccount = accountService.cachedPersonal(medic.getUniqueId()).orElse(null);
        if (patientAccount == null || medicAccount == null) {
            messages.send(medic, "general.db-unavailable");
            return;
        }
        service.issueCertificate(medic.getUniqueId(), medic.getName(), medicAccount.id(),
                        patient.getUniqueId(), patientAccount.id())
                .thenAccept(result -> databaseManager.db().onMain(() -> {
                    if (!medic.isOnline()) {
                        return;
                    }
                    switch (result.status()) {
                        case ISSUED -> {
                            give(patient, EmsItems.toStack(itemService, result.item()));
                            messages.send(medic, "ems.certificate-issued",
                                    Placeholder.unparsed("player", patient.getName()));
                            messages.send(patient, "ems.certificate-received");
                        }
                        case PATIENT_INJURED -> messages.send(medic, "ems.certificate-injured");
                        default -> messages.send(medic, "bank.insufficient-funds");
                    }
                }));
    }

    private void extract(Player medic) {
        if (!holdsType(medic, "extraction_syringe")) {
            messages.send(medic, "ems.syringe-required");
            return;
        }
        Poi barrel = nearestPoiOfType(medic, service.config().toxicBarrelPoiTypes());
        if (barrel == null) {
            messages.send(medic, "ems.barrel-required");
            return;
        }
        int duration = service.config().toxicDurationSecondsMin()
                + new java.security.SecureRandom().nextInt(
                        1 + service.config().toxicDurationSecondsMax()
                                - service.config().toxicDurationSecondsMin());
        service.startExtraction(medic.getUniqueId(), barrel.id(), duration)
                .thenAccept(mission -> databaseManager.db().onMain(() -> {
                    if (mission.isEmpty()) {
                        messages.send(medic, "ems.extraction-busy");
                        return;
                    }
                    messages.send(medic, "ems.extraction-started",
                            Placeholder.unparsed("seconds", String.valueOf(duration)));
                    runtime.runExtraction(medic, mission.get(), barrel);
                }));
    }

    private void convert(Player medic) {
        var held = itemService.readVerified(medic.getInventory().getItemInMainHand())
                .filter(data -> EmsService.ITEM_CHEMICAL.equals(data.itemType()))
                .orElse(null);
        if (held == null) {
            messages.send(medic, "ems.chemical-required");
            return;
        }
        Poi workstation = nearestPoiOfType(medic, service.config().toxicWorkstationPoiTypes());
        if (workstation == null) {
            messages.send(medic, "ems.workstation-required");
            return;
        }
        service.convertChemical(medic.getUniqueId(), medic.getName(), held.serial(),
                        workstation.id())
                .thenAccept(produced -> databaseManager.db().onMain(() -> {
                    if (!medic.isOnline()) {
                        return;
                    }
                    if (produced.isEmpty()) {
                        messages.send(medic, "ems.chemical-required");
                        return;
                    }
                    var hand = medic.getInventory().getItemInMainHand();
                    var handData = itemService.readVerified(hand);
                    if (handData.isPresent() && handData.get().serial().equals(held.serial())) {
                        medic.getInventory().setItemInMainHand(null);
                    }
                    give(medic, EmsItems.toStack(itemService, produced.get().item()));
                    messages.send(medic, "ems.converted",
                            Placeholder.unparsed("batch", produced.get().batchCode()));
                }));
    }

    private void giveManual(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("AfterLifeEMS");
        meta.setAuthor("Ospedale AfterLife");
        meta.addPages(
                Component.text("""
                        AfterLifeEMS — Manuale del soccorritore

                        Ferite comuni: emorragie, tagli, fratture, dolori, proiettili.

                        Lo scanner medico rivela cosa affligge il paziente e quale strumento serve."""),
                Component.text("""
                        Ogni cura è una sequenza precisa: strumento sbagliato, nessun progresso.

                        Le medicine portano un numero di lotto: l'ospedale sa sempre chi le ha prodotte."""),
                Component.text("""
                        Il certificato medico si ottiene solo da un medico, dopo le visite di rito, e ha una scadenza.

                        In caso di emergenza, un medico di turno arriverà sul posto."""));
        book.setItemMeta(meta);
        give(player, book);
        messages.send(player, "ems.manual-given");
    }

    // --- helpers ---

    private boolean holdsType(Player player, String itemType) {
        return itemService.readVerified(player.getInventory().getItemInMainHand())
                .filter(data -> itemType.equals(data.itemType()))
                .isPresent();
    }

    private boolean nearPoiOfType(Player player, List<String> types) {
        return nearestPoiOfType(player, types) != null;
    }

    private Poi nearestPoiOfType(Player player, List<String> types) {
        for (Poi poi : poiService.byTypeAndStatus(types, "ACTIVE")) {
            var location = poi.location();
            if (location != null && poi.world().equals(player.getWorld().getName())
                    && location.distanceSquared(player.getLocation()) <= 16) {
                return poi;
            }
        }
        return null;
    }

    private void give(Player player, ItemStack stack) {
        player.getInventory().addItem(stack).values().forEach(rest ->
                player.getWorld().dropItemNaturally(player.getLocation(), rest));
    }

    @Override
    public java.util.List<String> onTabComplete(@org.jetbrains.annotations.NotNull CommandSender sender,
            @org.jetbrains.annotations.NotNull Command command,
            @org.jetbrains.annotations.NotNull String alias, String @org.jetbrains.annotations.NotNull [] args) {
        if (args.length == 1) {
            return TabComplete.filter(java.util.List.of("turno", "fine", "scan", "cura", "produci",
                    "traccia", "certificato", "manuale", "emergenza", "cura_npc", "estrai",
                    "converti"), args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(java.util.Locale.ROOT);
            if (sub.equals("scan") || sub.equals("cura") || sub.equals("certificato")) {
                return TabComplete.players(args[1]);
            }
            if (sub.equals("produci")) {
                return TabComplete.filter(java.util.List.of("bandage", "splint", "medkit",
                        "adrenaline"), args[1]);
            }
        }
        return java.util.List.of();
    }

}
