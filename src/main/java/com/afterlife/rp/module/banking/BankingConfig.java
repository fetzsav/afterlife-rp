package com.afterlife.rp.module.banking;

import com.afterlife.rp.config.ConfigValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/** Validated banking module configuration (modules/banking.yml). */
public record BankingConfig(
        boolean enabled,
        List<Long> denominationsCentsDesc,
        boolean atmRequirePoi,
        List<String> atmPoiTypes,
        double atmRangeBlocks,
        List<Long> quickAmountsCents,
        String ibanAbi,
        String ibanCab,
        int checkExpiryDays,
        int statementEntries) {

    public static BankingConfig from(ConfigurationSection section) {
        List<String> errors = new ArrayList<>();
        if (section == null) {
            throw new ConfigValidationException(List.of("modules/banking.yml: missing 'banking' section"));
        }
        boolean enabled = section.getBoolean("enabled", true);

        List<Long> denominations = new ArrayList<>();
        for (long euro : section.getLongList("denominations-euro")) {
            denominations.add(euro * 100);
        }
        denominations.sort((a, b) -> Long.compare(b, a));
        if (denominations.isEmpty() || denominations.stream().anyMatch(d -> d <= 0)) {
            errors.add("banking.denominations-euro must be a non-empty list of positive values");
        }

        List<Long> quickAmounts = new ArrayList<>();
        for (long euro : section.getLongList("atm.quick-amounts-euro")) {
            quickAmounts.add(euro * 100);
        }
        if (quickAmounts.isEmpty() || quickAmounts.size() > 5) {
            errors.add("banking.atm.quick-amounts-euro must list 1-5 amounts");
        }

        List<String> poiTypes = new ArrayList<>();
        for (String type : section.getStringList("atm.poi-types")) {
            poiTypes.add(type.toUpperCase(Locale.ROOT));
        }
        if (poiTypes.isEmpty()) {
            errors.add("banking.atm.poi-types must not be empty");
        }

        double range = section.getDouble("atm.range-blocks", 4.0);
        if (range < 1 || range > 32) {
            errors.add("banking.atm.range-blocks must be between 1 and 32");
        }

        String abi = section.getString("iban.abi", "");
        String cab = section.getString("iban.cab", "");
        if (abi.length() != 5 || !abi.chars().allMatch(Character::isDigit)) {
            errors.add("banking.iban.abi must be exactly 5 digits");
        }
        if (cab.length() != 5 || !cab.chars().allMatch(Character::isDigit)) {
            errors.add("banking.iban.cab must be exactly 5 digits");
        }

        int checkExpiryDays = section.getInt("check-expiry-days", 7);
        if (checkExpiryDays < 1) {
            errors.add("banking.check-expiry-days must be at least 1");
        }
        int statementEntries = section.getInt("statement-entries", 5);
        if (statementEntries < 1 || statementEntries > 20) {
            errors.add("banking.statement-entries must be between 1 and 20");
        }

        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return new BankingConfig(enabled, List.copyOf(denominations),
                section.getBoolean("atm.require-poi", true), List.copyOf(poiTypes), range,
                List.copyOf(quickAmounts), abi, cab, checkExpiryDays, statementEntries);
    }
}
