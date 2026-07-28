package com.afterlife.rp.module.realestate;

import com.afterlife.rp.config.ConfigValidationException;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/** Validated real-estate module configuration (modules/realestate.yml). */
public record RealEstateConfig(
        boolean enabled,
        String revenueAccountCode,
        int powerCheckMinutes,
        int powerIncrementPerCheck,
        int powerAlertThreshold) {

    public static RealEstateConfig from(ConfigurationSection section) {
        if (section == null) {
            throw new ConfigValidationException(
                    List.of("modules/realestate.yml: missing 'realestate' section"));
        }
        List<String> errors = new ArrayList<>();
        String revenueAccount = section.getString("revenue-account", "government_budget");
        int checkMinutes = section.getInt("power.check-minutes", 60);
        int increment = section.getInt("power.increment-per-check", 10);
        int threshold = section.getInt("power.alert-threshold", 40);
        if (revenueAccount.isBlank()) {
            errors.add("realestate.revenue-account must not be empty");
        }
        if (checkMinutes < 1) {
            errors.add("realestate.power.check-minutes must be >= 1");
        }
        if (increment < 1 || threshold < increment) {
            errors.add("realestate.power: increment >= 1 and alert-threshold >= increment required");
        }
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return new RealEstateConfig(section.getBoolean("enabled", true),
                revenueAccount, checkMinutes, increment, threshold);
    }
}
