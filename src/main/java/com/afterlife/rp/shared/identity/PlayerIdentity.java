package com.afterlife.rp.shared.identity;

import java.util.UUID;

/** Permanent identity of a player (master plan §8.1). Nickname may be null. */
public record PlayerIdentity(UUID uuid, long publicId, String lastName, String nickname) {

    public PlayerIdentity withNickname(String newNickname) {
        return new PlayerIdentity(uuid, publicId, lastName, newNickname);
    }
}
