package com.afterlife.rp.unit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.afterlife.rp.shared.items.HmacSigner;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HmacSignerTest {

    private final HmacSigner signer =
            new HmacSigner("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    void signAndVerifyRoundTrip() {
        String signature = signer.sign("serial-1", "dirty_money", 500, 1_000_000L);
        assertTrue(signer.verify(signature, "serial-1", "dirty_money", 500, 1_000_000L));
    }

    @Test
    void tamperedFieldsFailVerification() {
        String signature = signer.sign("serial-1", "dirty_money", 500, 1_000_000L);
        assertFalse(signer.verify(signature, "serial-2", "dirty_money", 500, 1_000_000L));
        assertFalse(signer.verify(signature, "serial-1", "banknote", 500, 1_000_000L));
        assertFalse(signer.verify(signature, "serial-1", "dirty_money", 501, 1_000_000L));
        assertFalse(signer.verify(signature, "serial-1", "dirty_money", 500, 1_000_001L));
        assertFalse(signer.verify(null, "serial-1", "dirty_money", 500, 1_000_000L));
    }

    @Test
    void differentKeysProduceDifferentSignatures() {
        HmacSigner other =
                new HmacSigner("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(
                signer.sign("serial-1", "dirty_money", 500, 1_000_000L),
                other.sign("serial-1", "dirty_money", 500, 1_000_000L));
    }

    @Test
    void rejectsWeakKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> new HmacSigner("short".getBytes(StandardCharsets.UTF_8)));
    }
}
