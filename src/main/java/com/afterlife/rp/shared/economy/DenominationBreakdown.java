package com.afterlife.rp.shared.economy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Greedy largest-first banknote breakdown of an amount in cents. */
public final class DenominationBreakdown {

    private DenominationBreakdown() {}

    /**
     * Returns denomination -> count (largest first), or null when the amount
     * cannot be represented exactly with the configured denominations.
     * Denominations must be sorted descending.
     */
    public static Map<Long, Integer> breakdown(long amountCents, List<Long> denominationsDesc) {
        if (amountCents <= 0 || denominationsDesc.isEmpty()) {
            return null;
        }
        Map<Long, Integer> result = new LinkedHashMap<>();
        long remaining = amountCents;
        for (long denomination : denominationsDesc) {
            if (denomination <= 0) {
                return null;
            }
            int count = (int) (remaining / denomination);
            if (count > 0) {
                result.put(denomination, count);
                remaining -= denomination * (long) count;
            }
        }
        return remaining == 0 ? result : null;
    }

    public static int totalNotes(Map<Long, Integer> breakdown) {
        return breakdown.values().stream().mapToInt(Integer::intValue).sum();
    }
}
