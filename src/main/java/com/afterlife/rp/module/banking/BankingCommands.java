package com.afterlife.rp.module.banking;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.economy.Account;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.economy.Money;
import com.afterlife.rp.shared.items.ItemTypes;
import com.afterlife.rp.shared.items.SerializedItemService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Player and staff banking commands (§9.1): /iban, /atm, /bonifico, /assegno,
 * /incassa, /banchiere, /sequestro. Each validates sender, permission,
 * arguments, and state (rule 14).
 */
public final class BankingCommands implements CommandExecutor {

    private static final String PERM_USER = "afterlife.bank.user";
    private static final String PERM_BANKER = "afterlife.bank.banker";
    private static final String PERM_DIRECTOR = "afterlife.bank.director";

    private final DatabaseManager databaseManager;
    private final AccountService accountService;
    private final BankingService bankingService;
    private final AtmFlows atmFlows;
    private final SerializedItemService itemService;
    private final AuditService auditService;
    private final Messages messages;

    public BankingCommands(
            DatabaseManager databaseManager,
            AccountService accountService,
            BankingService bankingService,
            AtmFlows atmFlows,
            SerializedItemService itemService,
            AuditService auditService,
            Messages messages) {
        this.databaseManager = databaseManager;
        this.accountService = accountService;
        this.bankingService = bankingService;
        this.atmFlows = atmFlows;
        this.itemService = itemService;
        this.auditService = auditService;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "iban" -> iban(player);
            case "atm" -> atm(player, args);
            case "bonifico" -> bonifico(player, args);
            case "assegno" -> assegno(player, args);
            case "incassa" -> incassa(player);
            case "banchiere" -> banchiere(player, args);
            case "sequestro" -> sequestro(player, args);
            default -> messages.send(player, "general.internal-error");
        }
        return true;
    }

    private boolean requireReady(Player player) {
        if (!databaseManager.ready()) {
            messages.send(player, "general.db-unavailable");
            return false;
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

    private void iban(Player player) {
        if (!requirePermission(player, PERM_USER) || !requireReady(player)) {
            return;
        }
        Account account = accountService.cachedPersonal(player.getUniqueId()).orElse(null);
        if (account == null) {
            messages.send(player, "general.db-unavailable");
            return;
        }
        long balance = accountService.cachedBalance(account.id()).orElse(account.balance());
        messages.send(player, "bank.iban-info",
                Placeholder.unparsed("iban", account.iban()),
                Placeholder.unparsed("balance", Money.format(balance)),
                Placeholder.unparsed("status", account.frozen() ? "CONGELATO" : "attivo"));
    }

    private void atm(Player player, String[] args) {
        if (!requirePermission(player, PERM_USER) || !requireReady(player)) {
            return;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("preleva")) {
            Long cents = Money.parseWholeEuros(args[1]);
            if (cents == null) {
                messages.send(player, "bank.invalid-amount");
                return;
            }
            atmFlows.withdraw(player, cents);
            return;
        }
        atmFlows.openAtm(player);
    }

    private void bonifico(Player player, String[] args) {
        if (!requirePermission(player, PERM_USER) || !requireReady(player)) {
            return;
        }
        if (args.length != 2) {
            messages.send(player, "bank.bonifico-usage");
            return;
        }
        Long cents = Money.parseWholeEuros(args[1]);
        if (cents == null) {
            messages.send(player, "bank.invalid-amount");
            return;
        }
        Account source = accountService.cachedPersonal(player.getUniqueId()).orElse(null);
        if (source == null) {
            messages.send(player, "general.db-unavailable");
            return;
        }
        resolveTargetIban(args[0], targetIban -> {
            if (targetIban == null) {
                messages.send(player, "bank.target-not-found");
                return;
            }
            bankingService.transferByIban(player.getUniqueId(), source.id(), targetIban, cents,
                            "tx-" + UUID.randomUUID())
                    .thenAccept(result -> databaseManager.db().onMain(() -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (result.status() == LedgerService.Status.COMPLETED) {
                            messages.send(player, "bank.transfer-ok",
                                    Placeholder.unparsed("amount", Money.format(cents)),
                                    Placeholder.unparsed("target", args[0]));
                        } else {
                            atmFlows.sendLedgerFailure(player, result.status());
                        }
                    }));
        });
    }

    /** Accepts an IBAN directly or resolves an exact online player name. */
    private void resolveTargetIban(String input, java.util.function.Consumer<String> callback) {
        if (input.toUpperCase(Locale.ROOT).startsWith("IT") && input.length() >= 15) {
            callback.accept(input.toUpperCase(Locale.ROOT));
            return;
        }
        Player target = Bukkit.getPlayerExact(input);
        if (target == null) {
            callback.accept(null);
            return;
        }
        accountService.getOrCreatePersonal(target.getUniqueId())
                .thenAccept(account -> databaseManager.db().onMain(() -> callback.accept(account.iban())));
    }

    private void assegno(Player player, String[] args) {
        if (!requirePermission(player, PERM_USER) || !requireReady(player)) {
            return;
        }
        if (args.length != 2) {
            messages.send(player, "bank.assegno-usage");
            return;
        }
        Player payee = Bukkit.getPlayerExact(args[0]);
        if (payee == null) {
            messages.send(player, "bank.target-not-found");
            return;
        }
        Long cents = Money.parseWholeEuros(args[1]);
        if (cents == null) {
            messages.send(player, "bank.invalid-amount");
            return;
        }
        Account issuer = accountService.cachedPersonal(player.getUniqueId()).orElse(null);
        if (issuer == null) {
            messages.send(player, "general.db-unavailable");
            return;
        }
        bankingService.issueCheck(player.getUniqueId(), issuer.id(), payee.getUniqueId(), cents,
                        "chk-" + UUID.randomUUID())
                .whenComplete((check, error) -> databaseManager.db().onMain(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        atmFlows.sendLedgerFailure(player, LedgerService.failureFrom(error).status());
                        return;
                    }
                    ItemStack stack = BankingItems.toStack(itemService, check);
                    player.getInventory().addItem(stack).values().forEach(rest ->
                            player.getWorld().dropItemNaturally(player.getLocation(), rest));
                    messages.send(player, "bank.check-issued",
                            Placeholder.unparsed("amount", Money.format(cents)),
                            Placeholder.unparsed("payee", payee.getName()));
                }));
    }

    private void incassa(Player player) {
        if (!requirePermission(player, PERM_USER) || !requireReady(player)) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        var checkData = itemService.readVerified(held)
                .filter(data -> ItemTypes.CHECK.equals(data.itemType()))
                .orElse(null);
        if (checkData == null) {
            messages.send(player, "bank.check-hold");
            return;
        }
        Account account = accountService.cachedPersonal(player.getUniqueId()).orElse(null);
        if (account == null) {
            messages.send(player, "general.db-unavailable");
            return;
        }
        bankingService.redeemCheck(player.getUniqueId(), account.id(), checkData,
                        "chr-" + UUID.randomUUID())
                .thenAccept(status -> databaseManager.db().onMain(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    switch (status) {
                        case COMPLETED -> {
                            removeSerialFromHand(player, checkData.serial());
                            messages.send(player, "bank.check-redeemed",
                                    Placeholder.unparsed("amount", Money.format(checkData.denomination())));
                        }
                        case NOT_PAYEE -> messages.send(player, "bank.check-not-payee");
                        case EXPIRED -> messages.send(player, "bank.check-expired");
                        default -> messages.send(player, "bank.check-invalid");
                    }
                }));
    }

    private void removeSerialFromHand(Player player, UUID serial) {
        ItemStack held = player.getInventory().getItemInMainHand();
        var data = itemService.readVerified(held);
        if (data.isPresent() && data.get().serial().equals(serial)) {
            player.getInventory().setItemInMainHand(null);
        }
    }

    private void banchiere(Player player, String[] args) {
        if (!requirePermission(player, PERM_BANKER) || !requireReady(player)) {
            return;
        }
        if (args.length < 2) {
            messages.send(player, "bank.banchiere-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(player, "bank.target-not-found");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "apri" -> accountService.getOrCreatePersonal(target.getUniqueId())
                    .thenAccept(account -> {
                        auditService.log(player.getUniqueId(), player.getName(),
                                "ACCOUNT_OPEN", target.getUniqueId().toString(),
                                java.util.Map.of("iban", account.iban()));
                        databaseManager.db().onMain(() -> messages.send(player, "bank.account-opened",
                                Placeholder.unparsed("player", target.getName()),
                                Placeholder.unparsed("iban", account.iban())));
                    });
            case "carta" -> accountService.getOrCreatePersonal(target.getUniqueId())
                    .thenCompose(account -> bankingService.issueCard(
                            target.getUniqueId(), account.id(), player.getUniqueId(), player.getName()))
                    .thenAccept(card -> databaseManager.db().onMain(() -> {
                        if (!target.isOnline()) {
                            return;
                        }
                        ItemStack stack = BankingItems.toStack(itemService, card);
                        target.getInventory().addItem(stack).values().forEach(rest ->
                                target.getWorld().dropItemNaturally(target.getLocation(), rest));
                        messages.send(player, "bank.card-issued",
                                Placeholder.unparsed("player", target.getName()));
                    }));
            case "revoca" -> bankingService.revokeCards(
                            target.getUniqueId(), player.getUniqueId(), player.getName())
                    .thenAccept(count -> databaseManager.db().onMain(() ->
                            messages.send(player, "bank.cards-revoked",
                                    Placeholder.unparsed("count", String.valueOf(count)),
                                    Placeholder.unparsed("player", target.getName()))));
            default -> messages.send(player, "bank.banchiere-usage");
        }
    }

    private void sequestro(Player player, String[] args) {
        if (!requirePermission(player, PERM_DIRECTOR) || !requireReady(player)) {
            return;
        }
        if (args.length < 3
                || (!args[1].equalsIgnoreCase("congela") && !args[1].equalsIgnoreCase("scongela"))) {
            messages.send(player, "bank.sequestro-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.send(player, "bank.target-not-found");
            return;
        }
        boolean freeze = args[1].equalsIgnoreCase("congela");
        String reason = String.join(" ", List.of(args).subList(2, args.length));
        accountService.getOrCreatePersonal(target.getUniqueId())
                .thenCompose(account -> accountService.setFrozen(
                        account.id(), freeze, reason, player.getUniqueId(), player.getName()))
                .thenAccept(changed -> databaseManager.db().onMain(() -> messages.send(player,
                        changed ? (freeze ? "bank.frozen-ok" : "bank.unfrozen-ok") : "bank.freeze-unchanged",
                        Placeholder.unparsed("player", target.getName()),
                        Placeholder.unparsed("reason", reason))));
    }
}
