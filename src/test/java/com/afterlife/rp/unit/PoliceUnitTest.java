package com.afterlife.rp.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.afterlife.rp.module.police.PoliceConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class PoliceUnitTest {

    private final PoliceConfig config = new PoliceConfig(true, 30, 4,
            6, 5, List.of("drug_dose"), List.of("sealed_bag"),
            List.of(100_000L, 1_000_000L, 10_000_000L));

    @Test
    void accountBandsHideExactBalances() {
        assertEquals("< 1000 €", config.band(50_000));       // 500 €
        assertEquals("1000-10000 €", config.band(500_000));  // 5.000 €
        assertEquals("10000-100000 €", config.band(5_000_000));
        assertEquals("> 100000 €", config.band(50_000_000));
    }
}
