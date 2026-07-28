package com.afterlife.rp.integration;

import com.afterlife.rp.database.DatabaseManager;
import com.afterlife.rp.shared.economy.Account;
import com.afterlife.rp.shared.economy.AccountService;
import com.afterlife.rp.shared.economy.LedgerService;
import com.afterlife.rp.shared.economy.Money;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * Registers the AfterLife ledger as THE Vault economy provider (§2.1: no
 * second authoritative economy). Vault's API is synchronous, so reads come
 * from the balance cache (populated at join) and writes are validated against
 * the cache, then applied through the ledger asynchronously with the
 * government budget as counterparty — third-party plugins conceptually mint
 * or burn money against it. See ADR 0003.
 */
public final class VaultEconomyBridge implements Economy {

    private final DatabaseManager databaseManager;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final Logger logger;

    public VaultEconomyBridge(
            DatabaseManager databaseManager,
            AccountService accountService,
            LedgerService ledgerService,
            Logger logger) {
        this.databaseManager = databaseManager;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.logger = logger;
    }

    private Optional<Account> account(OfflinePlayer player) {
        Optional<Account> cached = accountService.cachedPersonal(player.getUniqueId());
        if (cached.isPresent() || Bukkit.isPrimaryThread()) {
            return cached;
        }
        try {
            // Off the main thread we may block briefly for offline players.
            return accountService.findPersonal(player.getUniqueId()).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private EconomyResponse move(OfflinePlayer player, double amount, boolean deposit, String reason) {
        if (!databaseManager.ready()) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "database non disponibile");
        }
        long cents = Math.round(amount * 100.0);
        if (cents <= 0) {
            return new EconomyResponse(0, getBalance(player),
                    EconomyResponse.ResponseType.FAILURE, "importo non valido");
        }
        Optional<Account> account = account(player);
        if (account.isEmpty()) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "conto non trovato");
        }
        long balance = accountService.cachedBalance(account.get().id()).orElse(account.get().balance());
        if (!deposit && balance < cents) {
            return new EconomyResponse(0, balance / 100.0,
                    EconomyResponse.ResponseType.FAILURE, "fondi insufficienti");
        }
        if (!deposit && account.get().frozen()) {
            return new EconomyResponse(0, balance / 100.0,
                    EconomyResponse.ResponseType.FAILURE, "conto congelato");
        }
        UUID government = accountService.system(AccountService.SYSTEM_GOVERNMENT).id();
        long playerDelta = deposit ? cents : -cents;
        ledgerService.execute("vault-" + UUID.randomUUID(), reason, player.getUniqueId(), null,
                List.of(new LedgerService.Line(account.get().id(), playerDelta),
                        new LedgerService.Line(government, -playerDelta)),
                false)
                .thenAccept(result -> {
                    if (result.status() != LedgerService.Status.COMPLETED) {
                        logger.log(Level.WARNING, "Vault " + reason + " failed for "
                                + player.getUniqueId() + ": " + result.status());
                    }
                });
        // Optimistic response; the committed balance follows through the cache listener.
        return new EconomyResponse(cents / 100.0, (balance + playerDelta) / 100.0,
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    // --- identity ---

    @Override
    public boolean isEnabled() {
        return databaseManager.ready();
    }

    @Override
    public String getName() {
        return "AfterLifeRP";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(double amount) {
        return Money.format(Math.round(amount * 100.0));
    }

    @Override
    public String currencyNamePlural() {
        return "euro";
    }

    @Override
    public String currencyNameSingular() {
        return "euro";
    }

    // --- accounts ---

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return account(player).isPresent();
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean hasAccount(String playerName) {
        return hasAccount(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        if (!databaseManager.ready()) {
            return false;
        }
        accountService.getOrCreatePersonal(player.getUniqueId());
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean createPlayerAccount(String playerName) {
        return createPlayerAccount(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(Bukkit.getOfflinePlayer(playerName));
    }

    // --- balances ---

    @Override
    public double getBalance(OfflinePlayer player) {
        Optional<Account> account = account(player);
        if (account.isEmpty()) {
            return 0;
        }
        return accountService.cachedBalance(account.get().id()).orElse(account.get().balance()) / 100.0;
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    @SuppressWarnings("deprecation")
    public double getBalance(String playerName) {
        return getBalance(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    @SuppressWarnings("deprecation")
    public double getBalance(String playerName, String world) {
        return getBalance(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean has(String playerName, double amount) {
        return has(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean has(String playerName, String worldName, double amount) {
        return has(Bukkit.getOfflinePlayer(playerName), amount);
    }

    // --- movements ---

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return move(player, amount, false, "VAULT_WITHDRAW");
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    @SuppressWarnings("deprecation")
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    @SuppressWarnings("deprecation")
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return move(player, amount, true, "VAULT_DEPOSIT");
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    @SuppressWarnings("deprecation")
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    @SuppressWarnings("deprecation")
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    // --- banks: not supported (organization treasuries use the ledger directly) ---

    private EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                "le banche Vault non sono supportate");
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    @SuppressWarnings("deprecation")
    public EconomyResponse createBank(String name, String player) {
        return noBanks();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    @SuppressWarnings("deprecation")
    public EconomyResponse isBankOwner(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    @SuppressWarnings("deprecation")
    public EconomyResponse isBankMember(String name, String playerName) {
        return noBanks();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}
