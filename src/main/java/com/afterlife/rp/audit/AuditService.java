package com.afterlife.rp.audit;

import com.afterlife.rp.database.DatabaseManager;
import com.google.gson.Gson;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-only audit trail (rule 9). This service only INSERTs; nothing in the
 * codebase may UPDATE or DELETE audit rows.
 */
public final class AuditService {

    private final DatabaseManager databaseManager;
    private final Logger logger;
    private final Gson gson = new Gson();

    public AuditService(DatabaseManager databaseManager, Logger logger) {
        this.databaseManager = databaseManager;
        this.logger = logger;
    }

    public CompletableFuture<Void> log(
            UUID actor, String actorName, String action, String target, Map<String, ?> context) {
        String contextJson = context == null || context.isEmpty() ? null : gson.toJson(context);
        return databaseManager.db().<Void>supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO audit_events (actor_uuid, actor_name, action, target, context) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, actor == null ? null : actor.toString());
                statement.setString(2, actorName);
                statement.setString(3, action);
                statement.setString(4, target);
                statement.setString(5, contextJson);
                statement.executeUpdate();
            }
            return null;
        }).exceptionally(e -> {
            logger.log(Level.SEVERE, "Failed to write audit event " + action + " target=" + target, e);
            return null;
        });
    }
}
