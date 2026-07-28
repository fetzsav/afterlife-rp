package com.afterlife.rp.shared.economy;

import java.util.UUID;

/** A clean-money account (personal, organization, or system clearing). */
public record Account(
        UUID id,
        OwnerType ownerType,
        UUID ownerRef,
        String code,
        String iban,
        long balance,
        boolean allowNegative,
        boolean frozen,
        String frozenReason,
        UUID frozenBy,
        int version) {

    public enum OwnerType { PLAYER, ORGANIZATION, SYSTEM }
}
