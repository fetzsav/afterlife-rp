package com.afterlife.rp.shared.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** SQL access for the players table. All methods expect to run off the main thread. */
public final class IdentityRepository {

    /**
     * Creates the row on first join (allocating the next sequential public ID via
     * AUTO_INCREMENT inside the surrounding transaction) or refreshes name/last_seen.
     */
    public PlayerIdentity ensure(Connection connection, UUID uuid, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO players (uuid, last_name) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE last_name = VALUES(last_name), "
                        + "last_seen = CURRENT_TIMESTAMP(3)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, name);
            statement.executeUpdate();
        }
        return find(connection, uuid).orElseThrow(
                () -> new SQLException("players row missing after ensure for " + uuid));
    }

    public Optional<PlayerIdentity> find(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid, public_id, last_name, nickname FROM players WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PlayerIdentity(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getLong("public_id"),
                        rs.getString("last_name"),
                        rs.getString("nickname")));
            }
        }
    }

    /** Sets or clears (null) the nickname; bumps the optimistic version. */
    public boolean updateNickname(Connection connection, UUID uuid, String nickname) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE players SET nickname = ?, version = version + 1 WHERE uuid = ?")) {
            statement.setString(1, nickname);
            statement.setString(2, uuid.toString());
            return statement.executeUpdate() == 1;
        }
    }

    public void touchLastSeen(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE players SET last_seen = CURRENT_TIMESTAMP(3) WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
    }
}
