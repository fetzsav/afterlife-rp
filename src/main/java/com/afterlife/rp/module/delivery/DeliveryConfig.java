package com.afterlife.rp.module.delivery;

import com.afterlife.rp.config.ConfigValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/** Validated delivery module configuration (modules/delivery.yml). */
public record DeliveryConfig(
        boolean enabled,
        int packageTimeoutMinutes,
        int afkWarningSeconds,
        int afkCancelSeconds,
        long basePayCents,
        long distanceCentsPerBlock,
        List<String> restaurantTypes,
        List<String> destinationTypes,
        List<String> shadowTypes,
        double temperatureBase,
        double coolingRatePerSecond,
        long baseTipCents,
        double routeMultiplierPer100Blocks,
        double contrabandChance,
        int contrabandWindowSeconds,
        long contrabandPayMinCents,
        long contrabandPayMaxCents,
        int contrabandDeadlineMinutes) {

    public static DeliveryConfig from(ConfigurationSection section) {
        if (section == null) {
            throw new ConfigValidationException(
                    List.of("modules/delivery.yml: missing 'delivery' section"));
        }
        List<String> errors = new ArrayList<>();
        List<String> restaurants = upper(section.getStringList("poi-types.restaurants"));
        List<String> destinations = upper(section.getStringList("poi-types.destinations"));
        List<String> shadows = upper(section.getStringList("poi-types.shadow-drops"));
        double chance = section.getDouble("contraband.offer-chance", 0.15);
        long payMin = section.getLong("contraband.pay-euro-min", 80) * 100;
        long payMax = section.getLong("contraband.pay-euro-max", 160) * 100;
        int warn = section.getInt("afk-warning-seconds", 45);
        int cancel = section.getInt("afk-cancel-seconds", 90);

        if (restaurants.isEmpty() || destinations.isEmpty() || shadows.isEmpty()) {
            errors.add("delivery.poi-types: restaurants, destinations, and shadow-drops must be set");
        }
        if (chance < 0 || chance > 1) {
            errors.add("delivery.contraband.offer-chance must be between 0 and 1");
        }
        if (payMin <= 0 || payMax < payMin) {
            errors.add("delivery.contraband: pay-euro-min > 0 and pay-euro-max >= min required");
        }
        if (warn < 5 || cancel <= warn) {
            errors.add("delivery: afk-warning-seconds >= 5 and afk-cancel-seconds > warning required");
        }
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return new DeliveryConfig(
                section.getBoolean("enabled", true),
                section.getInt("package-timeout-minutes", 10),
                warn,
                cancel,
                section.getLong("base-pay-euro", 12) * 100,
                section.getLong("distance-cents-per-block", 4),
                restaurants, destinations, shadows,
                section.getDouble("temperature.base", 100),
                section.getDouble("temperature.cooling-rate-per-second", 0.12),
                section.getLong("temperature.base-tip-euro", 10) * 100,
                section.getDouble("temperature.route-multiplier-per-100-blocks", 0.25),
                chance,
                section.getInt("contraband.offer-window-seconds", 60),
                payMin, payMax,
                section.getInt("contraband.deadline-minutes", 8));
    }

    private static List<String> upper(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(value.toUpperCase(Locale.ROOT));
        }
        return List.copyOf(result);
    }

    /** T(t) = max(0, base - coolingRate x elapsedSeconds) (§9.6). */
    public double temperature(long elapsedSeconds) {
        return Math.max(0, temperatureBase - coolingRatePerSecond * elapsedSeconds);
    }

    /** Tip = baseTip x (T/base) x routeMultiplier(blocks); reward adds base pay + distance. */
    public long tip(long elapsedSeconds, double routeBlocks) {
        double routeMultiplier = 1 + (routeBlocks / 100.0) * routeMultiplierPer100Blocks;
        return Math.round(baseTipCents * (temperature(elapsedSeconds) / temperatureBase)
                * routeMultiplier);
    }

    public long reward(long elapsedSeconds, double routeBlocks) {
        return basePayCents + Math.round(routeBlocks * distanceCentsPerBlock)
                + tip(elapsedSeconds, routeBlocks);
    }
}
