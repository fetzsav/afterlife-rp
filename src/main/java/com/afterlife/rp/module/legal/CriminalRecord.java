package com.afterlife.rp.module.legal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A criminal-record row. Severity: MINOR/MAJOR. Status: OPEN/SERVED/EXPUNGED. */
public record CriminalRecord(
        long id,
        UUID playerUuid,
        String charge,
        String severity,
        String status,
        Instant createdAt,
        int version) {

    /**
     * Expungement eligibility (§9.2): every non-expunged record must be a
     * SERVED MINOR offense, and the newest must be older than the crime-free
     * period. Pure function for unit testing.
     */
    public static boolean eligibleForExpungement(
            List<CriminalRecord> activeRecords, Instant now, int crimeFreeDays) {
        if (activeRecords.isEmpty()) {
            return false;
        }
        Instant cutoff = now.minusSeconds(crimeFreeDays * 86_400L);
        for (CriminalRecord record : activeRecords) {
            if (!"MINOR".equals(record.severity()) || !"SERVED".equals(record.status())
                    || record.createdAt().isAfter(cutoff)) {
                return false;
            }
        }
        return true;
    }
}
