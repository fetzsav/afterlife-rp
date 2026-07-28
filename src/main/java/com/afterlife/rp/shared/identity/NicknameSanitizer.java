package com.afterlife.rp.shared.identity;

import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Sanitizes VIP nicknames: strips MiniMessage tags, control characters, and
 * misleading/invisible Unicode, then enforces visible length (master plan §8.1).
 */
public final class NicknameSanitizer {

    private NicknameSanitizer() {}

    public static Optional<String> sanitize(String raw, int minLength, int maxLength) {
        if (raw == null) {
            return Optional.empty();
        }
        String stripped = MiniMessage.miniMessage().stripTags(raw);
        StringBuilder cleaned = new StringBuilder();
        stripped.codePoints().forEach(codePoint -> {
            if (isAllowed(codePoint)) {
                cleaned.appendCodePoint(codePoint);
            }
        });
        String result = cleaned.toString().strip();
        int visibleLength = result.codePointCount(0, result.length());
        if (visibleLength < minLength || visibleLength > maxLength) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    private static boolean isAllowed(int codePoint) {
        if (Character.isISOControl(codePoint)) {
            return false;
        }
        int type = Character.getType(codePoint);
        return type != Character.FORMAT
                && type != Character.CONTROL
                && type != Character.PRIVATE_USE
                && type != Character.UNASSIGNED
                && type != Character.SURROGATE
                && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR;
    }
}
