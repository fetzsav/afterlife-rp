package com.afterlife.rp.module.legal;

import com.afterlife.rp.config.Messages;
import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.items.SerializedItemService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
 * Legal module commands (§9.2). Italian names; validation per rule 14.
 * /contratto, /valida_contratto, /fedina, /pulisci_fedina, /arresto,
 * /rilascio, /avvocato, /ricorso, /prova, /licenza.
 */
public final class LegalCommands implements CommandExecutor, TabCompleter {

    private static final String PERM_LAWYER = "afterlife.legal.lawyer";
    private static final String PERM_POLICE = "afterlife.police.officer";

    private record Proposal(UUID proposer, UUID target, String content, Instant createdAt) {}

    private final DatabaseManager databaseManager;
    private final AccountService accountService;
    private final LegalService legalService;
    private final SerializedItemService itemService;
    private final Messages messages;
    private final Map<UUID, Proposal> proposalsByTarget = new ConcurrentHashMap<>();

    public LegalCommands(
            DatabaseManager databaseManager,
            AccountService accountService,
            LegalService legalService,
            SerializedItemService itemService,
            Messages messages) {
        this.databaseManager = databaseManager;
        this.accountService = accountService;
        this.legalService = legalService;
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
            case "contratto" -> contratto(player, args);
            case "valida_contratto" -> validaContratto(player);
            case "fedina" -> fedina(player, args);
            case "pulisci_fedina" -> pulisciFedina(player, args);
            case "arresto" -> arresto(player, args);
            case "rilascio" -> rilascio(player, args);
            case "avvocato" -> avvocato(player);
            case "ricorso" -> ricorso(player, args);
            case "prova" -> prova(player, args);
            case "licenza" -> licenza(player);
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

    // --- contracts ---

    private void contratto(Player player, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("proponi")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null || target.getUniqueId().equals(player.getUniqueId())) {
                messages.send(player, "bank.target-not-found");
                return;
            }
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held.getType() != Material.WRITABLE_BOOK && held.getType() != Material.WRITTEN_BOOK) {
                messages.send(player, "legal.contract-hold-book");
                return;
            }
            BookMeta book = (BookMeta) held.getItemMeta();
            StringBuilder content = new StringBuilder();
            for (Component page : book.pages()) {
                content.append(PlainTextComponentSerializer.plainText().serialize(page)).append('\n');
            }
            if (content.isEmpty()
                    || content.length() > legalService.config().contractMaxContentChars()) {
                messages.send(player, "legal.contract-content-invalid");
                return;
            }
            proposalsByTarget.put(target.getUniqueId(), new Proposal(
                    player.getUniqueId(), target.getUniqueId(), content.toString(), Instant.now()));
            messages.send(player, "legal.contract-proposed",
                    Placeholder.unparsed("player", target.getName()));
            messages.send(target, "legal.contract-received",
                    Placeholder.unparsed("player", player.getName()));
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("firma")) {
            Proposal proposal = proposalsByTarget.remove(player.getUniqueId());
            if (proposal == null || Duration.between(proposal.createdAt(), Instant.now()).getSeconds()
                    > legalService.config().contractProposalTimeoutSeconds()) {
                messages.send(player, "legal.contract-no-proposal");
                return;
            }
            legalService.createSignedContract(proposal.proposer(), player.getUniqueId(),
                            proposal.content())
                    .thenAccept(item -> databaseManager.db().onMain(() -> {
                        Player proposer = Bukkit.getPlayer(proposal.proposer());
                        ItemStack stack = itemService.toItemStack(item, Material.PAPER,
                                Component.text("Contratto firmato", NamedTextColor.YELLOW)
                                        .decoration(TextDecoration.ITALIC, false));
                        Player recipient = proposer != null ? proposer : player;
                        recipient.getInventory().addItem(stack).values().forEach(rest ->
                                recipient.getWorld().dropItemNaturally(recipient.getLocation(), rest));
                        messages.send(player, "legal.contract-signed");
                        if (proposer != null) {
                            messages.send(proposer, "legal.contract-signed");
                        }
                    }));
            return;
        }
        messages.send(player, "legal.contract-usage");
    }

    private void validaContratto(Player player) {
        if (!requirePermission(player, PERM_LAWYER)) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        var data = itemService.readVerified(held)
                .filter(d -> LegalService.ITEM_TYPE_CONTRACT.equals(d.itemType()))
                .orElse(null);
        if (data == null) {
            messages.send(player, "legal.contract-hold-contract");
            return;
        }
        databaseManager.db()
                .supply(connection -> new com.afterlife.rp.shared.items.SerializedItemRepository()
                        .find(connection, data.serial()))
                .thenAccept(record -> {
                    var contractId = record.flatMap(r ->
                            legalService.contractIdFromMetadata(r.metadata()));
                    if (contractId.isEmpty()) {
                        databaseManager.db().onMain(() ->
                                messages.send(player, "legal.contract-hold-contract"));
                        return;
                    }
                    legalService.validateContract(contractId.get(), player.getUniqueId(),
                                    player.getName())
                            .thenAccept(validated -> databaseManager.db().onMain(() -> {
                                if (!player.isOnline()) {
                                    return;
                                }
                                if (!validated) {
                                    messages.send(player, "legal.contract-already-validated");
                                    return;
                                }
                                ItemStack inHand = player.getInventory().getItemInMainHand();
                                var meta = inHand.getItemMeta();
                                meta.displayName(Component.text("Contratto validato ⚖",
                                                NamedTextColor.GOLD)
                                        .decoration(TextDecoration.ITALIC, false));
                                meta.setEnchantmentGlintOverride(true);
                                inHand.setItemMeta(meta);
                                messages.send(player, "legal.contract-validated");
                            }));
                });
    }

    // --- criminal records ---

    private void fedina(Player player, String[] args) {
        if (args.length >= 4 && args[0].equalsIgnoreCase("aggiungi")) {
            if (!requirePermission(player, PERM_POLICE)) {
                return;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            String severity = args[2].toUpperCase(Locale.ROOT);
            if (target == null || (!severity.equals("MINOR") && !severity.equals("MAJOR"))) {
                messages.send(player, "legal.fedina-usage");
                return;
            }
            String charge = String.join(" ", List.of(args).subList(3, args.length));
            legalService.addRecord(target.getUniqueId(), severity, charge,
                            player.getUniqueId(), player.getName())
                    .thenAccept(id -> messages.send(player, "legal.record-added",
                            Placeholder.unparsed("id", String.valueOf(id)),
                            Placeholder.unparsed("player", target.getName())));
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sconta")) {
            if (!requirePermission(player, PERM_POLICE)) {
                return;
            }
            try {
                long id = Long.parseLong(args[1]);
                legalService.markServed(id, player.getUniqueId(), player.getName())
                        .thenAccept(updated -> messages.send(player,
                                updated ? "legal.record-served" : "legal.record-not-found",
                                Placeholder.unparsed("id", args[1])));
            } catch (NumberFormatException e) {
                messages.send(player, "legal.fedina-usage");
            }
            return;
        }
        // View: self, or another player with police permission.
        UUID subject = player.getUniqueId();
        String subjectName = player.getName();
        if (args.length == 1) {
            if (!requirePermission(player, PERM_POLICE)) {
                return;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                messages.send(player, "bank.target-not-found");
                return;
            }
            subject = target.getUniqueId();
            subjectName = target.getName();
        }
        String finalName = subjectName;
        legalService.activeRecords(subject).thenAccept(records -> {
            messages.send(player, "legal.fedina-header",
                    Placeholder.unparsed("player", finalName),
                    Placeholder.unparsed("count", String.valueOf(records.size())));
            for (CriminalRecord record : records) {
                messages.send(player, "legal.fedina-entry",
                        Placeholder.unparsed("id", String.valueOf(record.id())),
                        Placeholder.unparsed("severity", record.severity()),
                        Placeholder.unparsed("status", record.status()),
                        Placeholder.unparsed("charge", record.charge()));
            }
        });
    }

    private void pulisciFedina(Player player, String[] args) {
        if (!requirePermission(player, PERM_LAWYER)) {
            return;
        }
        if (args.length != 1) {
            messages.send(player, "legal.pulisci-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.send(player, "bank.target-not-found");
            return;
        }
        var account = accountService.cachedPersonal(target.getUniqueId()).orElse(null);
        if (account == null) {
            messages.send(player, "general.db-unavailable");
            return;
        }
        legalService.expunge(target.getUniqueId(), account.id(), player.getUniqueId(), player.getName())
                .thenAccept(result -> databaseManager.db().onMain(() -> messages.send(player,
                        switch (result) {
                            case COMPLETED -> "legal.expunge-ok";
                            case NO_RECORDS -> "legal.expunge-no-records";
                            case PAYMENT_FAILED -> "legal.expunge-payment-failed";
                            default -> "legal.expunge-not-eligible";
                        },
                        Placeholder.unparsed("player", target.getName()))));
    }

    // --- detention ---

    private void arresto(Player player, String[] args) {
        if (!requirePermission(player, PERM_POLICE)) {
            return;
        }
        if (args.length < 1) {
            messages.send(player, "legal.arresto-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.send(player, "bank.target-not-found");
            return;
        }
        int minutes = legalService.config().detentionDefaultMinutes();
        if (args.length >= 2) {
            try {
                minutes = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                messages.send(player, "legal.arresto-usage");
                return;
            }
        }
        legalService.arrest(target.getUniqueId(), player.getUniqueId(), player.getName(), minutes)
                .thenAccept(created -> databaseManager.db().onMain(() -> {
                    if (created) {
                        messages.send(player, "legal.arrest-ok",
                                Placeholder.unparsed("player", target.getName()));
                        if (target.isOnline()) {
                            messages.send(target, "legal.arrest-notice");
                        }
                    } else {
                        messages.send(player, "legal.arrest-already");
                    }
                }));
    }

    private void rilascio(Player player, String[] args) {
        if (!requirePermission(player, PERM_POLICE)) {
            return;
        }
        if (args.length != 1) {
            messages.send(player, "legal.rilascio-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.send(player, "bank.target-not-found");
            return;
        }
        legalService.release(target.getUniqueId(), player.getUniqueId(), player.getName(), "POLICE")
                .thenAccept(released -> messages.send(player,
                        released ? "legal.release-ok" : "legal.release-none",
                        Placeholder.unparsed("player", target.getName())));
    }

    private void avvocato(Player player) {
        legalService.flagLawyerCalled(player.getUniqueId()).thenAccept(flagged ->
                databaseManager.db().onMain(() -> {
                    if (!flagged) {
                        messages.send(player, "legal.lawyer-call-none");
                        return;
                    }
                    messages.send(player, "legal.lawyer-call-ok");
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (online.hasPermission(PERM_LAWYER)) {
                            messages.send(online, "legal.lawyer-call-broadcast",
                                    Placeholder.unparsed("player", player.getName()));
                        }
                    }
                }));
    }

    private void ricorso(Player player, String[] args) {
        if (!requirePermission(player, PERM_LAWYER)) {
            return;
        }
        if (args.length != 1) {
            messages.send(player, "legal.ricorso-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.send(player, "bank.target-not-found");
            return;
        }
        legalService.appeal(target.getUniqueId(), player.getUniqueId(), player.getName())
                .thenAccept(released -> messages.send(player,
                        released ? "legal.appeal-ok" : "legal.appeal-rejected",
                        Placeholder.unparsed("player", target.getName())));
    }

    // --- evidence ---

    private void prova(Player player, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("crea")) {
            if (!requirePermission(player, PERM_POLICE)) {
                return;
            }
            String description = String.join(" ", List.of(args).subList(1, args.length));
            ItemStack held = player.getInventory().getItemInMainHand();
            UUID serial = itemService.readVerified(held)
                    .map(SerializedItemService.PdcData::serial).orElse(null);
            legalService.createEvidence(description, serial, player.getUniqueId(), player.getName())
                    .thenAccept(id -> databaseManager.db().onMain(() -> {
                        if (serial != null && player.isOnline()) {
                            // Confiscated item leaves circulation (chain of custody).
                            player.getInventory().setItemInMainHand(null);
                        }
                        messages.send(player, "legal.evidence-created",
                                Placeholder.unparsed("id", String.valueOf(id)));
                    }));
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("vedi")) {
            long id;
            try {
                id = Long.parseLong(args[1]);
            } catch (NumberFormatException e) {
                messages.send(player, "legal.prova-usage");
                return;
            }
            boolean authorized = player.hasPermission(PERM_POLICE);
            legalService.viewEvidence(id, player.getUniqueId(), player.getName(), authorized)
                    .thenAccept(view -> databaseManager.db().onMain(() -> {
                        if (view.isEmpty()) {
                            messages.send(player, authorized
                                    ? "legal.evidence-not-found" : "legal.evidence-denied");
                            return;
                        }
                        messages.send(player, "legal.evidence-view",
                                Placeholder.unparsed("id", String.valueOf(view.get().id())),
                                Placeholder.unparsed("description", view.get().description()),
                                Placeholder.unparsed("status", view.get().status()));
                    }));
            return;
        }
        messages.send(player, "legal.prova-usage");
    }

    // --- licenses ---

    private void licenza(Player player) {
        legalService.activeLicenses(player.getUniqueId()).thenAccept(licenses -> {
            messages.send(player, "legal.licenses-header",
                    Placeholder.unparsed("count", String.valueOf(licenses.size())));
            for (String license : licenses) {
                messages.send(player, "legal.licenses-entry",
                        Placeholder.unparsed("license", license));
            }
        });
    }

    @Override
    public java.util.List<String> onTabComplete(@org.jetbrains.annotations.NotNull CommandSender sender,
            @org.jetbrains.annotations.NotNull Command command,
            @org.jetbrains.annotations.NotNull String alias, String @org.jetbrains.annotations.NotNull [] args) {
        String cmd = command.getName().toLowerCase(java.util.Locale.ROOT);
        switch (cmd) {
            case "contratto" -> {
                if (args.length == 1) {
                    return TabComplete.filter(java.util.List.of("proponi", "firma"), args[0]);
                }
                if (args.length == 2 && args[0].equalsIgnoreCase("proponi")) {
                    return TabComplete.players(args[1]);
                }
            }
            case "fedina" -> {
                if (args.length == 1) {
                    return TabComplete.filter(java.util.List.of("aggiungi", "sconta"), args[0]);
                }
                if (args.length == 2 && args[0].equalsIgnoreCase("aggiungi")) {
                    return TabComplete.players(args[1]);
                }
            }
            case "pulisci_fedina", "arresto", "rilascio", "ricorso" -> {
                if (args.length == 1) {
                    return TabComplete.players(args[0]);
                }
            }
            case "prova" -> {
                if (args.length == 1) {
                    return TabComplete.filter(java.util.List.of("crea", "vedi"), args[0]);
                }
            }
            default -> { }
        }
        return java.util.List.of();
    }

}
