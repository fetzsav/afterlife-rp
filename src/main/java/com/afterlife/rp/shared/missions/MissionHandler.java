package com.afterlife.rp.shared.missions;

/**
 * Module hook for mission lifecycle events raised by the framework
 * (expiry sweep, quit/startup cleanup, AFK cancellation).
 */
public interface MissionHandler {

    /** Called off the main thread after a mission left ACTIVE via the framework. */
    void onEnded(Mission mission, String endState);

    /** Warn/cancel thresholds in seconds for AFK enforcement; null disables it. */
    default int[] afkWarnCancelSeconds() {
        return null;
    }
}
