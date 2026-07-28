package com.afterlife.rp.config;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/** Connection settings for the authoritative MariaDB database. */
public record DatabaseSettings(
        String host,
        int port,
        String database,
        String user,
        String password,
        int poolSize,
        long connectTimeoutMs) {

    public static DatabaseSettings from(ConfigurationSection section) {
        if (section == null) {
            throw new ConfigValidationException(List.of("Missing 'database' section in config.yml"));
        }
        DatabaseSettings settings = new DatabaseSettings(
                section.getString("host", ""),
                section.getInt("port", 3306),
                section.getString("name", ""),
                section.getString("user", ""),
                section.getString("password", ""),
                section.getInt("pool-size", 8),
                section.getLong("connect-timeout-ms", 5000));
        List<String> errors = settings.validate();
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return settings;
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (host == null || host.isBlank()) {
            errors.add("database.host must not be empty");
        }
        if (port < 1 || port > 65535) {
            errors.add("database.port must be between 1 and 65535");
        }
        if (database == null || database.isBlank()) {
            errors.add("database.name must not be empty");
        }
        if (user == null || user.isBlank()) {
            errors.add("database.user must not be empty");
        }
        if (poolSize < 1 || poolSize > 64) {
            errors.add("database.pool-size must be between 1 and 64");
        }
        if (connectTimeoutMs < 250) {
            errors.add("database.connect-timeout-ms must be at least 250");
        }
        return errors;
    }

    public String jdbcUrl() {
        return "jdbc:mariadb://" + host + ":" + port + "/" + database;
    }
}
