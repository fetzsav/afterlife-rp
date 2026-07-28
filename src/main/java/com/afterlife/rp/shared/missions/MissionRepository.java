package com.afterlife.rp.shared.missions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL access for missions. Deadlines always compare against the DB clock. */
public final class MissionRepository {

    public void insert(Connection connection, Mission mission, int deadlineSeconds)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO missions (id, type, owner_uuid, state, target_poi_id, origin_poi_id, "
                        + "deadline, reward_snapshot, data) "
                        + "VALUES (?, ?, ?, ?, ?, ?, TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3)), ?, ?)")) {
            statement.setString(1, mission.id().toString());
            statement.setString(2, mission.type());
            statement.setString(3, mission.owner().toString());
            statement.setString(4, mission.state());
            statement.setString(5, mission.targetPoiId() == null ? null : mission.targetPoiId().toString());
            statement.setString(6, mission.originPoiId() == null ? null : mission.originPoiId().toString());
            statement.setInt(7, deadlineSeconds);
            statement.setLong(8, mission.rewardSnapshot());
            statement.setString(9, mission.data());
            statement.executeUpdate();
        }
    }

    public Optional<Mission> findActiveOfType(Connection connection, UUID owner, String type)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM missions WHERE owner_uuid = ? AND type = ? AND state = 'ACTIVE'")) {
            statement.setString(1, owner.toString());
            statement.setString(2, type);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<Mission> findActiveFor(Connection connection, UUID owner) throws SQLException {
        List<Mission> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM missions WHERE owner_uuid = ? AND state = 'ACTIVE'")) {
            statement.setString(1, owner.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
        }
        return result;
    }

    public Optional<Mission> findById(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM missions WHERE id = ?")) {
            statement.setString(1, id.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** One-winner state transition (rule 13: rewards happen exactly once). */
    public boolean transition(Connection connection, UUID id, String from, String to)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE missions SET state = ?, version = version + 1, "
                        + "completed_at = CASE WHEN ? = 'COMPLETED' THEN CURRENT_TIMESTAMP(3) "
                        + "ELSE completed_at END "
                        + "WHERE id = ? AND state = ?")) {
            statement.setString(1, to);
            statement.setString(2, to);
            statement.setString(3, id.toString());
            statement.setString(4, from);
            return statement.executeUpdate() == 1;
        }
    }

    public void updateData(Connection connection, UUID id, String data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE missions SET data = ?, version = version + 1 WHERE id = ?")) {
            statement.setString(1, data);
            statement.setString(2, id.toString());
            statement.executeUpdate();
        }
    }

    /** ACTIVE missions past their deadline, judged by the database clock. */
    public List<Mission> findOverdue(Connection connection) throws SQLException {
        List<Mission> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM missions WHERE state = 'ACTIVE' AND deadline < CURRENT_TIMESTAMP(3)");
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
        }
        return result;
    }

    private Mission map(ResultSet rs) throws SQLException {
        String target = rs.getString("target_poi_id");
        String origin = rs.getString("origin_poi_id");
        return new Mission(
                UUID.fromString(rs.getString("id")),
                rs.getString("type"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("state"),
                target == null ? null : UUID.fromString(target),
                origin == null ? null : UUID.fromString(origin),
                rs.getLong("reward_snapshot"),
                rs.getString("data"),
                rs.getInt("version"));
    }
}
