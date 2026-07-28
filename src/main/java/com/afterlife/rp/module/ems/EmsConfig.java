package com.afterlife.rp.module.ems;

import com.afterlife.rp.config.ConfigValidationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.configuration.ConfigurationSection;

/** Validated EMS module configuration (modules/ems.yml). */
public record EmsConfig(
        boolean enabled,
        double minimumDamage,
        boolean applyEffects,
        double unconsciousHealthThreshold,
        double unconsciousChance,
        Map<String, CauseRule> causes,
        long priceCentsPerStep,
        int medicCommissionPercent,
        Map<String, List<String>> sequences,
        Set<String> consumables,
        List<String> workstationPoiTypes,
        Map<String, Long> reagentCostCents,
        long wageHourlyCents,
        long certificatePriceCents,
        int certificateExpiryDays,
        int emergencyIntervalMinutesMin,
        int emergencyIntervalMinutesMax,
        int emergencyMinMedics,
        List<String> emergencyPoiTypes,
        long emergencyRewardCents,
        int emergencyDeadlineMinutes,
        int emergencyTreatmentSteps,
        List<String> toxicBarrelPoiTypes,
        int toxicDurationSecondsMin,
        int toxicDurationSecondsMax,
        double toxicRadiusBlocks,
        List<String> toxicWorkstationPoiTypes) {

    public record CauseRule(double chance, List<String> types) {}

    public static EmsConfig from(ConfigurationSection section) {
        if (section == null) {
            throw new ConfigValidationException(List.of("modules/ems.yml: missing 'ems' section"));
        }
        List<String> errors = new ArrayList<>();

        Map<String, CauseRule> causes = new HashMap<>();
        ConfigurationSection causesSection = section.getConfigurationSection("injury.causes");
        if (causesSection != null) {
            for (String cause : causesSection.getKeys(false)) {
                double chance = causesSection.getDouble(cause + ".chance", 0);
                List<String> types = upper(causesSection.getStringList(cause + ".types"));
                if (chance < 0 || chance > 1 || types.isEmpty()) {
                    errors.add("ems.injury.causes." + cause + ": chance 0-1 and non-empty types required");
                }
                causes.put(cause.toUpperCase(Locale.ROOT), new CauseRule(chance, types));
            }
        }
        if (causes.isEmpty()) {
            errors.add("ems.injury.causes must define at least one cause");
        }

        Map<String, List<String>> sequences = new HashMap<>();
        ConfigurationSection sequenceSection = section.getConfigurationSection("treatment.sequences");
        if (sequenceSection != null) {
            for (String type : sequenceSection.getKeys(false)) {
                List<String> tools = sequenceSection.getStringList(type);
                if (tools.isEmpty()) {
                    errors.add("ems.treatment.sequences." + type + " must not be empty");
                }
                sequences.put(type.toUpperCase(Locale.ROOT), List.copyOf(tools));
            }
        }
        if (sequences.isEmpty()) {
            errors.add("ems.treatment.sequences must define at least one injury type");
        }

        Map<String, Long> reagents = new HashMap<>();
        ConfigurationSection reagentSection = section.getConfigurationSection("production.reagent-cost-euro");
        if (reagentSection != null) {
            for (String medicine : reagentSection.getKeys(false)) {
                reagents.put(medicine.toLowerCase(Locale.ROOT),
                        reagentSection.getLong(medicine) * 100);
            }
        }
        if (reagents.isEmpty()) {
            errors.add("ems.production.reagent-cost-euro must define at least one medicine");
        }

        int commission = section.getInt("treatment.medic-commission-percent", 10);
        if (commission < 0 || commission > 100) {
            errors.add("ems.treatment.medic-commission-percent must be 0-100");
        }
        int intervalMin = section.getInt("emergency.interval-minutes-min", 30);
        int intervalMax = section.getInt("emergency.interval-minutes-max", 40);
        if (intervalMin < 1 || intervalMax < intervalMin) {
            errors.add("ems.emergency: interval-minutes-min >= 1 and max >= min required");
        }
        int toxicMin = section.getInt("toxic.duration-seconds-min", 45);
        int toxicMax = section.getInt("toxic.duration-seconds-max", 60);
        if (toxicMin < 5 || toxicMax < toxicMin) {
            errors.add("ems.toxic: duration-seconds-min >= 5 and max >= min required");
        }
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }

        Set<String> consumables = new TreeSet<>();
        for (String item : section.getStringList("treatment.consumables")) {
            consumables.add(item.toLowerCase(Locale.ROOT));
        }

        return new EmsConfig(
                section.getBoolean("enabled", true),
                section.getDouble("injury.minimum-damage", 4.0),
                section.getBoolean("injury.apply-effects", true),
                section.getDouble("injury.unconscious-health-threshold", 6.0),
                section.getDouble("injury.unconscious-chance", 0.35),
                Map.copyOf(causes),
                section.getLong("treatment.price-euro-per-step", 15) * 100,
                commission,
                Map.copyOf(sequences),
                Set.copyOf(consumables),
                upper(section.getStringList("production.workstation-poi-types")),
                Map.copyOf(reagents),
                section.getLong("wage.hourly-euro", 35) * 100,
                section.getLong("certificate.price-euro", 100) * 100,
                section.getInt("certificate.expiry-days", 14),
                intervalMin, intervalMax,
                section.getInt("emergency.min-medics-on-duty", 1),
                upper(section.getStringList("emergency.poi-types")),
                section.getLong("emergency.reward-euro", 60) * 100,
                section.getInt("emergency.deadline-minutes", 15),
                section.getInt("emergency.treatment-steps", 3),
                upper(section.getStringList("toxic.barrel-poi-types")),
                toxicMin, toxicMax,
                section.getDouble("toxic.radius-blocks", 3),
                upper(section.getStringList("toxic.workstation-poi-types")));
    }

    private static List<String> upper(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(value.toUpperCase(Locale.ROOT));
        }
        return List.copyOf(result);
    }

    /** The next tool required for an injury, or null when the sequence is done. */
    public String nextTool(String injuryType, int step) {
        List<String> sequence = sequences.get(injuryType);
        if (sequence == null || step >= sequence.size()) {
            return null;
        }
        return sequence.get(step);
    }

    public int sequenceLength(String injuryType) {
        List<String> sequence = sequences.get(injuryType);
        return sequence == null ? 0 : sequence.size();
    }
}
