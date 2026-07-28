package com.afterlife.rp.shared.items;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 signatures for financial instruments (master plan §2.3: MD5 is
 * forbidden). The key lives outside Git (rule 12) — see SecretKeyManager.
 */
public final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public HmacSigner(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length < 16) {
            throw new IllegalArgumentException("HMAC key must be at least 16 bytes");
        }
        this.key = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    public String sign(String serial, String itemType, long denomination, long issuedAtEpochMs) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            String payload = serial + "|" + itemType + "|" + denomination + "|" + issuedAtEpochMs;
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public boolean verify(
            String signature, String serial, String itemType, long denomination, long issuedAtEpochMs) {
        if (signature == null) {
            return false;
        }
        String expected = sign(serial, itemType, denomination, issuedAtEpochMs);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }
}
