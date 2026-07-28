package com.afterlife.rp.shared.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL access for ledger transactions and entries. Runs off the main thread. */
public final class LedgerRepository {

    public record StatementEntry(
            long amount, long balanceAfter, String reason, String description, String createdAt) {}

    /** Returns false when the idempotency key already exists (replay). */
    public boolean insertTransaction(Connection connection, UUID id, String idempotencyKey,
            String reason, UUID actor, String description) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ledger_transactions (id, idempotency_key, reason, actor_uuid, description) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, id.toString());
            statement.setString(2, idempotencyKey);
            statement.setString(3, reason);
            statement.setString(4, actor == null ? null : actor.toString());
            statement.setString(5, description);
            statement.executeUpdate();
            return true;
        } catch (SQLIntegrityConstraintViolationException e) {
            return false;
        }
    }

    public void insertEntry(Connection connection, UUID transactionId, UUID accountId,
            long amount, long balanceAfter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ledger_entries (transaction_id, account_id, amount, balance_after) "
                        + "VALUES (?, ?, ?, ?)")) {
            statement.setString(1, transactionId.toString());
            statement.setString(2, accountId.toString());
            statement.setLong(3, amount);
            statement.setLong(4, balanceAfter);
            statement.executeUpdate();
        }
    }

    public List<StatementEntry> lastEntries(Connection connection, UUID accountId, int limit)
            throws SQLException {
        List<StatementEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT e.amount, e.balance_after, t.reason, t.description, e.created_at "
                        + "FROM ledger_entries e "
                        + "JOIN ledger_transactions t ON t.id = e.transaction_id "
                        + "WHERE e.account_id = ? ORDER BY e.id DESC LIMIT ?")) {
            statement.setString(1, accountId.toString());
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    entries.add(new StatementEntry(
                            rs.getLong("amount"),
                            rs.getLong("balance_after"),
                            rs.getString("reason"),
                            rs.getString("description"),
                            rs.getTimestamp("created_at").toString()));
                }
            }
        }
        return entries;
    }
}
