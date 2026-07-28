package com.afterlife.rp.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.module.delivery.DeliveryConfig;
import com.afterlife.rp.module.electrician.ElectricianConfig;
import com.afterlife.rp.module.electrician.WiringSequence;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatchUnitTest {

    private final ElectricianConfig electrician = new ElectricianConfig(true,
            List.of("ELECTRICAL_CABINET"), 20, 20,
            4000, 2500, 2000, 300, 0.01, 4);

    private final DeliveryConfig delivery = new DeliveryConfig(true, 10, 45, 90,
            1200, 4, List.of("RESTAURANT"), List.of("DELIVERY_DESTINATION"), List.of("SHADOW_DROP"),
            100, 0.12, 1000, 0.25, 0.15, 60, 8000, 16000, 8);

    @Test
    void electricianRewardFollowsTheFormula() {
        // callout 40 + complexity 2 x 25 + time bonus 20 (fast repair)
        assertEquals(4000 + 2 * 2500 + 2000, electrician.reward(2, 120));
        // No bonus when slower than the threshold.
        assertEquals(4000 + 3 * 2500, electrician.reward(3, 301));
    }

    @Test
    void temperatureDecaysLinearlyAndClampsAtZero() {
        assertEquals(100.0, delivery.temperature(0));
        assertEquals(100.0 - 0.12 * 100, delivery.temperature(100), 0.0001);
        assertEquals(0.0, delivery.temperature(100_000));
    }

    @Test
    void tipScalesWithTemperatureAndRoute() {
        // Hot food, 200-block route: 10€ x 1.0 x (1 + 2 x 0.25) = 15€.
        assertEquals(1500, delivery.tip(0, 200));
        // Cold food pays no tip regardless of route.
        assertEquals(0, delivery.tip(100_000, 200));
        // Reward = base + distance + tip.
        assertEquals(1200 + 200 * 4 + 1500, delivery.reward(0, 200));
    }

    @Test
    void wiringSequenceAcceptsOnlyAscendingClicks() {
        WiringSequence sequence = WiringSequence.shuffle(4,
                List.of(10, 12, 14, 16, 28, 30, 32, 34), new SecureRandom());
        List<Integer> order = sequence.slotOrder();
        assertEquals(4, order.size());

        // Clicking the second fuse first is wrong.
        assertFalse(sequence.click(order.get(1)));
        // Correct ascending order completes.
        for (int slot : order) {
            assertTrue(sequence.click(slot));
        }
        assertTrue(sequence.complete());
    }
}
