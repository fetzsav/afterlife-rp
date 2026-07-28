package com.afterlife.rp.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.module.legal.CriminalRecord;
import com.afterlife.rp.module.legal.LegalService;
import com.afterlife.rp.module.realestate.Property;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LegalUnitTest {

    private CriminalRecord record(String severity, String status, Instant createdAt) {
        return new CriminalRecord(1, UUID.randomUUID(), "furto", severity, status, createdAt, 0);
    }

    @Test
    void expungementRequiresServedMinorsOutsideCrimeFreePeriod() {
        Instant now = Instant.now();
        Instant old = now.minusSeconds(10 * 86_400L);

        assertTrue(CriminalRecord.eligibleForExpungement(
                List.of(record("MINOR", "SERVED", old)), now, 7));
        assertFalse(CriminalRecord.eligibleForExpungement(
                List.of(record("MAJOR", "SERVED", old)), now, 7), "major offenses block");
        assertFalse(CriminalRecord.eligibleForExpungement(
                List.of(record("MINOR", "OPEN", old)), now, 7), "unserved offenses block");
        assertFalse(CriminalRecord.eligibleForExpungement(
                List.of(record("MINOR", "SERVED", now.minusSeconds(3600))), now, 7),
                "recent offenses block (crime-free period)");
        assertFalse(CriminalRecord.eligibleForExpungement(List.of(), now, 7),
                "no records means nothing to expunge");
        assertFalse(CriminalRecord.eligibleForExpungement(
                List.of(record("MINOR", "SERVED", old), record("MAJOR", "OPEN", old)), now, 7),
                "one bad record poisons the set");
    }

    @Test
    void contractHashIsDeterministicAndContentSensitive() {
        String hash = LegalService.sha256("contenuto del contratto");
        assertEquals(hash, LegalService.sha256("contenuto del contratto"));
        assertEquals(64, hash.length());
        assertFalse(hash.equals(LegalService.sha256("contenuto del contratto.")));
    }

    @Test
    void policeAlertsExposeDistrictNotExactPosition() {
        Property withRegion = new Property(UUID.randomUUID(), "villa_1", "HOUSE", "world",
                123.9, 64, -487.2, "quartiere_nord", 100000, true, "DIRTY_RENTED", 1, 0);
        assertEquals("quartiere_nord", withRegion.district());

        Property withoutRegion = new Property(UUID.randomUUID(), "villa_2", "HOUSE", "world",
                123.9, 64, -487.2, null, 100000, true, "DIRTY_RENTED", 1, 0);
        assertEquals("zona 100,-400", withoutRegion.district());
    }
}
