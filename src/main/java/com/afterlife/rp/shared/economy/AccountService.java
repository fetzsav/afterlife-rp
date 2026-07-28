package com.afterlife.rp.shared.economy;

import com.afterlife.rp.audit.AuditService;
import com.afterlife.rp.database.DatabaseManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Personal, organization, and system accounts, plus the in-memory balance
 * cache used by the Vault bridge and displays. The database is authoritative;
 * the cache is refreshed by every committed ledger transaction.
 */
public final class AccountService {

    public static final String SYSTEM_CASH_ISSUANCE = "cash_issuance";
    public static final String SYSTEM_SEIZURE = "seizure";
    public static final String SYSTEM_GOVERNMENT = "government_budget";
    public static final String SYSTEM_CHECK_CLEARING = "check_clearing";

    private final DatabaseManager databaseManager;
    private final AccountRepository repository;
    private final AuditService auditService;
    private final String ibanAbi;
    private final String ibanCab;

    private final Map<UUID, Account> personalByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> balanceByAccount = new ConcurrentHashMap<>();
    private final Map<String, Account> systemByCode = new ConcurrentHashMap<>();

    public AccountService(
            DatabaseManager databaseManager,
            AccountRepository repository,
            AuditService auditService,
            String ibanAbi,
            String ibanCab) {
        this.databaseManager = databaseManager;
        this.repository = repository;
        this.auditService = auditService;
        this.ibanAbi = ibanAbi;
        this.ibanCab = ibanCab;
    }

    /** Applies committed ledger balances to the cache (wired as the ledger listener). */
    public void onLedgerCommit(Map<UUID, Long> newBalances) {
        newBalances.forEach((accountId, balance) -> {
            balanceByAccount.computeIfPresent(accountId, (id, old) -> balance);
            personalByPlayer.replaceAll((player, account) ->
                    account.id().equals(accountId)
                            ? new Account(account.id(), account.ownerType(), account.ownerRef(),
                                    account.code(), account.iban(), balance, account.allowNegative(),
                                    account.frozen(), account.frozenReason(), account.frozenBy(),
                                    account.version())
                            : account);
        });
    }

    /** First call creates the account with a fresh unique IBAN (retried on collision). */
    public CompletableFuture<Account> getOrCreatePersonal(UUID playerUuid) {
        Account cached = personalByPlayer.get(playerUuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return databaseManager.db().<Account>inTransaction(connection -> {
            Optional<Account> existing =
                    repository.findByOwner(connection, Account.OwnerType.PLAYER, playerUuid);
            if (existing.isPresent()) {
                return existing.get();
            }
            for (int attempt = 0; attempt < 5; attempt++) {
                Account candidate = new Account(UUID.randomUUID(), Account.OwnerType.PLAYER,
                        playerUuid, null, Iban.generate(ibanAbi, ibanCab), 0, false,
                        false, null, null, 0);
                if (repository.insert(connection, candidate)) {
                    return candidate;
                }
                // Collision: either the IBAN clashed (retry) or another thread
                // created the account (return it).
                Optional<Account> raced =
                        repository.findByOwner(connection, Account.OwnerType.PLAYER, playerUuid);
                if (raced.isPresent()) {
                    return raced.get();
                }
            }
            throw new SQLException("Could not allocate a unique IBAN after 5 attempts");
        }).thenApply(account -> {
            cache(playerUuid, account);
            return account;
        });
    }

    public Optional<Account> cachedPersonal(UUID playerUuid) {
        return Optional.ofNullable(personalByPlayer.get(playerUuid));
    }

    public Optional<Long> cachedBalance(UUID accountId) {
        return Optional.ofNullable(balanceByAccount.get(accountId));
    }

    public void evict(UUID playerUuid) {
        Account removed = personalByPlayer.remove(playerUuid);
        if (removed != null) {
            balanceByAccount.remove(removed.id());
        }
    }

    /** Loads the three system clearing accounts once at startup. */
    public CompletableFuture<Void> loadSystemAccounts() {
        return databaseManager.db().<Void>supply(connection -> {
            for (String code : new String[] {SYSTEM_CASH_ISSUANCE, SYSTEM_SEIZURE,
                    SYSTEM_GOVERNMENT, SYSTEM_CHECK_CLEARING}) {
                repository.findByCode(connection, code)
                        .ifPresent(account -> systemByCode.put(code, account));
            }
            return null;
        });
    }

    /** System accounts are seeded by migration; missing ones are a fatal defect. */
    public Account system(String code) {
        Account account = systemByCode.get(code);
        if (account == null) {
            throw new IllegalStateException("System account missing: " + code);
        }
        return account;
    }

    public CompletableFuture<Optional<Account>> findByIban(String iban) {
        return databaseManager.db().supply(connection -> repository.findByIban(connection, iban));
    }

    public CompletableFuture<Optional<Account>> findPersonal(UUID playerUuid) {
        Account cached = personalByPlayer.get(playerUuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        return databaseManager.db().supply(connection ->
                repository.findByOwner(connection, Account.OwnerType.PLAYER, playerUuid));
    }

    /** Freeze/unfreeze with mandatory reason and audit (§9.1 director). */
    public CompletableFuture<Boolean> setFrozen(
            UUID accountId, boolean frozen, String reason, UUID actor, String actorName) {
        return databaseManager.db().inTransaction(connection ->
                repository.setFrozen(connection, accountId, frozen, reason, actor))
                .thenApply(changed -> {
                    if (changed) {
                        auditService.log(actor, actorName,
                                frozen ? "ACCOUNT_FREEZE" : "ACCOUNT_UNFREEZE",
                                accountId.toString(), Map.of("reason", reason));
                        refreshCachedAccount(accountId);
                    }
                    return changed;
                });
    }

    /** Creates an organization with its treasury account. */
    public CompletableFuture<Account> createOrganization(
            String name, String type, String displayName, UUID actor, String actorName) {
        return databaseManager.db().<Account>inTransaction(connection -> {
            UUID orgId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO organizations (id, name, type, display_name) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, orgId.toString());
                statement.setString(2, name);
                statement.setString(3, type);
                statement.setString(4, displayName);
                statement.executeUpdate();
            }
            for (int attempt = 0; attempt < 5; attempt++) {
                Account candidate = new Account(UUID.randomUUID(), Account.OwnerType.ORGANIZATION,
                        orgId, null, Iban.generate(ibanAbi, ibanCab), 0, false,
                        false, null, null, 0);
                if (repository.insert(connection, candidate)) {
                    return candidate;
                }
            }
            throw new SQLException("Could not allocate a unique IBAN for organization " + name);
        }).thenApply(account -> {
            auditService.log(actor, actorName, "ORG_CREATE", name,
                    Map.of("type", type, "account", account.id().toString()));
            return account;
        });
    }

    private void cache(UUID playerUuid, Account account) {
        personalByPlayer.put(playerUuid, account);
        balanceByAccount.put(account.id(), account.balance());
    }

    private void refreshCachedAccount(UUID accountId) {
        databaseManager.db().supply(connection -> repository.findById(connection, accountId))
                .thenAccept(found -> found.ifPresent(account -> {
                    if (account.ownerType() == Account.OwnerType.PLAYER
                            && personalByPlayer.containsKey(account.ownerRef())) {
                        cache(account.ownerRef(), account);
                    }
                }));
    }
}
