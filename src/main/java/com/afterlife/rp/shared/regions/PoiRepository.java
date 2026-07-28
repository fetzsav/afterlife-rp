package com.afterlife.rp.shared.regions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL access for points_of_interest. Runs off the main thread. */
public final class PoiRepository {

    public void insert(Connection connection, Poi poi) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO points_of_interest "
                        + "(id, name, type, world, x, y, z, yaw, pitch, region_id, status, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, poi.id().toString());
            statement.setString(2, poi.name());
            statement.setString(3, poi.type());
            statement.setString(4, poi.world());
            statement.setDouble(5, poi.x());
            statement.setDouble(6, poi.y());
            statement.setDouble(7, poi.z());
            statement.setFloat(8, poi.yaw());
            statement.setFloat(9, poi.pitch());
            statement.setString(10, poi.regionId());
            statement.setString(11, poi.status());
            statement.setString(12, poi.createdBy() == null ? null : poi.createdBy().toString());
            statement.executeUpdate();
        }
    }

    public boolean deleteByName(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM points_of_interest WHERE name = ?")) {
            statement.setString(1, name);
            return statement.executeUpdate() == 1;
        }
    }

    public List<Poi> findAll(Connection connection) throws SQLException {
        List<Poi> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, name, type, world, x, y, z, yaw, pitch, region_id, status, created_by "
                        + "FROM points_of_interest ORDER BY type, name");
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String createdBy = rs.getString("created_by");
                result.add(new Poi(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getString("world"),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        rs.getFloat("yaw"),
                        rs.getFloat("pitch"),
                        rs.getString("region_id"),
                        rs.getString("status"),
                        createdBy == null ? null : UUID.fromString(createdBy)));
            }
        }
        return result;
    }
}
