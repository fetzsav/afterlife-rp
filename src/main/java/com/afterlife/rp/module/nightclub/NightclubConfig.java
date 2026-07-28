package com.afterlife.rp.module.nightclub;

import com.afterlife.rp.config.ConfigValidationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** Validated nightclub module configuration (modules/nightclub.yml). */
public record NightclubConfig(
        boolean enabled,
        double posRangeBlocks,
        List<String> posPoiTypes,
        int orderTimeoutSeconds,
        Map<String, Product> products,
        int drinkCooldownSeconds,
        double masterworkMultiplier,
        double dilutedMultiplier,
        List<String> shakerPoiTypes,
        int defaultCommissionPercent,
        int escrowCommissionPercent,
        int escrowTimeoutMinutes,
        int bountyFeePercent,
        long bountyMinCents,
        int happyHourDiscountPercent,
        int happyHourDurationMinutes,
        String clubRegion,
        String vipRegion,
        List<String> weaponMaterials,
        List<String> djPoiTypes,
        int djEffectSeconds,
        int djCooldownSeconds,
        int restockDelayMinutes) {

    public record Product(String name, long retailCents, long retailMinCents, long retailMaxCents,
            long wholesaleCents, String effect, String sideEffect, boolean cures) {}

    public static NightclubConfig from(ConfigurationSection section) {
        if (section == null) {
            throw new ConfigValidationException(
                    List.of("modules/nightclub.yml: missing 'nightclub' section"));
        }
        List<String> errors = new ArrayList<>();
        Map<String, Product> products = new HashMap<>();
        ConfigurationSection productsSection = section.getConfigurationSection("products");
        if (productsSection != null) {
            for (String id : productsSection.getKeys(false)) {
                ConfigurationSection p = productsSection.getConfigurationSection(id);
                if (p == null) {
                    continue;
                }
                long retail = p.getLong("retail-euro") * 100;
                long min = p.getLong("retail-min-euro") * 100;
                long max = p.getLong("retail-max-euro") * 100;
                if (retail <= 0 || min <= 0 || max < min || retail < min || retail > max) {
                    errors.add("nightclub.products." + id + ": retail within [min,max] > 0 required");
                }
                products.put(id.toLowerCase(Locale.ROOT), new Product(
                        p.getString("name", id), retail, min, max,
                        p.getLong("wholesale-euro") * 100,
                        p.getString("effect", ""), p.getString("side-effect", ""),
                        p.getBoolean("cures", false)));
            }
        }
        if (products.isEmpty()) {
            errors.add("nightclub.products must define at least one product");
        }
        int discount = section.getInt("happy-hour.discount-percent", 20);
        if (discount < 0 || discount > 90) {
            errors.add("nightclub.happy-hour.discount-percent must be 0-90");
        }
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return new NightclubConfig(
                section.getBoolean("enabled", true),
                section.getDouble("pos.range-blocks", 5),
                upper(section.getStringList("pos.poi-types")),
                section.getInt("pos.order-timeout-seconds", 60),
                Map.copyOf(products),
                section.getInt("drink.cooldown-seconds", 60),
                section.getDouble("drink.masterwork-duration-multiplier", 2.0),
                section.getDouble("drink.diluted-duration-multiplier", 0.5),
                upper(section.getStringList("shaker.poi-types")),
                section.getInt("commission.default-percent", 10),
                section.getInt("escrow.commission-percent", 5),
                section.getInt("escrow.timeout-minutes", 15),
                section.getInt("bounty.bartender-fee-percent", 5),
                section.getLong("bounty.min-euro", 100) * 100,
                discount,
                section.getInt("happy-hour.duration-minutes", 30),
                section.getString("security.club-region", "nightclub"),
                section.getString("security.vip-region", "nightclub_vip"),
                upper(section.getStringList("security.weapon-materials")),
                upper(section.getStringList("dj.poi-types")),
                section.getInt("dj.effect-seconds", 20),
                section.getInt("dj.cooldown-seconds", 45),
                section.getInt("restock.delivery-delay-minutes", 10));
    }

    private static List<String> upper(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(value.toUpperCase(Locale.ROOT));
        }
        return List.copyOf(result);
    }

    /** Retail price with the happy-hour discount applied when active. */
    public long priceWithDiscount(long retailCents, boolean happyHour) {
        if (!happyHour) {
            return retailCents;
        }
        return retailCents * (100 - happyHourDiscountPercent) / 100;
    }
}
