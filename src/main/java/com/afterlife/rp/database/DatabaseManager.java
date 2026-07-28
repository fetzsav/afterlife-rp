package com.afterlife.rp.database;

import com.afterlife.rp.config.DatabaseSettings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/** Owns the connection pool, Flyway migrations, and database health state. */
public final class DatabaseManager {

    public enum State { STARTING, READY, FAILED }

    public record Health(boolean ok, long latencyMs, String detail) {}

    private final DatabaseSettings settings;
    private final ClassLoader pluginClassLoader;
    private final Executor mainExecutor;
    private final Logger logger;

    private volatile State state = State.STARTING;
    private volatile String failureDetail = "starting";
    private HikariDataSource dataSource;
    private Db db;

    public DatabaseManager(
            DatabaseSettings settings,
            ClassLoader pluginClassLoader,
            Executor mainExecutor,
            Logger logger) {
        this.settings = settings;
        this.pluginClassLoader = pluginClassLoader;
        this.mainExecutor = mainExecutor;
        this.logger = logger;
    }

    /** Connects and migrates off-thread; completes on the async worker. */
    public CompletableFuture<State> start() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HikariConfig config = new HikariConfig();
                config.setPoolName("AfterLifeRP-Hikari");
                config.setJdbcUrl(settings.jdbcUrl());
                config.setUsername(settings.user());
                config.setPassword(settings.password());
                config.setMaximumPoolSize(settings.poolSize());
                config.setConnectionTimeout(settings.connectTimeoutMs());
                config.setDriverClassName("org.mariadb.jdbc.Driver");
                dataSource = new HikariDataSource(config);

                MigrateResult result = Flyway.configure(pluginClassLoader)
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
                logger.info("Flyway: " + result.migrationsExecuted + " migration(s) executed, schema at "
                        + result.targetSchemaVersion);

                AtomicInteger threadId = new AtomicInteger();
                ExecutorService async = Executors.newFixedThreadPool(
                        Math.max(2, settings.poolSize()),
                        r -> {
                            Thread t = new Thread(r, "AfterLifeRP-DB-" + threadId.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        });
                db = new Db(dataSource, async, mainExecutor);
                state = State.READY;
                return state;
            } catch (Exception e) {
                state = State.FAILED;
                failureDetail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                logger.log(Level.SEVERE, "Database startup failed — authoritative features disabled", e);
                if (dataSource != null) {
                    dataSource.close();
                    dataSource = null;
                }
                return state;
            }
        });
    }

    public State state() {
        return state;
    }

    public boolean ready() {
        return state == State.READY;
    }

    /** Must only be called when {@link #ready()}. */
    public Db db() {
        if (db == null) {
            throw new IllegalStateException("Database not ready (state=" + state + ")");
        }
        return db;
    }

    public CompletableFuture<Health> health() {
        if (!ready()) {
            return CompletableFuture.completedFuture(new Health(false, -1, failureDetail));
        }
        long started = System.nanoTime();
        return db.supply(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }
            return new Health(true, (System.nanoTime() - started) / 1_000_000, "ok");
        }).exceptionally(e -> new Health(false, -1, e.getCause() == null
                ? e.getMessage()
                : e.getCause().getMessage()));
    }

    public void shutdown() {
        if (db != null) {
            db.shutdown();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
