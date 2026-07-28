package com.afterlife.rp.shared.items;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/** SQL access for serialized_items. Runs off the main thread. */
public final class SerializedItemRepository {

    public void insert(Connection connection, SerializedItem item) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO serialized_items "
                        + "(serial, item_type, owner_uuid, denomination, status, issued_by, issued_at, metadata) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, item.serial().toString());
            statement.setString(2, item.itemType());
            statement.setString(3, item.owner() == null ? null : item.owner().toString());
            if (item.denomination() == null) {
                statement.setNull(4, java.sql.Types.BIGINT);
            } else {
                statement.setLong(4, item.denomination());
            }
            statement.setString(5, item.status().name());
            statement.setString(6, item.issuedBy() == null ? null : item.issuedBy().toString());
            statement.setTimestamp(7, new Timestamp(item.issuedAtEpochMs()));
            statement.setString(8, item.metadata());
            statement.executeUpdate();
        }
    }

    public Optional<SerializedItem> find(Connection connection, UUID serial) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT serial, item_type, owner_uuid, denomination, status, issued_by, issued_at, metadata "
                        + "FROM serialized_items WHERE serial = ?")) {
            statement.setString(1, serial.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long denomination = rs.getLong("denomination");
                boolean denominationNull = rs.wasNull();
                String owner = rs.getString("owner_uuid");
                String issuedBy = rs.getString("issued_by");
                return Optional.of(new SerializedItem(
                        UUID.fromString(rs.getString("serial")),
                        rs.getString("item_type"),
                        owner == null ? null : UUID.fromString(owner),
                        denominationNull ? null : denomination,
                        ItemStatus.valueOf(rs.getString("status")),
                        issuedBy == null ? null : UUID.fromString(issuedBy),
                        rs.getTimestamp("issued_at").getTime(),
                        rs.getString("metadata")));
            }
        }
    }

    public java.util.List<SerializedItem> findByOwnerAndType(
            Connection connection, UUID owner, String itemType, ItemStatus status) throws SQLException {
        java.util.List<SerializedItem> result = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT serial, item_type, owner_uuid, denomination, status, issued_by, issued_at, metadata "
                        + "FROM serialized_items WHERE owner_uuid = ? AND item_type = ? AND status = ?")) {
            statement.setString(1, owner.toString());
            statement.setString(2, itemType);
            statement.setString(3, status.name());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    long denomination = rs.getLong("denomination");
                    boolean denominationNull = rs.wasNull();
                    String issuedBy = rs.getString("issued_by");
                    result.add(new SerializedItem(
                            UUID.fromString(rs.getString("serial")),
                            rs.getString("item_type"),
                            owner,
                            denominationNull ? null : denomination,
                            ItemStatus.valueOf(rs.getString("status")),
                            issuedBy == null ? null : UUID.fromString(issuedBy),
                            rs.getTimestamp("issued_at").getTime(),
                            rs.getString("metadata")));
                }
            }
        }
        return result;
    }

    /**
     * Single-shot status transition: succeeds for exactly one caller even under
     * concurrency or replay (rule 13). Returns false when the row was not in
     * {@code from} status.
     */
    public boolean transition(Connection connection, UUID serial, ItemStatus from, ItemStatus to)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE serialized_items SET status = ?, version = version + 1 "
                        + "WHERE serial = ? AND status = ?")) {
            statement.setString(1, to.name());
            statement.setString(2, serial.toString());
            statement.setString(3, from.name());
            return statement.executeUpdate() == 1;
        }
    }
}
