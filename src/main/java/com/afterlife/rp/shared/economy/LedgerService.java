package com.afterlife.rp.shared.economy;

import com.afterlife.rp.database.DatabaseManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The shared double-entry ledger (rule 3): every clean-money movement goes
 * through this service. Entries must balance to zero; account rows are locked
 * in deterministic order; the idempotency key makes replays fail safely
 * (rules 4 and 13). Frozen accounts reject debits unless the use case
 * explicitly overrides (bank director seizures).
 */
public final class LedgerService {

    /** amount > 0 credits the account, amount < 0 debits it. */
    public record Line(UUID accountId, long amount) {}

    public enum Status { COMPLETED, DUPLICATE, INSUFFICIENT_FUNDS, ACCOUNT_FROZEN, ACCOUNT_NOT_FOUND, INVALID }

    public record Result(Status status, UUID transactionId, Map<UUID, Long> newBalances) {
        static Result failure(Status status) {
            return new Result(status, null, Map.of());
        }
    }

    /**
     * Aborts a composed transaction with a typed business failure; the
     * surrounding SQL transaction rolls back entirely.
     */
    public static final class LedgerAbort extends RuntimeException {
        private final Status status;

        public LedgerAbort(Status status) {
            super(status.name(), null, false, false);
            this.status = status;
        }

        public Status status() {
            return status;
        }
    }

    private final DatabaseManager databaseManager;
    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final Consumer<Map<UUID, Long>> balanceListener;

    public LedgerService(
            DatabaseManager databaseManager,
            AccountRepository accountRepository,
            LedgerRepository ledgerRepository,
            Consumer<Map<UUID, Long>> balanceListener) {
        this.databaseManager = databaseManager;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.balanceListener = balanceListener;
    }

    /** Standalone transfer in its own SQL transaction. */
    public CompletableFuture<Result> execute(
            String idempotencyKey,
            String reason,
            UUID actor,
            String description,
            List<Line> lines,
            boolean overrideFrozen) {
        return databaseManager.db().<Result>inTransaction(connection ->
                apply(connection, idempotencyKey, reason, actor, description, lines, overrideFrozen))
                .exceptionally(e -> {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (cause instanceof LedgerAbort abort) {
                        return Result.failure(abort.status());
                    }
                    throw e instanceof RuntimeException runtime ? runtime : new RuntimeException(e);
                })
                .thenApply(this::publish);
    }

    /**
     * Composable core: runs inside the caller's transaction and THROWS
     * {@link LedgerAbort} on any non-completed outcome so the whole unit of
     * work rolls back together. Callers of composed flows must invoke
     * {@link #publish(Result)} after their transaction commits.
     */
    public Result apply(
            Connection connection,
            String idempotencyKey,
            String reason,
            UUID actor,
            String description,
            List<Line> lines,
            boolean overrideFrozen) throws SQLException {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80
                || lines.size() < 2) {
            throw new LedgerAbort(Status.INVALID);
        }
        long sum = 0;
        Map<UUID, Long> netByAccount = new HashMap<>();
        for (Line line : lines) {
            if (line.amount() == 0) {
                throw new LedgerAbort(Status.INVALID);
            }
            sum += line.amount();
            netByAccount.merge(line.accountId(), line.amount(), Long::sum);
        }
        if (sum != 0) {
            throw new LedgerAbort(Status.INVALID);
        }

        UUID transactionId = UUID.randomUUID();
        if (!ledgerRepository.insertTransaction(
                connection, transactionId, idempotencyKey, reason, actor, description)) {
            throw new LedgerAbort(Status.DUPLICATE);
        }

        List<Account> locked = accountRepository.lockAll(connection, List.copyOf(netByAccount.keySet()));
        if (locked.size() != netByAccount.size()) {
            throw new LedgerAbort(Status.ACCOUNT_NOT_FOUND);
        }

        Map<UUID, Long> newBalances = new HashMap<>();
        for (Account account : locked) {
            long net = netByAccount.get(account.id());
            if (net < 0 && account.frozen() && !overrideFrozen) {
                throw new LedgerAbort(Status.ACCOUNT_FROZEN);
            }
            long newBalance = account.balance() + net;
            if (newBalance < 0 && !account.allowNegative()) {
                throw new LedgerAbort(Status.INSUFFICIENT_FUNDS);
            }
            newBalances.put(account.id(), newBalance);
        }
        for (Line line : lines) {
            ledgerRepository.insertEntry(connection, transactionId,
                    line.accountId(), line.amount(), newBalances.get(line.accountId()));
        }
        for (Map.Entry<UUID, Long> entry : newBalances.entrySet()) {
            accountRepository.updateBalance(connection, entry.getKey(), entry.getValue());
        }
        return new Result(Status.COMPLETED, transactionId, Map.copyOf(newBalances));
    }

    /** Pushes committed balances to the cache listener; returns the result unchanged. */
    public Result publish(Result result) {
        if (result.status() == Status.COMPLETED && balanceListener != null) {
            balanceListener.accept(result.newBalances());
        }
        return result;
    }

    /** Maps a composed-flow exception to a failure Result, rethrowing unknown errors. */
    public static Result failureFrom(Throwable e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        if (cause instanceof LedgerAbort abort) {
            return Result.failure(abort.status());
        }
        throw e instanceof RuntimeException runtime ? runtime : new RuntimeException(e);
    }
}
