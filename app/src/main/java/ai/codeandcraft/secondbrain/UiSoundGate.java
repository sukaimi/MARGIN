package ai.codeandcraft.secondbrain;

/** Android-free admission and callback invalidation. No pending cue is ever stored. */
final class UiSoundGate {
    private long generation, lastTap = Long.MIN_VALUE;
    private boolean active;
    private Object playbackOwner;
    private long muteEpoch, resourceGeneration;
    long resourceGeneration() { return resourceGeneration; }
    void retain(Object owner) { playbackOwner = owner; }
    void close(Object owner) { if (playbackOwner == owner) playbackOwner = null; }
    boolean playback() { return playbackOwner != null; }
    boolean releasable(long token) { return !active && token == resourceGeneration; }
    long muteEpoch() { return muteEpoch; }
    void mute() { muteEpoch++; resourceGeneration++; invalidate(); }
    static final class FinishMarker {
        private boolean pending;
        private final long epoch;
        FinishMarker(boolean manual, boolean restored, long epoch) { pending = manual && !restored; this.epoch = epoch; }
        boolean consume(long currentEpoch) { boolean result = pending && epoch == currentEpoch; pending = false; return result; }
    }
    long generation() { return generation; }
    void resume() { active = true; generation++; resourceGeneration++; }
    // Normal completion has no pending action and preserves the current session.
    boolean complete(long token) { return current(token); }
    void invalidate() { generation++; }
    void pause() { active = false; resourceGeneration++; invalidate(); }
    boolean current(long token) { return active && token == generation; }
    static boolean captureBlocked(boolean pending, boolean uiPending, boolean requested,
            boolean recording, String status) {
        return pending || uiPending || requested || recording
                || !("STOPPED".equals(status) || "IDLE".equals(status) || "ERROR".equals(status));
    }
    boolean eligible(long now, long token, boolean enabled, boolean loaded, boolean capture,
            boolean playback, boolean policy) {
        return current(token) && enabled && loaded && !capture && !playback && policy
                && (lastTap == Long.MIN_VALUE || (now >= lastTap && now - lastTap >= 180));
    }
    boolean admit(long now, long token, boolean enabled, boolean loaded, boolean capture,
            boolean playback, boolean policy, boolean focus) {
        if (!current(token) || !enabled || !loaded || capture || playback || !policy || !focus) return false;
        if (lastTap != Long.MIN_VALUE && (now < lastTap || now - lastTap < 180)) return false;
        lastTap = now;
        return true;
    }
}
