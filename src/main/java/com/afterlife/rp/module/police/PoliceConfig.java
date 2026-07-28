package com.afterlife.rp.module.police;

import com.afterlife.rp.config.ConfigValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/** Validated police module configuration (modules/police.yml). */
public record PoliceConfig(
        boolean enabled,
        int warrantDefaultMinutes,
        double searchRangeBlocks,
        int k9ScanIntervalSeconds,
        double k9RadiusBlocks,
        List<String> k9ContrabandTypes,
        List<String> k9OdorproofTypes,
        List<Long> accountBandsCents) {

    public static PoliceConfig from(ConfigurationSection section) {
        if (section == null) {
            throw new ConfigValidationException(List.of("modules/police.yml: missing 'police' section"));
        }
        List<String> errors = new ArrayList<>();
        List<String> contraband = upper(section.getStringList("k9.contraband-item-types"));
        if (contraband.isEmpty()) {
            errors.add("police.k9.contraband-item-types must not be empty");
        }
        List<Long> bands = new ArrayList<>();
        for (long euro : section.getLongList("account-check.bands-euro")) {
            bands.add(euro * 100);
        }
        bands.sort(Long::compare);
        int scanInterval = section.getInt("k9.scan-interval-seconds", 6);
        if (scanInterval < 1) {
            errors.add("police.k9.scan-interval-seconds must be >= 1");
        }
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return new PoliceConfig(
                section.getBoolean("enabled", true),
                section.getInt("warrant.default-duration-minutes", 30),
                section.getDouble("search.range-blocks", 4),
                scanInterval,
                section.getDouble("k9.radius-blocks", 5),
                contraband,
                upper(section.getStringList("k9.odorproof-item-types")),
                List.copyOf(bands));
    }

    private static List<String> upper(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(value.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(result);
    }

    /** Band label for an exact balance: "< 1.000 €", "1.000-10.000 €", etc. */
    public String band(long cents) {
        long lower = 0;
        for (long band : accountBandsCents) {
            if (cents < band) {
                return (lower == 0 ? "< " : (lower / 100) + "-") + (band / 100) + " €";
            }
            lower = band;
        }
        return "> " + (lower / 100) + " €";
    }
}
