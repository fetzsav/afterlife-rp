package com.afterlife.rp.shared.items;

import java.util.UUID;

/** Database record for a serialized physical valuable. */
public record SerializedItem(
        UUID serial,
        String itemType,
        UUID owner,
        Long denomination,
        ItemStatus status,
        UUID issuedBy,
        long issuedAtEpochMs) {}
