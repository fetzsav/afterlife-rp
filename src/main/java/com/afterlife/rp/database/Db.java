package com.afterlife.rp.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * Async gateway to the database. Rule 1: all SQL runs on the pool executor,
 * never on the server thread. Rule 2: Bukkit mutations continue on {@link #onMain}.
 */
public final class Db {

    private final DataSource dataSource;
    private final ExecutorService async;
    private final Executor main;

    public Db(DataSource dataSource, ExecutorService async, Executor main) {
        this.dataSource = dataSource;
        this.async = async;
        this.main = main;
    }

    public <T> CompletableFuture<T> supply(SqlFunction<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return work.apply(connection);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, async);
    }

    /** Runs work in a single SQL transaction; rolls back on any exception. */
    public <T> CompletableFuture<T> inTransaction(SqlFunction<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    T result = work.apply(connection);
                    connection.commit();
                    return result;
                } catch (SQLException e) {
                    connection.rollback();
                    throw new CompletionException(e);
                } catch (RuntimeException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(previousAutoCommit);
                }
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, async);
    }

    /** Schedules a continuation on the server main thread. */
    public void onMain(Runnable task) {
        main.execute(task);
    }

    public Executor mainExecutor() {
        return main;
    }

    public void shutdown() {
        async.shutdown();
        try {
            if (!async.awaitTermination(5, TimeUnit.SECONDS)) {
                async.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            async.shutdownNow();
        }
    }
}
