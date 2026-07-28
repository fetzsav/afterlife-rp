package com.afterlife.rp.module.realestate;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.module.banking.BankingItems;
import com.afterlife.rp.module.banking.BankingService;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.Money;
import com.afterlife.rp.shared.items.ItemTypes;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Real-estate commands (§9.7): /luoghidisponibili, /luoghisporchi, /agenzia,
 * /cambia_serratura, /cassaforte, /chiave.
 */
public final class RealEstateCommands implements CommandExecutor {

    private static final String PERM_AGENT = "afterlife.realestate.agent";
    private static final String PERM_DIRECTOR = "afterlife.realestate.director";

    private final DatabaseManager databaseManager;
    private final AccountService accountService;
    private final RealEstateService realEstateService;
    private final BankingService bankingService;
    private final SerializedItemService itemService;
    private final Messages messages;

    public RealEstateCommands(
            DatabaseManager databaseManager,
            AccountService accountService,
            RealEstateService realEstateService,
            BankingService bankingService,
            SerializedItemService itemService,
            Messages messages) {
        this.databaseManager = databaseManager;
        this.accountService = accountService;
        this.realEstateService = realEstateService;
        this.bankingService = bankingService;
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
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "luoghidisponibili" -> listProperties(player, "AVAILABLE", false);
            case "luoghisporchi" -> listProperties(player, "DIRTY_AVAILABLE", true);
            case "agenzia" -> agenzia(player, args);
            case "cambia_serratura" -> cambiaSerratura(player, args);
            case "cassaforte" -> cassaforte(player, args);
            case "chiave" -> chiave(player);
            default -> messages.send(player, "general.internal-error");
        }
        return true;
    }

    private boolean requirePermission(Player player, String permission) {
        if (!player.hasPermission(permission)) {
            messages.send(player, "general.no-permission");
            return false;
        }
        return true;
    }

    private void listProperties(Player player, String state, boolean requiresDirector) {
        if (requiresDirector && !requirePermission(player, PERM_DIRECTOR)) {
            return;
        }
        realEstateService.listByState(state).thenAccept(properties -> {
            messages.send(player, "estate.list-header",
                    Placeholder.unparsed("count", String.valueOf(properties.size())));
            for (Property property : properties) {
                messages.send(player, "estate.list-entry",
                        Placeholder.unparsed("name", property.name()),
                        Placeholder.unparsed("type", property.type()),
                        Placeholder.unparsed("price", Money.format(property.price())),
                        Placeholder.unparsed("world", property.world()),
                        Placeholder.unparsed("x", String.valueOf((int) property.x())),
                        Placeholder.unparsed("z", String.valueOf((int) property.z())));
            }
        });
    }

    private void agenzia(Player player, String[] args) {
        if (!requirePermission(player, PERM_AGENT)) {
            return;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("vendi")) {
            sell(player, args[1], args[2]);
            return;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("affittasporco")) {
            if (!requirePermission(player, PERM_DIRECTOR)) {
                return;
            }
            rentDirty(player, args[1], args[2]);
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("distruggi_file")) {
            destroyFile(player);
            return;
        }
        messages.send(player, "estate.agenzia-usage");
    }

    private void sell(Player agent, String propertyName, String buyerName) {
        Player buyer = Bukkit.getPlayerExact(buyerName);
        if (buyer == null) {
            messages.send(agent, "bank.target-not-found");
            return;
        }
        var account = accountService.cachedPersonal(buyer.getUniqueId()).orElse(null);
        if (account == null) {
            messages.send(agent, "general.db-unavailable");
            return;
        }
        realEstateService.sell(propertyName, buyer.getUniqueId(), account.id(),
                        agent.getUniqueId(), agent.getName())
                .thenAccept(outcome -> databaseManager.db().onMain(() -> {
                    switch (outcome.result()) {
                        case COMPLETED -> {
                            giveKey(buyer, outcome.key(), propertyName);
                            messages.send(agent, "estate.sold",
                                    Placeholder.unparsed("name", propertyName),
                                    Placeholder.unparsed("player", buyer.getName()));
                            if (buyer.isOnline()) {
                                messages.send(buyer, "estate.bought",
                                        Placeholder.unparsed("name", propertyName));
                            }
                        }
                        case PAYMENT_FAILED -> messages.send(agent, "bank.insufficient-funds");
                        case NOT_AVAILABLE -> messages.send(agent, "estate.not-available");
                        default -> messages.send(agent, "estate.not-found",
                                Placeholder.unparsed("name", propertyName));
                    }
                }));
    }

    private void rentDirty(Player director, String propertyName, String tenantName) {
        Player tenant = Bukkit.getPlayerExact(tenantName);
        if (tenant == null) {
            messages.send(director, "bank.target-not-found");
            return;
        }
        List<SerializedItemService.PdcData> dirtyNotes = new ArrayList<>();
        for (ItemStack stack : tenant.getInventory().getContents()) {
            if (stack == null) {
                continue;
            }
            itemService.readVerified(stack)
                    .filter(data -> ItemTypes.DIRTY_MONEY.equals(data.itemType()))
                    .ifPresent(dirtyNotes::add);
        }
        realEstateService.rentDirty(propertyName, tenant.getUniqueId(), dirtyNotes,
                        director.getUniqueId(), director.getName())
                .thenAccept(result -> databaseManager.db().onMain(() -> {
                    if (!result.completed()) {
                        messages.send(director, "estate.dirty-rent-failed");
                        return;
                    }
                    removeSerials(tenant, Set.copyOf(result.consumedSerials()));
                    ItemStack file = itemService.toItemStack(result.fileItem(), Material.PAPER,
                            Component.text("File Confidenziale — " + propertyName,
                                    NamedTextColor.DARK_PURPLE).decoration(TextDecoration.ITALIC, false));
                    director.getInventory().addItem(file).values().forEach(rest ->
                            director.getWorld().dropItemNaturally(director.getLocation(), rest));
                    messages.send(director, "estate.dirty-rented",
                            Placeholder.unparsed("name", propertyName),
                            Placeholder.unparsed("player", tenant.getName()),
                            Placeholder.unparsed("amount", Money.format(result.collectedCents())));
                    if (tenant.isOnline()) {
                        messages.send(tenant, "estate.dirty-tenant-notice",
                                Placeholder.unparsed("name", propertyName));
                    }
                }));
    }

    private void destroyFile(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        var data = itemService.readVerified(held)
                .filter(d -> RealEstateService.ITEM_TYPE_BLACK_FILE.equals(d.itemType()))
                .orElse(null);
        if (data == null) {
            messages.send(player, "estate.file-hold");
            return;
        }
        realEstateService.destroyBlackFile(data, player.getUniqueId(), player.getName())
                .thenAccept(destroyed -> databaseManager.db().onMain(() -> {
                    if (!destroyed) {
                        messages.send(player, "estate.file-invalid");
                        return;
                    }
                    if (player.isOnline()) {
                        player.getInventory().setItemInMainHand(null);
                    }
                    messages.send(player, "estate.file-destroyed");
                }));
    }

    private void cambiaSerratura(Player player, String[] args) {
        if (!requirePermission(player, PERM_AGENT)) {
            return;
        }
        if (args.length < 2) {
            messages.send(player, "estate.serratura-usage");
            return;
        }
        String propertyName = args[0];
        String reason = String.join(" ", List.of(args).subList(1, args.length));
        realEstateService.changeLock(propertyName, reason, player.getUniqueId(), player.getName())
                .thenAccept(change -> databaseManager.db().onMain(() -> {
                    if (!change.changed()) {
                        messages.send(player, "estate.not-found",
                                Placeholder.unparsed("name", propertyName));
                        return;
                    }
                    if (change.newKey() != null && change.currentOwner() != null) {
                        Player owner = Bukkit.getPlayer(change.currentOwner());
                        if (owner != null) {
                            giveKey(owner, change.newKey(), propertyName);
                            messages.send(owner, "estate.new-key",
                                    Placeholder.unparsed("name", propertyName));
                        } else {
                            realEstateService.queueItemDelivery(change.newKey(), "LOCK_CHANGE");
                        }
                    }
                    messages.send(player, "estate.lock-changed",
                            Placeholder.unparsed("name", propertyName));
                }));
    }

    private void cassaforte(Player player, String[] args) {
        if (!requirePermission(player, PERM_DIRECTOR)) {
            return;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("preleva")) {
            Long cents = Money.parseWholeEuros(args[1]);
            if (cents == null) {
                messages.send(player, "bank.invalid-amount");
                return;
            }
            realEstateService.blackSafeWithdraw(cents, player.getUniqueId(), player.getName())
                    .thenCompose(withdrawn -> {
                        if (!withdrawn) {
                            return java.util.concurrent.CompletableFuture.completedFuture(
                                    List.<SerializedItem>of());
                        }
                        return bankingService.issueDirty(player.getUniqueId(), cents,
                                player.getUniqueId());
                    })
                    .thenAccept(notes -> databaseManager.db().onMain(() -> {
                        if (notes.isEmpty()) {
                            messages.send(player, "estate.safe-insufficient");
                            return;
                        }
                        for (SerializedItem note : notes) {
                            player.getInventory().addItem(BankingItems.toStack(itemService, note))
                                    .values().forEach(rest -> player.getWorld()
                                            .dropItemNaturally(player.getLocation(), rest));
                        }
                        messages.send(player, "estate.safe-withdrawn",
                                Placeholder.unparsed("amount", Money.format(cents)));
                    }));
            return;
        }
        realEstateService.blackSafeBalance().thenAccept(balance ->
                messages.send(player, "estate.safe-balance",
                        Placeholder.unparsed("amount", Money.format(balance))));
    }

    private void chiave(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        var data = itemService.readVerified(held)
                .filter(d -> RealEstateService.ITEM_TYPE_KEY.equals(d.itemType()))
                .orElse(null);
        if (data == null) {
            messages.send(player, "estate.key-hold");
            return;
        }
        realEstateService.keyValid(data).thenAccept(valid ->
                messages.send(player, valid ? "estate.key-valid" : "estate.key-invalid"));
    }

    private void giveKey(Player recipient, SerializedItem key, String propertyName) {
        if (!recipient.isOnline()) {
            realEstateService.queueItemDelivery(key, "PROPERTY_KEY");
            return;
        }
        ItemStack stack = itemService.toItemStack(key, Material.TRIPWIRE_HOOK,
                Component.text("Chiave — " + propertyName, NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false));
        recipient.getInventory().addItem(stack).values().forEach(rest ->
                recipient.getWorld().dropItemNaturally(recipient.getLocation(), rest));
    }

    private void removeSerials(Player player, Set<UUID> serials) {
        Set<UUID> remaining = new HashSet<>(serials);
        var contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null) {
                continue;
            }
            var data = itemService.readVerified(stack);
            if (data.isPresent() && remaining.contains(data.get().serial())) {
                player.getInventory().setItem(i, null);
            }
        }
    }
}
