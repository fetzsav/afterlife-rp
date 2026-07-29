package com.afterlife.rp.shared.identity;

import java.util.UUID;

/**
 * Permanent identity of a player (master plan §8.1). Nickname may be null;
 * locale is the player's chosen language code (null → server default).
 */
public record PlayerIdentity(UUID uuid, long publicId, String lastName, String nickname,
        String locale) {

    public PlayerIdentity withNickname(String newNickname) {
        return new PlayerIdentity(uuid, publicId, lastName, newNickname, locale);
    }

    public PlayerIdentity withLocale(String newLocale) {
        return new PlayerIdentity(uuid, publicId, lastName, nickname, newLocale);
    }
}
