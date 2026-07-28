package com.afterlife.rp.module.legal;

import com.afterlife.rp.config.ConfigValidationException;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/** Validated legal module configuration (modules/legal.yml). */
public record LegalConfig(
        boolean enabled,
        int detentionDefaultMinutes,
        int detentionMaxMinutes,
        int expungeCrimeFreeDays,
        long expungeFeeCents,
        int contractMaxContentChars,
        int contractProposalTimeoutSeconds) {

    public static LegalConfig from(ConfigurationSection section) {
        if (section == null) {
            throw new ConfigValidationException(List.of("modules/legal.yml: missing 'legal' section"));
        }
        List<String> errors = new ArrayList<>();
        int detentionDefault = section.getInt("detention.default-minutes", 15);
        int detentionMax = section.getInt("detention.max-minutes", 45);
        int crimeFreeDays = section.getInt("expunge.crime-free-days", 7);
        long feeCents = section.getLong("expunge.fee-euro", 500) * 100;
        int maxChars = section.getInt("contract.max-content-chars", 4000);
        int proposalTimeout = section.getInt("contract.proposal-timeout-seconds", 120);

        if (detentionDefault < 1 || detentionMax < detentionDefault) {
            errors.add("legal.detention: default-minutes >= 1 and max-minutes >= default-minutes required");
        }
        if (crimeFreeDays < 0) {
            errors.add("legal.expunge.crime-free-days must be >= 0");
        }
        if (feeCents < 0) {
            errors.add("legal.expunge.fee-euro must be >= 0");
        }
        if (maxChars < 100 || maxChars > 60000) {
            errors.add("legal.contract.max-content-chars must be between 100 and 60000");
        }
        if (proposalTimeout < 15) {
            errors.add("legal.contract.proposal-timeout-seconds must be >= 15");
        }
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return new LegalConfig(section.getBoolean("enabled", true), detentionDefault, detentionMax,
                crimeFreeDays, feeCents, maxChars, proposalTimeout);
    }
}
