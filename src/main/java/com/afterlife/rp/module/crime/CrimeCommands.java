package com.afterlife.rp.module.crime;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.module.banking.BankingItems;
import com.afterlife.rp.module.police.PoliceService;
import com.afterlife.rp.shared.economy.Money;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.missions.JobSessionService;
import com.afterlife.rp.shared.regions.Poi;
import com.afterlife.rp.shared.regions.PoiService;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/** /gang <turno|fine|vendi|sigilla|apri|hackera> (§9.4). */
public final class CrimeCommands implements CommandExecutor {

    private static final String PERM_GANG = "afterlife.crime.gang";

    private final DatabaseManager databaseManager;
    private final CrimeService service;
    private final CrimeRuntime runtime;
    private final PoliceService policeService;
    private final JobSessionService jobSessions;
    private final SerializedItemService itemService;
    private final PoiService poiService;
    private final Messages messages;

    public CrimeCommands(DatabaseManager databaseManager, CrimeService service, CrimeRuntime runtime,
            PoliceService policeService, JobSessionService jobSessions,
            SerializedItemService itemService, PoiService poiService, Messages messages) {
        this.databaseManager = databaseManager;
        this.service = service;
        this.runtime = runtime;
        this.policeService = policeService;
        this.jobSessions = jobSessions;
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
        if (!player.hasPermission(PERM_GANG)) {
            messages.send(player, "general.no-permission");
            return true;
        }
        if (!databaseManager.ready()) {
            messages.send(player, "general.db-unavailable");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "turno" -> jobSessions.start(player.getUniqueId(), CrimeService.JOB_GANG)
                    .thenAccept(started -> messages.send(player,
                            started ? "crime.duty-on" : "crime.duty-already"));
            case "fine" -> jobSessions.end(player.getUniqueId(), CrimeService.JOB_GANG)
                    .thenAccept(ended -> messages.send(player,
                            ended ? "crime.duty-off" : "crime.duty-not-on"));
            case "vendi" -> sell(player);
            case "sigilla" -> seal(player);
            case "apri" -> unseal(player);
            case "costruisci" -> buildDevice(player);
            case "hackera" -> hack(player);
            default -> messages.send(player, "crime.usage");
        }
        return true;
    }

    private void sell(Player player) {
        if (!jobSessions.isOnDuty(player.getUniqueId(), CrimeService.JOB_GANG)) {
            messages.send(player, "crime.duty-required");
            return;
        }
        if (!runtime.inSaleZone(player)) {
            messages.send(player, "crime.no-zone");
            return;
        }
        var held = itemService.readVerified(player.getInventory().getItemInMainHand())
                .filter(d -> CrimeService.ITEM_DRUG.equals(d.itemType()))
                .orElse(null);
        if (held == null) {
            messages.send(player, "crime.dose-required");
            return;
        }
        Poi zone = runtime.nearestSaleZone(player);
        service.sellDose(player.getUniqueId(), held.serial()).thenAccept(result ->
                databaseManager.db().onMain(() -> {
                    if (!player.isOnline() || !result.sold()) {
                        return;
                    }
                    var hand = player.getInventory().getItemInMainHand();
                    var handData = itemService.readVerified(hand);
                    if (handData.isPresent() && handData.get().serial().equals(held.serial())) {
                        hand.setAmount(hand.getAmount() - 1);
                    }
                    for (SerializedItem note : result.notes()) {
                        give(player, BankingItems.toStack(itemService, note));
                    }
                    messages.send(player, "crime.sold",
                            Placeholder.unparsed("amount", Money.format(result.dirtyCents())));
                    if (result.suspicion() && zone != null) {
                        policeService.raiseAlert("DRUG_SALE", district(zone), zone.world(),
                                "street_sale");
                    }
                }));
    }

    private void seal(Player player) {
        var held = itemService.readVerified(player.getInventory().getItemInMainHand())
                .filter(d -> CrimeService.ITEM_DRUG.equals(d.itemType()))
                .orElse(null);
        if (held == null) {
            messages.send(player, "crime.dose-required");
            return;
        }
        service.seal(player.getUniqueId(), held.serial()).thenAccept(bag ->
                databaseManager.db().onMain(() -> {
                    if (!player.isOnline() || bag.isEmpty()) {
                        messages.send(player, "crime.seal-failed");
                        return;
                    }
                    consumeHeld(player, held.serial());
                    give(player, CrimeItems.toStack(itemService, bag.get()));
                    messages.send(player, "crime.sealed");
                }));
    }

    private void unseal(Player player) {
        var held = itemService.readVerified(player.getInventory().getItemInMainHand())
                .filter(d -> CrimeService.ITEM_SEALED.equals(d.itemType()))
                .orElse(null);
        if (held == null) {
            messages.send(player, "crime.bag-required");
            return;
        }
        service.unseal(player.getUniqueId(), held.serial()).thenAccept(drug ->
                databaseManager.db().onMain(() -> {
                    if (!player.isOnline() || drug.isEmpty()) {
                        return;
                    }
                    consumeHeld(player, held.serial());
                    give(player, CrimeItems.toStack(itemService, drug.get()));
                    messages.send(player, "crime.unsealed");
                }));
    }

    private void buildDevice(Player player) {
        var held = itemService.readVerified(player.getInventory().getItemInMainHand())
                .filter(d -> com.afterlife.rp.module.electrician.ElectricianService
                        .ITEM_TYPE_CIRCUIT_BOARD.equals(d.itemType()))
                .orElse(null);
        if (held == null) {
            messages.send(player, "crime.board-required");
            return;
        }
        service.buildHackDevice(player.getUniqueId(), held.serial()).thenAccept(device ->
                databaseManager.db().onMain(() -> {
                    if (!player.isOnline() || device.isEmpty()) {
                        messages.send(player, "crime.board-required");
                        return;
                    }
                    consumeHeld(player, held.serial());
                    give(player, CrimeItems.toStack(itemService, device.get()));
                    messages.send(player, "crime.device-built");
                }));
    }

    private void hack(Player player) {
        var held = itemService.readVerified(player.getInventory().getItemInMainHand())
                .filter(d -> CrimeService.ITEM_HACK_DEVICE.equals(d.itemType()))
                .orElse(null);
        if (held == null) {
            messages.send(player, "crime.device-required");
            return;
        }
        Poi atm = nearestPoi(player, service.config().atmPoiTypes());
        if (atm == null) {
            messages.send(player, "crime.no-atm");
            return;
        }
        service.startHack(player.getUniqueId(), atm.id(), held.serial()).thenAccept(mission ->
                databaseManager.db().onMain(() -> {
                    if (!player.isOnline() || mission.isEmpty()) {
                        messages.send(player, "crime.hack-busy");
                        return;
                    }
                    consumeHeld(player, held.serial());
                    messages.send(player, "crime.hack-started",
                            Placeholder.unparsed("seconds",
                                    String.valueOf(service.config().hackChannelSeconds())));
                    runtime.runHack(player, mission.get(), atm);
                }));
    }

    private Poi nearestPoi(Player player, java.util.List<String> types) {
        for (Poi poi : poiService.byTypeAndStatus(types, "ACTIVE")) {
            var location = poi.location();
            if (location != null && poi.world().equals(player.getWorld().getName())
                    && location.distanceSquared(player.getLocation()) <= 9) {
                return poi;
            }
        }
        return null;
    }

    private String district(Poi poi) {
        if (poi.regionId() != null && !poi.regionId().isBlank()) {
            return poi.regionId();
        }
        return "zona " + (((int) poi.x()) / 100 * 100) + "," + (((int) poi.z()) / 100 * 100);
    }

    private void consumeHeld(Player player, java.util.UUID serial) {
        var hand = player.getInventory().getItemInMainHand();
        var data = itemService.readVerified(hand);
        if (data.isPresent() && data.get().serial().equals(serial)) {
            hand.setAmount(hand.getAmount() - 1);
        }
    }

    private void give(Player player, ItemStack stack) {
        player.getInventory().addItem(stack).values().forEach(rest ->
                player.getWorld().dropItemNaturally(player.getLocation(), rest));
    }
}
