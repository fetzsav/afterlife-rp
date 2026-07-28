package com.afterlife.rp.module.electrician;

import com.afterlife.rp.config.ConfigValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/** Validated electrician module configuration (modules/electrician.yml). */
public record ElectricianConfig(
        boolean enabled,
        List<String> poiTypes,
        int dispatchIntervalMinutes,
        int missionDeadlineMinutes,
        long calloutFeeCents,
        long complexityMultiplierCents,
        long timeBonusCents,
        int timeBonusWithinSeconds,
        double circuitBoardChance,
        int minigameFuses) {

    public static ElectricianConfig from(ConfigurationSection section) {
        if (section == null) {
            throw new ConfigValidationException(
                    List.of("modules/electrician.yml: missing 'electrician' section"));
        }
        List<String> errors = new ArrayList<>();
        List<String> poiTypes = new ArrayList<>();
        for (String type : section.getStringList("poi-types")) {
            poiTypes.add(type.toUpperCase(Locale.ROOT));
        }
        int dispatch = section.getInt("dispatch-interval-minutes", 20);
        int deadline = section.getInt("mission-deadline-minutes", 20);
        double chance = section.getDouble("circuit-board-chance", 0.01);
        int fuses = section.getInt("minigame.fuses", 4);

        if (poiTypes.isEmpty()) {
            errors.add("electrician.poi-types must not be empty");
        }
        if (dispatch < 1 || deadline < 1) {
            errors.add("electrician: dispatch-interval-minutes and mission-deadline-minutes must be >= 1");
        }
        if (chance < 0 || chance > 1) {
            errors.add("electrician.circuit-board-chance must be between 0 and 1");
        }
        if (fuses < 2 || fuses > 8) {
            errors.add("electrician.minigame.fuses must be between 2 and 8");
        }
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return new ElectricianConfig(
                section.getBoolean("enabled", true),
                List.copyOf(poiTypes),
                dispatch,
                deadline,
                section.getLong("reward.callout-fee-euro", 40) * 100,
                section.getLong("reward.complexity-multiplier-euro", 25) * 100,
                section.getLong("reward.time-bonus-euro", 20) * 100,
                section.getInt("reward.time-bonus-within-seconds", 300),
                chance,
                fuses);
    }

    /** Government pay = callout fee + complexity x multiplier + time bonus (§9.5). */
    public long reward(int complexity, long elapsedSeconds) {
        long total = calloutFeeCents + complexity * complexityMultiplierCents;
        if (elapsedSeconds <= timeBonusWithinSeconds) {
            total += timeBonusCents;
        }
        return total;
    }
}
