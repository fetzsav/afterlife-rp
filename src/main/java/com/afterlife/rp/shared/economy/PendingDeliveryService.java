package com.afterlife.rp.shared.economy;

import com.afterlife.rp.database.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Durable recovery for item handouts that could not complete (§7.4 step 8).
 * A row transitions PENDING -> DELIVERED exactly once, even across restarts.
 */
public final class PendingDeliveryService {

    public record Pending(
            UUID id, UUID playerUuid, String itemType, Long denomination,
            int quantity, String reason, UUID transactionId, int version) {}

    private final DatabaseManager databaseManager;

    public PendingDeliveryService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void insert(Connection connection, UUID playerUuid, String itemType,
            Long denomination, int quantity, String reason, UUID transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO pending_deliveries "
                        + "(id, player_uuid, item_type, denomination, quantity, reason, transaction_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, playerUuid.toString());
            statement.setString(3, itemType);
            if (denomination == null) {
                statement.setNull(4, java.sql.Types.BIGINT);
            } else {
                statement.setLong(4, denomination);
            }
            statement.setInt(5, quantity);
            statement.setString(6, reason);
            statement.setString(7, transactionId == null ? null : transactionId.toString());
            statement.executeUpdate();
        }
    }

    public CompletableFuture<Void> insertStandalone(UUID playerUuid, String itemType,
            Long denomination, int quantity, String reason, UUID transactionId) {
        return databaseManager.db().inTransaction(connection -> {
            insert(connection, playerUuid, itemType, denomination, quantity, reason, transactionId);
            return null;
        });
    }

    public CompletableFuture<List<Pending>> pendingFor(UUID playerUuid) {
        return databaseManager.db().supply(connection -> {
            List<Pending> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, player_uuid, item_type, denomination, quantity, reason, "
                            + "transaction_id, version FROM pending_deliveries "
                            + "WHERE player_uuid = ? AND status = 'PENDING'")) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        long denomination = rs.getLong("denomination");
                        boolean denominationNull = rs.wasNull();
                        String transactionId = rs.getString("transaction_id");
                        result.add(new Pending(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("player_uuid")),
                                rs.getString("item_type"),
                                denominationNull ? null : denomination,
                                rs.getInt("quantity"),
                                rs.getString("reason"),
                                transactionId == null ? null : UUID.fromString(transactionId),
                                rs.getInt("version")));
                    }
                }
            }
            return result;
        });
    }

    /** One-winner claim; false when another worker (or a previous restart) won. */
    public CompletableFuture<Boolean> markDelivered(UUID id, int expectedVersion) {
        return databaseManager.db().inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pending_deliveries SET status = 'DELIVERED', "
                            + "delivered_at = CURRENT_TIMESTAMP(3), version = version + 1 "
                            + "WHERE id = ? AND status = 'PENDING' AND version = ?")) {
                statement.setString(1, id.toString());
                statement.setInt(2, expectedVersion);
                return statement.executeUpdate() == 1;
            }
        });
    }
}
