package com.afterlife.rp.shared.items;

import java.util.UUID;

/** Database record for a serialized physical valuable. Metadata is JSON or null. */
public record SerializedItem(
        UUID serial,
        String itemType,
        UUID owner,
        Long denomination,
        ItemStatus status,
        UUID issuedBy,
        long issuedAtEpochMs,
        String metadata) {}
