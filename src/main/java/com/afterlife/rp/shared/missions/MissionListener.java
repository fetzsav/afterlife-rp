package com.afterlife.rp.shared.missions;

import com.afterlife.rp.database.DatabaseManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Framework lifecycle: active missions load into cache on join; disconnecting
 * cancels active missions and ends duty (§15 disconnect cleanup).
 */
public final class MissionListener implements Listener {

    private final DatabaseManager databaseManager;
    private final MissionService missionService;
    private final JobSessionService jobSessions;

    public MissionListener(DatabaseManager databaseManager, MissionService missionService,
            JobSessionService jobSessions) {
        this.databaseManager = databaseManager;
        this.missionService = missionService;
        this.jobSessions = jobSessions;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (databaseManager.ready()) {
            missionService.loadActiveOnJoin(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!databaseManager.ready()) {
            return;
        }
        missionService.cancelAllFor(event.getPlayer().getUniqueId(), "quit");
        jobSessions.endAll(event.getPlayer().getUniqueId());
        missionService.evict(event.getPlayer().getUniqueId());
    }
}
