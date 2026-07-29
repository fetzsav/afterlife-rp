package com.afterlife.rp.module.nightclub;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.Money;
import com.afterlife.rp.shared.gui.GuiButton;
import com.afterlife.rp.shared.gui.GuiManager;
import com.afterlife.rp.shared.gui.GuiMenu;
import com.afterlife.rp.shared.items.ItemTypes;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.regions.Poi;
import com.afterlife.rp.shared.regions.PoiService;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import com.afterlife.rp.command.TabComplete;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/** /club <vendi|conferma|rifiuta|shaker|scanner|blacklist|dj|escrow|taglia|staff|prezzi|rifornisci|happyhour|magazzino> (§9.11). */
public final class NightclubCommands implements CommandExecutor, TabCompleter {

    private static final String PERM_BARTENDER = "afterlife.nightclub.bartender";
    private static final String PERM_SECURITY = "afterlife.nightclub.security";
    private static final String PERM_MANAGER = "afterlife.nightclub.manager";
    private static final String PERM_BOSS = "afterlife.crime.boss";

    private final Plugin plugin;
    private final DatabaseManager databaseManager;
    private final NightclubService service;
    private final AccountService accountService;
    private final SerializedItemService itemService;
    private final PoiService poiService;
    private final GuiManager guiManager;
    private final Messages messages;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Long> djCooldown = new ConcurrentHashMap<>();

    public NightclubCommands(Plugin plugin, DatabaseManager databaseManager,
            NightclubService service, AccountService accountService,
            SerializedItemService itemService, PoiService poiService, GuiManager guiManager,
            Messages messages) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.service = service;
        this.accountService = accountService;
        this.itemService = itemService;
        this.poiService = poiService;
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
        if (!databaseManager.ready()) {
            messages.send(player, "general.db-unavailable");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "vendi" -> sell(player, args);
            case "conferma" -> confirm(player);
            case "rifiuta" -> decline(player);
            case "shaker" -> shaker(player, args);
            case "scanner" -> scanner(player, args);
            case "blacklist" -> blacklist(player, args);
            case "dj" -> dj(player);
            case "escrow" -> escrow(player, args);
            case "taglia" -> bounty(player, args);
            case "staff" -> staff(player, args);
            case "prezzi" -> prices(player, args);
            case "rifornisci" -> restock(player, args);
            case "happyhour" -> happyHour(player);
            case "magazzino" -> stock(player);
            default -> messages.send(player, "club.usage");
        }
        return true;
    }

    private boolean has(Player player, String permission) {
        if (!player.hasPermission(permission)) {
            messages.send(player, "general.no-permission");
            return false;
        }
        return true;
    }

    private boolean nearPoi(Player player, List<String> types, double range) {
        for (Poi poi : poiService.byTypeAndStatus(types, "ACTIVE")) {
            var location = poi.location();
            if (location != null && poi.world().equals(player.getWorld().getName())
                    && location.distanceSquared(player.getLocation()) <= range * range) {
                return true;
            }
        }
        return false;
    }

    // --- POS ---

    private void sell(Player bartender, String[] args) {
        if (!has(bartender, PERM_BARTENDER)) {
            return;
        }
        if (args.length < 3) {
            messages.send(bartender, "club.vendi-usage");
            return;
        }
        if (!nearPoi(bartender, service.config().posPoiTypes(), service.config().posRangeBlocks())) {
            messages.send(bartender, "club.pos-required");
            return;
        }
        Player customer = Bukkit.getPlayerExact(args[1]);
        if (customer == null || customer.getLocation().distanceSquared(bartender.getLocation())
                > service.config().posRangeBlocks() * service.config().posRangeBlocks()) {
            messages.send(bartender, "club.customer-too-far");
            return;
        }
        List<NightclubService.OrderLine> lines = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            String[] parts = args[i].split(":", 2);
            int quantity = parts.length == 2 ? parseIntOr(parts[1], -1) : 1;
            lines.add(new NightclubService.OrderLine(parts[0].toLowerCase(Locale.ROOT), quantity));
        }
        service.proposeOrder(bartender.getUniqueId(), customer.getUniqueId(), lines)
                .thenAccept(orderId -> databaseManager.db().onMain(() -> {
                    if (orderId.isEmpty()) {
                        messages.send(bartender, "club.order-invalid");
                        return;
                    }
                    messages.send(bartender, "club.order-proposed",
                            Placeholder.unparsed("player", customer.getName()));
                    messages.send(customer, "club.order-received",
                            Placeholder.unparsed("player", bartender.getName()));
                }));
    }

    private void confirm(Player customer) {
        service.pendingOrderFor(customer.getUniqueId()).thenCompose(order -> {
            if (order.isEmpty()) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            var customerAccount = accountService.cachedPersonal(customer.getUniqueId()).orElse(null);
            var view = order.get();
            if (customerAccount == null) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            return accountService.findPersonal(view.employee()).thenCompose(bartenderAccount -> {
                if (bartenderAccount.isEmpty()) {
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }
                return service.commissionPercentFor(view.employee()).thenCompose(commission ->
                        service.acceptOrder(view, customerAccount.id(), bartenderAccount.get().id(),
                                        commission)
                                .thenApply(outcome -> new Object[] {view, outcome}));
            });
        }).thenAccept(result -> databaseManager.db().onMain(() -> {
            if (!customer.isOnline()) {
                return;
            }
            if (result == null) {
                messages.send(customer, "club.no-pending-order");
                return;
            }
            var view = (NightclubService.OrderView) ((Object[]) result)[0];
            var outcome = (NightclubService.PosOutcome) ((Object[]) result)[1];
            switch (outcome.result()) {
                case COMPLETED -> {
                    for (SerializedItem drink : outcome.drinks()) {
                        give(customer, NightclubItems.drinkStack(itemService, service, drink));
                    }
                    give(customer, NightclubItems.receiptStack(itemService,
                            outcome.customerReceipt()));
                    Player bartender = Bukkit.getPlayer(view.employee());
                    if (bartender != null) {
                        give(bartender, NightclubItems.receiptStack(itemService,
                                outcome.bartenderReceipt()));
                        messages.send(bartender, "club.order-completed-bartender",
                                Placeholder.unparsed("amount", Money.format(outcome.totalCents())));
                    }
                    messages.send(customer, "club.order-completed",
                            Placeholder.unparsed("amount", Money.format(outcome.totalCents())));
                }
                case OUT_OF_STOCK -> messages.send(customer, "club.out-of-stock");
                case PAYMENT_FAILED -> messages.send(customer, "bank.insufficient-funds");
                default -> messages.send(customer, "club.no-pending-order");
            }
        }));
    }

    private void decline(Player customer) {
        service.pendingOrderFor(customer.getUniqueId()).thenCompose(order -> order.isEmpty()
                        ? java.util.concurrent.CompletableFuture.completedFuture(false)
                        : service.declineOrder(order.get().id()))
                .thenAccept(declined -> messages.send(customer,
                        declined ? "club.order-declined" : "club.no-pending-order"));
    }

    // --- shaker minigame: click when the indicator is green (§9.11) ---

    private void shaker(Player bartender, String[] args) {
        if (!has(bartender, PERM_BARTENDER)) {
            return;
        }
        if (args.length != 2) {
            messages.send(bartender, "club.shaker-usage");
            return;
        }
        if (!nearPoi(bartender, service.config().shakerPoiTypes(), 4)) {
            messages.send(bartender, "club.shaker-required");
            return;
        }
        String product = args[1].toLowerCase(Locale.ROOT);
        if (!service.config().products().containsKey(product)) {
            messages.send(bartender, "club.order-invalid");
            return;
        }
        AtomicInteger phase = new AtomicInteger(random.nextInt(3));
        Map<Integer, GuiButton> buttons = new HashMap<>();
        ItemStack indicator = phaseItem(phase.get());
        BukkitTask[] cycler = new BukkitTask[1];
        buttons.put(13, GuiButton.of(indicator, (p, click) -> {
            if (cycler[0] != null) {
                cycler[0].cancel();
            }
            p.closeInventory();
            String quality = switch (phase.get() % 3) {
                case 0 -> "MASTERWORK";
                case 1 -> "NORMAL";
                default -> "DILUTED";
            };
            service.mixDrink(p.getUniqueId(), product, quality).thenAccept(drink ->
                    databaseManager.db().onMain(() -> {
                        if (!p.isOnline()) {
                            return;
                        }
                        if (drink.isEmpty()) {
                            messages.send(p, "club.out-of-stock");
                            return;
                        }
                        give(p, NightclubItems.drinkStack(itemService, service, drink.get()));
                        messages.send(p, "club.mixed",
                                Placeholder.unparsed("quality", quality));
                    }));
        }));
        GuiMenu menu = new GuiMenu() {
            @Override
            public Component title() {
                return Component.text("Shaker — colpisci sul VERDE", NamedTextColor.DARK_AQUA);
            }

            @Override
            public int size() {
                return 27;
            }

            @Override
            public Map<Integer, GuiButton> buttons() {
                return buttons;
            }

            @Override
            public String permission() {
                return PERM_BARTENDER;
            }
        };
        guiManager.open(bartender, menu);
        cycler[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!bartender.isOnline()
                    || !(bartender.getOpenInventory().getTopInventory().getHolder()
                            instanceof com.afterlife.rp.shared.gui.GuiHolder)) {
                cycler[0].cancel();
                return;
            }
            phase.incrementAndGet();
            bartender.getOpenInventory().getTopInventory().setItem(13, phaseItem(phase.get()));
        }, 10L, 10L);
    }

    private ItemStack phaseItem(int phase) {
        Material material = switch (phase % 3) {
            case 0 -> Material.LIME_STAINED_GLASS_PANE;
            case 1 -> Material.YELLOW_STAINED_GLASS_PANE;
            default -> Material.RED_STAINED_GLASS_PANE;
        };
        ItemStack stack = new ItemStack(material);
        var meta = stack.getItemMeta();
        meta.displayName(Component.text("SHAKERA!", NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        stack.setItemMeta(meta);
        return stack;
    }

    // --- security ---

    private void scanner(Player bouncer, String[] args) {
        if (!has(bouncer, PERM_SECURITY)) {
            return;
        }
        if (args.length != 2) {
            messages.send(bouncer, "club.scanner-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || target.getLocation().distanceSquared(bouncer.getLocation()) > 25) {
            messages.send(bouncer, "club.customer-too-far");
            return;
        }
        List<String> found = new ArrayList<>();
        for (ItemStack stack : target.getInventory().getContents()) {
            if (stack != null
                    && service.config().weaponMaterials().contains(stack.getType().name())) {
                found.add(stack.getType().name());
            }
        }
        messages.send(bouncer, found.isEmpty() ? "club.scan-clean" : "club.scan-weapons",
                Placeholder.unparsed("player", target.getName()),
                Placeholder.unparsed("items", String.join(", ", found)));
    }

    private void blacklist(Player security, String[] args) {
        if (!has(security, PERM_SECURITY)) {
            return;
        }
        if (args.length >= 4 && args[1].equalsIgnoreCase("add")) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                messages.send(security, "bank.target-not-found");
                return;
            }
            String reason = String.join(" ", List.of(args).subList(3, args.length));
            service.blacklistAdd(target.getUniqueId(), reason, security.getUniqueId(),
                            security.getName())
                    .thenAccept(added -> messages.send(security,
                            added ? "club.blacklist-added" : "club.blacklist-already",
                            Placeholder.unparsed("player", target.getName())));
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                messages.send(security, "bank.target-not-found");
                return;
            }
            service.blacklistRemove(target.getUniqueId(), security.getUniqueId(), security.getName())
                    .thenAccept(removed -> messages.send(security,
                            removed ? "club.blacklist-removed" : "club.blacklist-missing",
                            Placeholder.unparsed("player", target.getName())));
            return;
        }
        messages.send(security, "club.blacklist-usage");
    }

    private void dj(Player player) {
        if (!has(player, PERM_BARTENDER)) {
            return;
        }
        if (!nearPoi(player, service.config().djPoiTypes(), 5)) {
            messages.send(player, "club.dj-required");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = djCooldown.get(player.getUniqueId());
        if (last != null && now - last < service.config().djCooldownSeconds() * 1000L) {
            messages.send(player, "club.dj-cooldown");
            return;
        }
        djCooldown.put(player.getUniqueId(), now);
        AtomicInteger ticks = new AtomicInteger();
        int maxTicks = service.config().djEffectSeconds();
        // Strict rate limits (§9.11): a bounded, low-volume particle show.
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (ticks.incrementAndGet() > maxTicks || !player.isOnline()) {
                task[0].cancel();
                return;
            }
            var location = player.getLocation().add(0, 2, 0);
            player.getWorld().spawnParticle(Particle.DUST,
                    location.clone().add(random.nextDouble() * 6 - 3, random.nextDouble() * 2,
                            random.nextDouble() * 6 - 3),
                    10, new Particle.DustOptions(
                            org.bukkit.Color.fromRGB(random.nextInt(0xFFFFFF)), 1.4f));
            player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE,
                    location.clone().add(0, -1, 0), 2, 1.5, 0.2, 1.5, 0.01);
        }, 20L, 20L);
        messages.send(player, "club.dj-started");
    }

    // --- escrow ---

    private void escrow(Player player, String[] args) {
        String action = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "crea" -> {
                if (!has(player, PERM_BARTENDER) || args.length < 5) {
                    messages.send(player, "club.escrow-usage");
                    return;
                }
                Player a = Bukkit.getPlayerExact(args[2]);
                Player b = Bukkit.getPlayerExact(args[3]);
                Long cents = Money.parseWholeEuros(args[4]);
                if (a == null || b == null || cents == null) {
                    messages.send(player, "club.escrow-usage");
                    return;
                }
                service.createEscrow(player.getUniqueId(), player.getName(), a.getUniqueId(),
                                b.getUniqueId(), cents)
                        .thenAccept(id -> databaseManager.db().onMain(() -> {
                            messages.send(player, "club.escrow-created");
                            messages.send(a, "club.escrow-party-a");
                            messages.send(b, "club.escrow-party-b",
                                    Placeholder.unparsed("amount", Money.format(cents)));
                        }));
            }
            case "deposita" -> withDeal(player, deal -> {
                var held = itemService.readVerified(player.getInventory().getItemInMainHand())
                        .orElse(null);
                if (held == null) {
                    messages.send(player, "club.escrow-item-required");
                    return;
                }
                service.escrowDepositItem(deal, player.getUniqueId(), held.serial())
                        .thenAccept(deposited -> databaseManager.db().onMain(() -> {
                            if (!deposited) {
                                messages.send(player, "club.escrow-deposit-failed");
                                return;
                            }
                            player.getInventory().setItemInMainHand(null);
                            messages.send(player, "club.escrow-deposited");
                        }));
            });
            case "paga" -> withDeal(player, deal -> {
                List<SerializedItemService.PdcData> notes = new ArrayList<>();
                for (ItemStack stack : player.getInventory().getContents()) {
                    if (stack == null) {
                        continue;
                    }
                    itemService.readVerified(stack)
                            .filter(data -> ItemTypes.DIRTY_MONEY.equals(data.itemType()))
                            .ifPresent(notes::add);
                }
                service.escrowDepositDirty(deal, player.getUniqueId(), notes)
                        .thenAccept(collected -> databaseManager.db().onMain(() -> {
                            if (collected <= 0) {
                                messages.send(player, "club.escrow-deposit-failed");
                                return;
                            }
                            removeRedeemedDirty(player);
                            messages.send(player, "club.escrow-paid",
                                    Placeholder.unparsed("amount", Money.format(collected)));
                        }));
            });
            case "blocca" -> withDeal(player, deal ->
                    service.escrowLock(deal, player.getUniqueId()).thenAccept(locked ->
                            messages.send(player, locked
                                    ? "club.escrow-locked" : "club.escrow-lock-failed")));
            case "conferma" -> withDeal(player, deal ->
                    service.escrowConfirm(deal, player.getUniqueId(), player.getName())
                            .thenAccept(result -> messages.send(player, switch (result) {
                                case COMPLETED -> "club.escrow-completed";
                                case NOT_LOCKED -> "club.escrow-not-locked";
                                default -> "club.escrow-deposit-failed";
                            })));
            case "annulla" -> withDeal(player, deal ->
                    service.escrowCancel(deal, player.getUniqueId(), player.getName())
                            .thenAccept(cancelled -> messages.send(player, cancelled
                                    ? "club.escrow-cancelled" : "club.escrow-lock-failed")));
            default -> messages.send(player, "club.escrow-usage");
        }
    }

    private void withDeal(Player player,
            java.util.function.Consumer<NightclubService.EscrowDeal> action) {
        service.openEscrowFor(player.getUniqueId()).thenAccept(deal ->
                databaseManager.db().onMain(() -> {
                    if (deal.isEmpty()) {
                        messages.send(player, "club.escrow-none");
                        return;
                    }
                    action.accept(deal.get());
                }));
    }

    private void removeRedeemedDirty(Player player) {
        var contents = player.getInventory().getContents();
        List<java.util.concurrent.CompletableFuture<Void>> checks = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null) {
                continue;
            }
            int slot = i;
            itemService.readVerified(stack)
                    .filter(data -> ItemTypes.DIRTY_MONEY.equals(data.itemType()))
                    .ifPresent(data -> checks.add(itemService
                            .validate(stack, com.afterlife.rp.shared.items.ItemStatus.ISSUED)
                            .thenAccept(validation -> {
                                if (validation.validation()
                                        != SerializedItemService.Validation.VALID) {
                                    databaseManager.db().onMain(() ->
                                            player.getInventory().setItem(slot, null));
                                }
                            })));
        }
    }

    // --- bounties ---

    private void bounty(Player player, String[] args) {
        String action = args.length < 2 ? "lista" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "crea" -> {
                if (!has(player, PERM_BOSS) || args.length < 4) {
                    messages.send(player, "club.taglia-usage");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                Long cents = Money.parseWholeEuros(args[3]);
                if (target == null || cents == null) {
                    messages.send(player, "club.taglia-usage");
                    return;
                }
                var account = accountService.cachedPersonal(player.getUniqueId()).orElse(null);
                if (account == null) {
                    messages.send(player, "general.db-unavailable");
                    return;
                }
                service.createBounty(player.getUniqueId(), player.getName(), account.id(),
                                target.getUniqueId(), cents)
                        .thenAccept(id -> messages.send(player, id.isPresent()
                                ? "club.taglia-created" : "club.taglia-failed"));
            }
            case "paga" -> {
                if (!has(player, PERM_BARTENDER) || args.length < 4) {
                    messages.send(player, "club.taglia-usage");
                    return;
                }
                long id = parseIntOr(args[2], -1);
                Player claimant = Bukkit.getPlayerExact(args[3]);
                var bartenderAccount = accountService.cachedPersonal(player.getUniqueId())
                        .orElse(null);
                var claimantAccount = claimant == null ? null
                        : accountService.cachedPersonal(claimant.getUniqueId()).orElse(null);
                if (id < 0 || claimant == null || bartenderAccount == null
                        || claimantAccount == null) {
                    messages.send(player, "club.taglia-usage");
                    return;
                }
                service.payBounty(id, claimantAccount.id(), bartenderAccount.id(),
                                claimant.getUniqueId(), player.getUniqueId(), player.getName())
                        .thenAccept(result -> messages.send(player, switch (result) {
                            case COMPLETED -> "club.taglia-paid";
                            case NOT_OPEN -> "club.taglia-not-open";
                            default -> "bank.insufficient-funds";
                        }));
            }
            default -> service.openBounties().thenAccept(bounties -> {
                messages.send(player, "club.taglia-header",
                        Placeholder.unparsed("count", String.valueOf(bounties.size())));
                for (var bounty : bounties) {
                    var target = Bukkit.getOfflinePlayer(bounty.target());
                    messages.send(player, "club.taglia-entry",
                            Placeholder.unparsed("id", String.valueOf(bounty.id())),
                            Placeholder.unparsed("target",
                                    target.getName() == null ? "?" : target.getName()),
                            Placeholder.unparsed("amount", Money.format(bounty.amountCents())));
                }
            });
        }
    }

    // --- manager ---

    private void staff(Player manager, String[] args) {
        if (!has(manager, PERM_MANAGER)) {
            return;
        }
        if (args.length >= 4 && args[1].equalsIgnoreCase("assumi")) {
            Player target = Bukkit.getPlayerExact(args[2]);
            int commission = parseIntOr(args.length >= 5 ? args[4] : "10", 10);
            if (target == null || commission < 0 || commission > 50) {
                messages.send(manager, "club.staff-usage");
                return;
            }
            service.hire(target.getUniqueId(), args[3].toUpperCase(Locale.ROOT), commission,
                            manager.getUniqueId(), manager.getName())
                    .thenAccept(hired -> messages.send(manager, "club.staff-hired",
                            Placeholder.unparsed("player", target.getName())));
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("licenzia")) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                messages.send(manager, "bank.target-not-found");
                return;
            }
            service.fire(target.getUniqueId(), manager.getUniqueId(), manager.getName())
                    .thenAccept(fired -> messages.send(manager,
                            fired ? "club.staff-fired" : "club.staff-usage",
                            Placeholder.unparsed("player", target.getName())));
            return;
        }
        messages.send(manager, "club.staff-usage");
    }

    private void prices(Player manager, String[] args) {
        if (!has(manager, PERM_MANAGER)) {
            return;
        }
        if (args.length != 3) {
            messages.send(manager, "club.prezzi-usage");
            return;
        }
        Long cents = Money.parseWholeEuros(args[2]);
        if (cents == null) {
            messages.send(manager, "bank.invalid-amount");
            return;
        }
        service.setPrice(args[1].toLowerCase(Locale.ROOT), cents, manager.getUniqueId(),
                        manager.getName())
                .thenAccept(changed -> messages.send(manager,
                        changed ? "club.prezzi-set" : "club.prezzi-out-of-range"));
    }

    private void restock(Player manager, String[] args) {
        if (!has(manager, PERM_MANAGER)) {
            return;
        }
        if (args.length != 3) {
            messages.send(manager, "club.rifornisci-usage");
            return;
        }
        int quantity = parseIntOr(args[2], -1);
        service.restock(args[1].toLowerCase(Locale.ROOT), quantity, manager.getUniqueId(),
                        manager.getName())
                .thenAccept(ordered -> messages.send(manager,
                        ordered ? "club.rifornisci-ordered" : "club.rifornisci-failed",
                        Placeholder.unparsed("minutes",
                                String.valueOf(service.config().restockDelayMinutes()))));
    }

    private void happyHour(Player manager) {
        if (!has(manager, PERM_MANAGER)) {
            return;
        }
        service.startHappyHour(manager.getUniqueId(), manager.getName());
        messages.send(manager, "club.happyhour-started",
                Placeholder.unparsed("percent",
                        String.valueOf(service.config().happyHourDiscountPercent())),
                Placeholder.unparsed("minutes",
                        String.valueOf(service.config().happyHourDurationMinutes())));
    }

    private void stock(Player player) {
        if (!has(player, PERM_BARTENDER)) {
            return;
        }
        service.stock().thenAccept(rows -> {
            messages.send(player, "club.stock-header");
            for (var row : rows) {
                messages.send(player, "club.stock-entry",
                        Placeholder.unparsed("product", row.product()),
                        Placeholder.unparsed("stock", String.valueOf(row.stock())),
                        Placeholder.unparsed("price", Money.format(row.retailCents())));
            }
        });
    }

    private void give(Player player, ItemStack stack) {
        player.getInventory().addItem(stack).values().forEach(rest ->
                player.getWorld().dropItemNaturally(player.getLocation(), rest));
    }

    private int parseIntOr(String input, int fallback) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public java.util.List<String> onTabComplete(@org.jetbrains.annotations.NotNull CommandSender sender,
            @org.jetbrains.annotations.NotNull Command command,
            @org.jetbrains.annotations.NotNull String alias, String @org.jetbrains.annotations.NotNull [] args) {
        if (args.length == 1) {
            return TabComplete.filter(java.util.List.of("vendi", "conferma", "rifiuta", "shaker",
                    "scanner", "blacklist", "dj", "escrow", "taglia", "staff", "prezzi",
                    "rifornisci", "happyhour", "magazzino"), args[0]);
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
                case "vendi", "scanner" -> {
                    return TabComplete.players(args[1]);
                }
                case "blacklist" -> {
                    return TabComplete.filter(java.util.List.of("add", "remove"), args[1]);
                }
                case "escrow" -> {
                    return TabComplete.filter(java.util.List.of("crea", "deposita", "paga", "blocca",
                            "conferma", "annulla"), args[1]);
                }
                case "taglia" -> {
                    return TabComplete.filter(java.util.List.of("crea", "lista", "paga"), args[1]);
                }
                case "staff" -> {
                    return TabComplete.filter(java.util.List.of("assumi", "licenzia"), args[1]);
                }
                case "prezzi", "rifornisci" -> {
                    return TabComplete.filter(java.util.List.of("vodka_redbull", "absinthe",
                            "tequila_boom", "lemonade"), args[1]);
                }
                default -> { }
            }
        }
        return java.util.List.of();
    }

}
