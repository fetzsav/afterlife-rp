package com.afterlife.rp.module.electrician;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.Money;
import com.afterlife.rp.shared.gui.GuiButton;
import com.afterlife.rp.shared.gui.GuiManager;
import com.afterlife.rp.shared.gui.GuiMenu;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.missions.JobSessionService;
import com.afterlife.rp.shared.missions.Mission;
import com.afterlife.rp.shared.missions.MissionService;
import com.afterlife.rp.shared.regions.Poi;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/** /elettricista <turno|fine|chiamate|accetta <poi>|ripara> (§9.5). */
public final class ElectricianCommands implements CommandExecutor {

    private static final String PERMISSION = "afterlife.electrician.worker";
    private static final List<Integer> MINIGAME_SLOTS = List.of(10, 12, 14, 16, 28, 30, 32, 34);

    private final DatabaseManager databaseManager;
    private final ElectricianService service;
    private final MissionService missionService;
    private final JobSessionService jobSessions;
    private final AccountService accountService;
    private final SerializedItemService itemService;
    private final GuiManager guiManager;
    private final Messages messages;
    private final SecureRandom random = new SecureRandom();

    public ElectricianCommands(
            DatabaseManager databaseManager,
            ElectricianService service,
            MissionService missionService,
            JobSessionService jobSessions,
            AccountService accountService,
            SerializedItemService itemService,
            GuiManager guiManager,
            Messages messages) {
        this.databaseManager = databaseManager;
        this.service = service;
        this.missionService = missionService;
        this.jobSessions = jobSessions;
        this.accountService = accountService;
        this.itemService = itemService;
        this.guiManager = guiManager;
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
            case "turno" -> jobSessions.start(player.getUniqueId(), ElectricianService.JOB)
                    .thenAccept(started -> messages.send(player,
                            started ? "electrician.duty-on" : "electrician.duty-already"));
            case "fine" -> jobSessions.end(player.getUniqueId(), ElectricianService.JOB)
                    .thenAccept(ended -> messages.send(player,
                            ended ? "electrician.duty-off" : "electrician.duty-not-on"));
            case "chiamate" -> listCalls(player);
            case "accetta" -> accept(player, args);
            case "ripara" -> repair(player);
            default -> messages.send(player, "electrician.usage");
        }
        return true;
    }

    private boolean requireOnDuty(Player player) {
        if (!jobSessions.isOnDuty(player.getUniqueId(), ElectricianService.JOB)) {
            messages.send(player, "electrician.duty-required");
            return false;
        }
        return true;
    }

    private void listCalls(Player player) {
        if (!requireOnDuty(player)) {
            return;
        }
        List<Poi> calls = service.openCalls();
        messages.send(player, "electrician.calls-header",
                Placeholder.unparsed("count", String.valueOf(calls.size())));
        for (Poi poi : calls) {
            messages.send(player, "electrician.calls-entry",
                    Placeholder.unparsed("name", poi.name()),
                    Placeholder.unparsed("type", poi.type()),
                    Placeholder.unparsed("world", poi.world()),
                    Placeholder.unparsed("x", String.valueOf((int) poi.x())),
                    Placeholder.unparsed("z", String.valueOf((int) poi.z())));
        }
    }

    private void accept(Player player, String[] args) {
        if (!requireOnDuty(player)) {
            return;
        }
        if (args.length != 2) {
            messages.send(player, "electrician.usage");
            return;
        }
        Poi poi = service.openCalls().stream()
                .filter(p -> p.name().equalsIgnoreCase(args[1]))
                .findFirst().orElse(null);
        if (poi == null) {
            messages.send(player, "electrician.call-not-found");
            return;
        }
        service.accept(player.getUniqueId(), poi).thenAccept(mission ->
                databaseManager.db().onMain(() -> messages.send(player,
                        mission.isPresent() ? "electrician.call-accepted" : "electrician.call-taken",
                        Placeholder.unparsed("name", poi.name()))));
    }

    private void repair(Player player) {
        if (!requireOnDuty(player)) {
            return;
        }
        Mission mission = missionService
                .cachedActiveOfType(player.getUniqueId(), ElectricianService.MISSION_TYPE)
                .orElse(null);
        if (mission == null) {
            messages.send(player, "electrician.no-mission");
            return;
        }
        Poi target = mission.targetPoiId() == null
                ? null
                : service.poiById(mission.targetPoiId());
        if (target == null || target.location() == null
                || !target.world().equals(player.getWorld().getName())
                || target.location().distanceSquared(player.getLocation()) > 9) {
            messages.send(player, "electrician.too-far");
            return;
        }
        boolean hasToolKit = false;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && itemService.readVerified(stack)
                    .filter(d -> ElectricianService.ITEM_TYPE_TOOL_KIT.equals(d.itemType()))
                    .isPresent()) {
                hasToolKit = true;
                break;
            }
        }
        if (!hasToolKit) {
            messages.send(player, "electrician.toolkit-required");
            return;
        }
        openWiringMinigame(player, mission);
    }

    private void openWiringMinigame(Player player, Mission mission) {
        WiringSequence sequence = WiringSequence.shuffle(
                service.config().minigameFuses(), MINIGAME_SLOTS, random);
        Map<Integer, GuiButton> buttons = new HashMap<>();
        for (int i = 0; i < sequence.slotOrder().size(); i++) {
            int slot = sequence.slotOrder().get(i);
            int fuseNumber = i + 1;
            ItemStack icon = new ItemStack(Material.REDSTONE_LAMP, fuseNumber);
            var meta = icon.getItemMeta();
            meta.displayName(Component.text("Fusibile " + fuseNumber, NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            icon.setItemMeta(meta);
            buttons.put(slot, GuiButton.of(icon, (p, click) -> {
                if (!sequence.click(slot)) {
                    p.closeInventory();
                    messages.send(p, "electrician.wiring-failed");
                    return;
                }
                if (sequence.complete()) {
                    p.closeInventory();
                    finishRepair(p, mission);
                }
            }));
        }
        guiManager.open(player, new GuiMenu() {
            @Override
            public Component title() {
                return Component.text("Cablaggio — ordine crescente", NamedTextColor.DARK_AQUA);
            }

            @Override
            public int size() {
                return 45;
            }

            @Override
            public Map<Integer, GuiButton> buttons() {
                return buttons;
            }

            @Override
            public String permission() {
                return PERMISSION;
            }
        });
    }

    private void finishRepair(Player player, Mission mission) {
        var account = accountService.cachedPersonal(player.getUniqueId()).orElse(null);
        if (account == null) {
            messages.send(player, "general.db-unavailable");
            return;
        }
        service.completeRepair(mission, account.id()).thenAccept(result ->
                databaseManager.db().onMain(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (!result.rewarded()) {
                        messages.send(player, "electrician.already-completed");
                        return;
                    }
                    messages.send(player, "electrician.repair-done",
                            Placeholder.unparsed("reward", Money.format(result.rewardCents())));
                    if (result.circuitBoard()) {
                        ItemStack board = itemService.toItemStack(result.boardItem(),
                                Material.REPEATER,
                                Component.text("Scheda a circuito intatta", NamedTextColor.GOLD)
                                        .decoration(TextDecoration.ITALIC, false));
                        var meta = board.getItemMeta();
                        meta.setCustomModelData(ElectricianService.CIRCUIT_BOARD_MODEL_DATA);
                        board.setItemMeta(meta);
                        player.getInventory().addItem(board).values().forEach(rest ->
                                player.getWorld().dropItemNaturally(player.getLocation(), rest));
                        messages.send(player, "electrician.board-found");
                    }
                }));
    }
}
