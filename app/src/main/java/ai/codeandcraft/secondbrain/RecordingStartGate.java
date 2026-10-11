package ai.codeandcraft.secondbrain;

/** Process-local startup ownership; never reconstructed from a Bundle or Intent. */
final class RecordingStartGate {
    static final RecordingStartGate INSTANCE = new RecordingStartGate();
    private long sequence, owner, requestUtc, completedOwner, completedUtc;
    private String completedSession;
    private boolean dispatched;
    synchronized long begin() { return begin(System.currentTimeMillis()); }
    synchronized long begin(long utc) {
        if (owner != 0) return 0;
        owner = ++sequence;
        requestUtc = utc;
        completedOwner = completedUtc = 0;
        completedSession = null;
        dispatched = false;
        return owner;
    }
    synchronized long owner() { return owner; }
    synchronized long requestUtc() { return owner == 0 ? 0 : requestUtc; }
    synchronized boolean pending() { return owner != 0; }
    synchronized boolean claim(long request) {
        if (request == 0 || request != owner || dispatched) return false;
        dispatched = true;
        return true;
    }
    synchronized boolean cancelWaiting(long request) {
        if (request == 0 || request != owner || dispatched) return false;
        owner = 0;
        return true;
    }
    synchronized boolean fail(long request) {
        if (request == 0 || request != owner) return false;
        owner = 0;
        dispatched = false;
        return true;
    }
    synchronized void completed(long request, String session) {
        if (request == 0 || request != owner || session == null) return;
        completedOwner = request;
        completedUtc = requestUtc;
        completedSession = session;
    }
    synchronized boolean canResolveCompleted(long request, long utc, String session) {
        return owner == 0 && request != 0 && request == completedOwner && utc > 0
                && utc == completedUtc && completedSession != null
                && (session == null || session.equals(completedSession));
    }
    synchronized void serviceReleased() { owner = 0; dispatched = false; }
    synchronized boolean canRestore(boolean recording, String status) {
        return pending() || recording || "STARTING".equals(status) || "RECORDING".equals(status);
    }
}
