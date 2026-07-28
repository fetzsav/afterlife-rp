package com.afterlife.rp.module.realestate;

import java.util.UUID;

/** A property row. State: AVAILABLE/OWNED/DIRTY_AVAILABLE/DIRTY_RENTED. */
public record Property(
        UUID id,
        String name,
        String type,
        String world,
        double x,
        double y,
        double z,
        String regionId,
        long price,
        boolean dirty,
        String state,
        int lockVersion,
        int version) {

    /** District shown in police alerts: region name or coarse coordinates only (§9.7). */
    public String district() {
        if (regionId != null && !regionId.isBlank()) {
            return regionId;
        }
        return "zona " + (((int) x) / 100 * 100) + "," + (((int) z) / 100 * 100);
    }
}
