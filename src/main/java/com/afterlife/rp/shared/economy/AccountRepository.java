package com.afterlife.rp.shared.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL access for accounts. Runs off the main thread. */
public final class AccountRepository {

    public Optional<Account> findByOwner(Connection connection, Account.OwnerType type, UUID ownerRef)
            throws SQLException {
        return queryOne(connection,
                "SELECT * FROM accounts WHERE owner_type = ? AND owner_ref = ?",
                statement -> {
                    statement.setString(1, type.name());
                    statement.setString(2, ownerRef.toString());
                });
    }

    public Optional<Account> findByCode(Connection connection, String code) throws SQLException {
        return queryOne(connection, "SELECT * FROM accounts WHERE code = ?",
                statement -> statement.setString(1, code));
    }

    public Optional<Account> findByIban(Connection connection, String iban) throws SQLException {
        return queryOne(connection, "SELECT * FROM accounts WHERE iban = ?",
                statement -> statement.setString(1, iban));
    }

    public Optional<Account> findById(Connection connection, UUID id) throws SQLException {
        return queryOne(connection, "SELECT * FROM accounts WHERE id = ?",
                statement -> statement.setString(1, id.toString()));
    }

    /** Locks and returns the account rows in deterministic order (deadlock avoidance). */
    public List<Account> lockAll(Connection connection, List<UUID> accountIds) throws SQLException {
        List<UUID> sorted = new ArrayList<>(accountIds);
        sorted.sort((a, b) -> a.toString().compareTo(b.toString()));
        List<Account> locked = new ArrayList<>(sorted.size());
        for (UUID id : sorted) {
            Optional<Account> account = queryOne(connection,
                    "SELECT * FROM accounts WHERE id = ? FOR UPDATE",
                    statement -> statement.setString(1, id.toString()));
            account.ifPresent(locked::add);
        }
        return locked;
    }

    /** Returns false on IBAN/owner uniqueness collision (caller may retry with a new IBAN). */
    public boolean insert(Connection connection, Account account) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO accounts (id, owner_type, owner_ref, code, iban, balance, "
                        + "allow_negative, frozen, frozen_reason, frozen_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, account.id().toString());
            statement.setString(2, account.ownerType().name());
            statement.setString(3, account.ownerRef() == null ? null : account.ownerRef().toString());
            statement.setString(4, account.code());
            statement.setString(5, account.iban());
            statement.setLong(6, account.balance());
            statement.setBoolean(7, account.allowNegative());
            statement.setBoolean(8, account.frozen());
            statement.setString(9, account.frozenReason());
            statement.setString(10, account.frozenBy() == null ? null : account.frozenBy().toString());
            statement.executeUpdate();
            return true;
        } catch (SQLIntegrityConstraintViolationException e) {
            return false;
        }
    }

    public void updateBalance(Connection connection, UUID accountId, long newBalance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance = ?, version = version + 1 WHERE id = ?")) {
            statement.setLong(1, newBalance);
            statement.setString(2, accountId.toString());
            statement.executeUpdate();
        }
    }

    /** One-winner freeze/unfreeze transition. */
    public boolean setFrozen(Connection connection, UUID accountId, boolean frozen,
            String reason, UUID actor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET frozen = ?, frozen_reason = ?, frozen_by = ?, "
                        + "version = version + 1 WHERE id = ? AND frozen = ?")) {
            statement.setBoolean(1, frozen);
            statement.setString(2, frozen ? reason : null);
            statement.setString(3, frozen && actor != null ? actor.toString() : null);
            statement.setString(4, accountId.toString());
            statement.setBoolean(5, !frozen);
            return statement.executeUpdate() == 1;
        }
    }

    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private Optional<Account> queryOne(Connection connection, String sql, Binder binder)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(map(rs));
            }
        }
    }

    private Account map(ResultSet rs) throws SQLException {
        String ownerRef = rs.getString("owner_ref");
        String frozenBy = rs.getString("frozen_by");
        return new Account(
                UUID.fromString(rs.getString("id")),
                Account.OwnerType.valueOf(rs.getString("owner_type")),
                ownerRef == null ? null : UUID.fromString(ownerRef),
                rs.getString("code"),
                rs.getString("iban"),
                rs.getLong("balance"),
                rs.getBoolean("allow_negative"),
                rs.getBoolean("frozen"),
                rs.getString("frozen_reason"),
                frozenBy == null ? null : UUID.fromString(frozenBy),
                rs.getInt("version"));
    }
}
