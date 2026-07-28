package com.afterlife.rp.shared.gui;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Server-side state for one open GUI (rule 7: sessions, never title matching). */
public final class GuiSession {

    private final UUID id = UUID.randomUUID();
    private final UUID playerId;
    private final GuiMenu menu;
    private final Instant createdAt = Instant.now();
    private volatile Instant lastActivityAt = Instant.now();

    public GuiSession(UUID playerId, GuiMenu menu) {
        this.playerId = playerId;
        this.menu = menu;
    }

    public UUID id() {
        return id;
    }

    public UUID playerId() {
        return playerId;
    }

    public GuiMenu menu() {
        return menu;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public void touch() {
        lastActivityAt = Instant.now();
    }

    public boolean expired(Duration timeout) {
        return Instant.now().isAfter(lastActivityAt.plus(timeout));
    }
}
