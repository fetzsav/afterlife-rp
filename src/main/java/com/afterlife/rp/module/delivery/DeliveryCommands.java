package com.afterlife.rp.module.delivery;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.module.banking.BankingItems;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.Money;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.missions.JobSessionService;
import com.afterlife.rp.shared.missions.Mission;
import com.afterlife.rp.shared.missions.MissionService;
import com.afterlife.rp.shared.regions.Poi;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import com.afterlife.rp.command.TabComplete;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/** /rider <turno|fine|ordine|ritira|consegna|accetta_pacco> (§9.6). */
public final class DeliveryCommands implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "afterlife.delivery.driver";

    private final DatabaseManager databaseManager;
    private final DeliveryService service;
    private final MissionService missionService;
    private final JobSessionService jobSessions;
    private final AccountService accountService;
    private final SerializedItemService itemService;
    private final Messages messages;

    public DeliveryCommands(
            DatabaseManager databaseManager,
            DeliveryService service,
            MissionService missionService,
            JobSessionService jobSessions,
            AccountService accountService,
            SerializedItemService itemService,
            Messages messages) {
        this.databaseManager = databaseManager;
        this.service = service;
        this.missionService = missionService;
        this.jobSessions = jobSessions;
        this.accountService = accountService;
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
        if (!player.hasPermission(PERMISSION)) {
            messages.send(player, "general.no-permission");
            return true;
        }
        if (!databaseManager.ready()) {
            messages.send(player, "general.db-unavailable");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "turno" -> jobSessions.start(player.getUniqueId(), DeliveryService.JOB)
                    .thenAccept(started -> messages.send(player,
                            started ? "delivery.duty-on" : "delivery.duty-already"));
            case "fine" -> jobSessions.end(player.getUniqueId(), DeliveryService.JOB)
                    .thenAccept(ended -> messages.send(player,
                            ended ? "delivery.duty-off" : "delivery.duty-not-on"));
            case "ordine" -> order(player);
            case "ritira" -> pickup(player);
            case "consegna" -> deliver(player);
            case "accetta_pacco" -> acceptContraband(player);
            default -> messages.send(player, "delivery.usage");
        }
        return true;
    }

    private boolean requireOnDuty(Player player) {
        if (!jobSessions.isOnDuty(player.getUniqueId(), DeliveryService.JOB)) {
            messages.send(player, "delivery.duty-required");
            return false;
        }
        return true;
    }

    private void order(Player player) {
        if (!requireOnDuty(player)) {
            return;
        }
        service.startOrder(player.getUniqueId()).thenAccept(mission ->
                databaseManager.db().onMain(() -> {
                    if (mission.isEmpty()) {
                        messages.send(player, "delivery.order-unavailable");
                        return;
                    }
                    Poi restaurant = service.poiById(mission.get().targetPoiId());
                    messages.send(player, "delivery.order-assigned",
                            Placeholder.unparsed("restaurant",
                                    restaurant == null ? "?" : restaurant.name()));
                }));
    }

    private void pickup(Player player) {
        if (!requireOnDuty(player)) {
            return;
        }
        Mission mission = missionService
                .cachedActiveOfType(player.getUniqueId(), DeliveryService.MISSION_FOOD).orElse(null);
        if (mission == null || !"PICKUP".equals(mission.dataString("phase"))) {
            messages.send(player, "delivery.no-pickup");
            return;
        }
        Poi restaurant = service.poiById(mission.targetPoiId());
        if (!near(player, restaurant)) {
            messages.send(player, "delivery.too-far");
            return;
        }
        service.pickup(mission).thenAccept(pack -> databaseManager.db().onMain(() -> {
            if (pack.isEmpty() || !player.isOnline()) {
                return;
            }
            givePackage(player, pack.get(), false);
            Poi dropoff = service.poiById(UUID.fromString(mission.dataString("dropoff")));
            messages.send(player, "delivery.picked-up",
                    Placeholder.unparsed("destination", dropoff == null ? "?" : dropoff.name()));
        }));
    }

    private void deliver(Player player) {
        if (!requireOnDuty(player)) {
            return;
        }
        Mission contraband = missionService
                .cachedActiveOfType(player.getUniqueId(), DeliveryService.MISSION_CONTRABAND)
                .orElse(null);
        if (contraband != null && deliverContrabandIfNear(player, contraband)) {
            return;
        }
        Mission food = missionService
                .cachedActiveOfType(player.getUniqueId(), DeliveryService.MISSION_FOOD).orElse(null);
        if (food == null || !"DELIVER".equals(food.dataString("phase"))) {
            messages.send(player, "delivery.no-delivery");
            return;
        }
        Poi dropoff = service.poiById(UUID.fromString(food.dataString("dropoff")));
        if (!near(player, dropoff)) {
            messages.send(player, "delivery.too-far");
            return;
        }
        if (!holdingPackage(player, food, DeliveryService.ITEM_FOOD_PACKAGE)) {
            messages.send(player, "delivery.package-required");
            return;
        }
        var account = accountService.cachedPersonal(player.getUniqueId()).orElse(null);
        if (account == null) {
            messages.send(player, "general.db-unavailable");
            return;
        }
        service.deliverFood(food, account.id()).thenAccept(outcome ->
                databaseManager.db().onMain(() -> {
                    if (!player.isOnline() || !outcome.rewarded()) {
                        return;
                    }
                    removePackage(player, food);
                    messages.send(player, "delivery.delivered",
                            Placeholder.unparsed("reward", Money.format(outcome.rewardCents())));
                    if (outcome.contrabandOffered()) {
                        messages.send(player, "delivery.contraband-offer",
                                Placeholder.unparsed("seconds", String.valueOf(
                                        service.config().contrabandWindowSeconds())));
                    }
                }));
    }

    private boolean deliverContrabandIfNear(Player player, Mission mission) {
        Poi shadow = service.poiById(mission.targetPoiId());
        if (!near(player, shadow)) {
            return false;
        }
        if (!holdingPackage(player, mission, DeliveryService.ITEM_CONTRABAND_PACKAGE)) {
            messages.send(player, "delivery.package-required");
            return true;
        }
        service.deliverContraband(mission).thenAccept(outcome ->
                databaseManager.db().onMain(() -> {
                    if (!player.isOnline() || !outcome.rewarded()) {
                        return;
                    }
                    removePackage(player, mission);
                    for (SerializedItem note : outcome.notes()) {
                        player.getInventory().addItem(BankingItems.toStack(itemService, messages, note))
                                .values().forEach(rest -> player.getWorld()
                                        .dropItemNaturally(player.getLocation(), rest));
                    }
                    messages.send(player, "delivery.contraband-delivered",
                            Placeholder.unparsed("amount", Money.format(outcome.dirtyCents())));
                }));
        return true;
    }

    private void acceptContraband(Player player) {
        if (!requireOnDuty(player)) {
            return;
        }
        service.acceptContraband(player.getUniqueId()).thenAccept(pack ->
                databaseManager.db().onMain(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (pack.isEmpty()) {
                        messages.send(player, "delivery.contraband-none");
                        return;
                    }
                    givePackage(player, pack.get(), true);
                    messages.send(player, "delivery.contraband-accepted");
                }));
    }

    // --- helpers ---

    private boolean near(Player player, Poi poi) {
        return poi != null && poi.location() != null
                && poi.world().equals(player.getWorld().getName())
                && poi.location().distanceSquared(player.getLocation()) <= 9;
    }

    /** The held item must be THIS mission's package: exact serial match (rule 15). */
    private boolean holdingPackage(Player player, Mission mission, String itemType) {
        String expectedSerial = mission.dataString("package_serial");
        if (expectedSerial == null) {
            return false;
        }
        return itemService.readVerified(player.getInventory().getItemInMainHand())
                .filter(data -> itemType.equals(data.itemType()))
                .filter(data -> data.serial().toString().equals(expectedSerial))
                .isPresent();
    }

    private void removePackage(Player player, Mission mission) {
        String serial = mission.dataString("package_serial");
        if (serial == null) {
            return;
        }
        var contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null) {
                continue;
            }
            var data = itemService.readVerified(stack);
            if (data.isPresent() && data.get().serial().toString().equals(serial)) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    private void givePackage(Player player, SerializedItem pack, boolean contraband) {
        ItemStack stack = itemService.toItemStack(pack,
                contraband ? Material.BARREL : Material.CHEST,
                Component.text(contraband ? "Pacco sigillato" : "Ordine da consegnare",
                                contraband ? NamedTextColor.DARK_RED : NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false));
        player.getInventory().addItem(stack).values().forEach(rest ->
                player.getWorld().dropItemNaturally(player.getLocation(), rest));
    }

    @Override
    public java.util.List<String> onTabComplete(@org.jetbrains.annotations.NotNull CommandSender sender,
            @org.jetbrains.annotations.NotNull Command command,
            @org.jetbrains.annotations.NotNull String alias, String @org.jetbrains.annotations.NotNull [] args) {
        if (args.length == 1) {
            return TabComplete.filter(java.util.List.of("turno", "fine", "ordine", "ritira",
                    "consegna", "accetta_pacco"), args[0]);
        }
        return java.util.List.of();
    }

}
