package com.afterlife.rp.shared.items;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.logging.Logger;

/**
 * Loads or creates the HMAC secret in the plugin data folder — never in Git
 * (rule 12). File: plugins/AfterLifeRP/secret.key (hex, 32 bytes).
 */
public final class SecretKeyManager {

    private SecretKeyManager() {}

    public static byte[] loadOrCreate(Path file, Logger logger) throws IOException {
        if (Files.exists(file)) {
            String hex = Files.readString(file, StandardCharsets.UTF_8).strip();
            return HexFormat.of().parseHex(hex);
        }
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Files.createDirectories(file.getParent());
        Files.writeString(file, HexFormat.of().formatHex(key), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g. Windows dev machine); acceptable.
        }
        logger.info("Generated new HMAC secret at " + file);
        return key;
    }
}
