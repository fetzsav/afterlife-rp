package com.afterlife.rp.module.banking;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.economy.Account;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.DenominationBreakdown;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.economy.Money;
import com.afterlife.rp.shared.gui.GuiButton;
import com.afterlife.rp.shared.gui.GuiManager;
import com.afterlife.rp.shared.gui.GuiMenu;
import com.afterlife.rp.shared.items.ItemTypes;
import com.afterlife.rp.shared.items.SerializedItem;
import com.afterlife.rp.shared.items.SerializedItemService;
import com.afterlife.rp.shared.regions.Poi;
import com.afterlife.rp.shared.regions.PoiService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * ATM user flows (§9.1): card gate, POI proximity, deposit of physical notes,
 * quick/custom withdrawals, statement. GUI clicks re-enter these methods; every
 * authoritative step happens in BankingService transactions.
 */
public final class AtmFlows {

    private final DatabaseManager databaseManager;
    private final AccountService accountService;
    private final BankingService bankingService;
    private final SerializedItemService itemService;
    private final PoiService poiService;
    private final GuiManager guiManager;
    private final Messages messages;

    public AtmFlows(
            DatabaseManager databaseManager,
            AccountService accountService,
            BankingService bankingService,
            SerializedItemService itemService,
            PoiService poiService,
            GuiManager guiManager,
            Messages messages) {
        this.databaseManager = databaseManager;
        this.accountService = accountService;
        this.bankingService = bankingService;
        this.itemService = itemService;
        this.poiService = poiService;
        this.guiManager = guiManager;
        this.messages = messages;
    }

    /** Main-thread gate: DB, POI proximity, account, card. Runs action when valid. */
    public void withAtmAccess(Player player, java.util.function.Consumer<Account> action) {
        if (!databaseManager.ready()) {
            messages.send(player, "general.db-unavailable");
            return;
        }
        BankingConfig config = bankingService.config();
        if (config.atmRequirePoi() && !nearAtm(player, config)) {
            messages.send(player, "bank.not-near-atm");
            return;
        }
        Account account = accountService.cachedPersonal(player.getUniqueId()).orElse(null);
        if (account == null) {
            messages.send(player, "general.db-unavailable");
            return;
        }
        SerializedItemService.PdcData card = findHeldCard(player);
        if (card == null) {
            messages.send(player, "bank.card-required");
            return;
        }
        bankingService.validateCard(card, player.getUniqueId(), account.id()).thenAccept(valid ->
                databaseManager.db().onMain(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (!valid) {
                        messages.send(player, "bank.card-invalid");
                        return;
                    }
                    action.accept(account);
                }));
    }

    public void openAtm(Player player) {
        withAtmAccess(player, account -> guiManager.open(player, buildMenu(player, account)));
    }

    public void withdraw(Player player, long amountCents) {
        withAtmAccess(player, account -> {
            Map<Long, Integer> breakdown = DenominationBreakdown.breakdown(
                    amountCents, bankingService.config().denominationsCentsDesc());
            if (breakdown == null) {
                messages.send(player, "bank.invalid-amount");
                return;
            }
            int needed = DenominationBreakdown.totalNotes(breakdown);
            if (emptySlots(player) < needed) {
                messages.send(player, "bank.inventory-full",
                        Placeholder.unparsed("slots", String.valueOf(needed)));
                return;
            }
            String idempotencyKey = "atm-w-" + UUID.randomUUID();
            bankingService.withdraw(player.getUniqueId(), account.id(), amountCents, idempotencyKey)
                    .thenAccept(result -> databaseManager.db().onMain(() ->
                            finishWithdraw(player, amountCents, result)));
        });
    }

    private void finishWithdraw(Player player, long amountCents, BankingService.WithdrawResult result) {
        if (result.status() != LedgerService.Status.COMPLETED) {
            sendLedgerFailure(player, result.status());
            return;
        }
        for (SerializedItem note : result.notes()) {
            if (!player.isOnline()) {
                bankingService.voidAndQueueRedelivery(note, result.transactionId());
                continue;
            }
            ItemStack stack = BankingItems.toStack(itemService, note);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            if (!overflow.isEmpty()) {
                // Inventory changed between the pre-check and delivery (§7.4 step 8).
                bankingService.voidAndQueueRedelivery(note, result.transactionId());
            }
        }
        if (player.isOnline()) {
            messages.send(player, "bank.withdraw-ok",
                    Placeholder.unparsed("amount", Money.format(amountCents)));
        }
    }

    public void depositAll(Player player) {
        withAtmAccess(player, account -> {
            List<SerializedItemService.PdcData> notes = new ArrayList<>();
            for (ItemStack stack : player.getInventory().getContents()) {
                if (stack == null) {
                    continue;
                }
                itemService.readVerified(stack)
                        .filter(data -> ItemTypes.BANKNOTE.equals(data.itemType()))
                        .ifPresent(notes::add);
            }
            if (notes.isEmpty()) {
                messages.send(player, "bank.no-notes");
                return;
            }
            String idempotencyKey = "atm-d-" + UUID.randomUUID();
            bankingService.depositNotes(player.getUniqueId(), account.id(), notes, idempotencyKey)
                    .thenAccept(result -> databaseManager.db().onMain(() -> {
                        if (!player.isOnline() || result.status() != LedgerService.Status.COMPLETED) {
                            if (player.isOnline()) {
                                messages.send(player, "bank.no-notes");
                            }
                            return;
                        }
                        removeRedeemedNotes(player, Set.copyOf(result.redeemedSerials()));
                        messages.send(player, "bank.deposit-ok",
                                Placeholder.unparsed("amount", Money.format(result.totalCents())),
                                Placeholder.unparsed("count",
                                        String.valueOf(result.redeemedSerials().size())));
                    }));
        });
    }

    public void sendStatement(Player player) {
        Account account = accountService.cachedPersonal(player.getUniqueId()).orElse(null);
        if (account == null) {
            messages.send(player, "general.db-unavailable");
            return;
        }
        bankingService.statement(account.id()).thenAccept(entries -> {
            messages.send(player, "bank.statement-header");
            for (var entry : entries) {
                messages.send(player, "bank.statement-entry",
                        Placeholder.unparsed("sign", entry.amount() >= 0 ? "+" : ""),
                        Placeholder.unparsed("amount", Money.format(entry.amount())),
                        Placeholder.unparsed("reason", entry.reason()),
                        Placeholder.unparsed("balance", Money.format(entry.balanceAfter())),
                        Placeholder.unparsed("date", entry.createdAt()));
            }
        });
    }

    public void sendLedgerFailure(Player player, LedgerService.Status status) {
        switch (status) {
            case INSUFFICIENT_FUNDS -> messages.send(player, "bank.insufficient-funds");
            case ACCOUNT_FROZEN -> messages.send(player, "bank.account-frozen");
            case ACCOUNT_NOT_FOUND -> messages.send(player, "bank.target-not-found");
            case DUPLICATE -> messages.send(player, "bank.duplicate-request");
            default -> messages.send(player, "bank.invalid-amount");
        }
    }

    // --- internals ---

    private GuiMenu buildMenu(Player player, Account account) {
        BankingConfig config = bankingService.config();
        Map<Integer, GuiButton> buttons = new HashMap<>();

        long balance = accountService.cachedBalance(account.id()).orElse(account.balance());
        buttons.put(4, GuiButton.of(
                icon(Material.GOLD_INGOT, "Saldo: " + Money.format(balance), NamedTextColor.YELLOW),
                (p, click) -> {}));

        int slot = 10;
        for (long quickAmount : config.quickAmountsCents()) {
            buttons.put(slot, GuiButton.of(
                    icon(Material.PAPER, "Preleva " + Money.format(quickAmount), NamedTextColor.GREEN),
                    (p, click) -> {
                        p.closeInventory();
                        withdraw(p, quickAmount);
                    }));
            slot += 2;
        }

        buttons.put(20, GuiButton.of(
                icon(Material.HOPPER, "Deposita tutte le banconote", NamedTextColor.AQUA),
                (p, click) -> {
                    p.closeInventory();
                    depositAll(p);
                }));
        buttons.put(24, GuiButton.of(
                icon(Material.WRITABLE_BOOK, "Ultimi movimenti", NamedTextColor.GOLD),
                (p, click) -> {
                    p.closeInventory();
                    sendStatement(p);
                }));

        return new GuiMenu() {
            @Override
            public Component title() {
                return Component.text("Bancomat AfterLife", NamedTextColor.DARK_GREEN);
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
                return "afterlife.bank.user";
            }
        };
    }

    private ItemStack icon(Material material, String text, NamedTextColor color) {
        ItemStack stack = new ItemStack(material);
        var meta = stack.getItemMeta();
        meta.displayName(Component.text(text, color).decoration(TextDecoration.ITALIC, false));
        stack.setItemMeta(meta);
        return stack;
    }

    private boolean nearAtm(Player player, BankingConfig config) {
        double rangeSquared = config.atmRangeBlocks() * config.atmRangeBlocks();
        for (Poi poi : poiService.all()) {
            if (!config.atmPoiTypes().contains(poi.type())
                    || !poi.world().equals(player.getWorld().getName())) {
                continue;
            }
            var location = poi.location();
            if (location != null && location.distanceSquared(player.getLocation()) <= rangeSquared) {
                return true;
            }
        }
        return false;
    }

    private SerializedItemService.PdcData findHeldCard(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null) {
                continue;
            }
            var data = itemService.readVerified(stack)
                    .filter(d -> ItemTypes.CREDIT_CARD.equals(d.itemType()));
            if (data.isPresent()) {
                return data.get();
            }
        }
        return null;
    }

    private int emptySlots(Player player) {
        int empty = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                empty++;
            }
        }
        return empty;
    }

    private void removeRedeemedNotes(Player player, Set<UUID> redeemedSerials) {
        Set<UUID> remaining = new HashSet<>(redeemedSerials);
        var contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null) {
                continue;
            }
            var data = itemService.readVerified(stack);
            // Copies share the redeemed serial: every instance becomes worthless, so
            // all of them are removed (anti-duplication cleanup).
            if (data.isPresent() && remaining.contains(data.get().serial())) {
                player.getInventory().setItem(i, null);
            }
        }
    }
}
