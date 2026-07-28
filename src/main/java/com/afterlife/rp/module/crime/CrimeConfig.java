package com.afterlife.rp.module.crime;

import com.afterlife.rp.config.ConfigValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/** Validated crime module configuration (modules/crime.yml). */
public record CrimeConfig(
        boolean enabled,
        List<String> saleZonePoiTypes,
        int demandIntervalSeconds,
        long payoutMinCents,
        long payoutMaxCents,
        double suspicionChance,
        double goodTripChance,
        int tripSeconds,
        int hallucinationCap,
        int consumeCooldownSeconds,
        List<String> goodMobs,
        List<String> badMobs,
        List<String> atmPoiTypes,
        int hackChannelSeconds,
        long hackRewardMinCents,
        long hackRewardMaxCents,
        boolean hackAlert) {

    public static CrimeConfig from(ConfigurationSection section) {
        if (section == null) {
            throw new ConfigValidationException(List.of("modules/crime.yml: missing 'crime' section"));
        }
        List<String> errors = new ArrayList<>();
        double goodTrip = section.getDouble("drug.good-trip-chance", 0.70);
        if (goodTrip < 0 || goodTrip > 1) {
            errors.add("crime.drug.good-trip-chance must be 0-1");
        }
        double suspicion = section.getDouble("gang.suspicion-chance", 0.25);
        if (suspicion < 0 || suspicion > 1) {
            errors.add("crime.gang.suspicion-chance must be 0-1");
        }
        long payMin = section.getLong("gang.payout-euro-min", 40) * 100;
        long payMax = section.getLong("gang.payout-euro-max", 80) * 100;
        if (payMin <= 0 || payMax < payMin) {
            errors.add("crime.gang: payout-euro-min > 0 and max >= min required");
        }
        List<String> goodMobs = upper(section.getStringList("drug.good-mobs"));
        List<String> badMobs = upper(section.getStringList("drug.bad-mobs"));
        if (goodMobs.isEmpty() || badMobs.isEmpty()) {
            errors.add("crime.drug.good-mobs and bad-mobs must not be empty");
        }
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return new CrimeConfig(
                section.getBoolean("enabled", true),
                upper(section.getStringList("gang.sale-zone-poi-types")),
                section.getInt("gang.demand-interval-seconds", 90),
                payMin, payMax, suspicion,
                goodTrip,
                section.getInt("drug.trip-seconds", 20),
                section.getInt("drug.hallucination-cap", 4),
                section.getInt("drug.consume-cooldown-seconds", 45),
                goodMobs, badMobs,
                upper(section.getStringList("atm-hack.poi-types")),
                section.getInt("atm-hack.channel-seconds", 20),
                section.getLong("atm-hack.reward-euro-min", 200) * 100,
                section.getLong("atm-hack.reward-euro-max", 600) * 100,
                section.getBoolean("atm-hack.alert", true));
    }

    private static List<String> upper(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(value.toUpperCase(Locale.ROOT));
        }
        return List.copyOf(result);
    }
}
