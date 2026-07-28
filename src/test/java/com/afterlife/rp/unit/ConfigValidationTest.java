package com.afterlife.rp.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.config.ConfigValidationException;
import com.afterlife.rp.config.CoreConfig;
import com.afterlife.rp.config.DatabaseSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigValidationTest {

    private YamlConfiguration yaml(String content) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return configuration;
    }

    @Test
    void validDatabaseSectionLoads() {
        DatabaseSettings settings = DatabaseSettings.from(yaml("""
                database:
                  host: localhost
                  port: 3306
                  name: afterlife
                  user: afterlife
                  password: secret
                  pool-size: 4
                """).getConfigurationSection("database"));
        assertEquals("jdbc:mariadb://localhost:3306/afterlife?timezone=auto", settings.jdbcUrl());
    }

    @Test
    void missingHostAndUserFailLoudly() {
        ConfigValidationException e = assertThrows(ConfigValidationException.class,
                () -> DatabaseSettings.from(yaml("""
                        database:
                          port: 70000
                          name: afterlife
                          pool-size: 0
                        """).getConfigurationSection("database")));
        assertTrue(e.errors().stream().anyMatch(msg -> msg.contains("database.host")));
        assertTrue(e.errors().stream().anyMatch(msg -> msg.contains("database.user")));
        assertTrue(e.errors().stream().anyMatch(msg -> msg.contains("database.port")));
        assertTrue(e.errors().stream().anyMatch(msg -> msg.contains("database.pool-size")));
    }

    @Test
    void coreConfigRejectsBadNicknameBoundsAndEmptyPoiTypes() {
        ConfigValidationException e = assertThrows(ConfigValidationException.class,
                () -> CoreConfig.from(yaml("""
                        identity:
                          nickname:
                            min-length: 5
                            max-length: 2
                        gui:
                          session-timeout-seconds: 3
                        poi:
                          types: []
                        """)));
        assertEquals(3, e.errors().size());
    }

    @Test
    void coreConfigNormalizesPoiTypesToUpperCase() {
        CoreConfig config = CoreConfig.from(yaml("""
                poi:
                  types: [atm, Generic]
                """));
        assertTrue(config.poiTypes().contains("ATM"));
        assertTrue(config.poiTypes().contains("GENERIC"));
    }
}
