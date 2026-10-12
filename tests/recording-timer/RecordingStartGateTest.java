package ai.codeandcraft.secondbrain;

/** JVM model of Activity recreation, with production gate and guard; no Android runtime. */
public final class RecordingStartGateTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    private static final class Host {
        final RecordingStartGate gate;
        String page, id;
        long base, owner, utc;
        boolean finishing;
        Host(RecordingStartGate gate, Host saved, boolean active, String status) {
            this.gate = gate;
            page = saved == null ? "Meetings" : saved.page;
            base = saved == null ? -1 : saved.base;
            id = saved == null ? null : saved.id;
            finishing = saved != null && saved.finishing;
            utc = saved == null ? 0 : saved.utc;
            owner = saved == null ? 0 : saved.owner;
            boolean completedFinish = finishing && "STOPPED".equals(status) && !active && gate.canResolveCompleted(owner, utc, id);
            if (gate.canRestore(active, status)) { owner = gate.owner(); if (gate.requestUtc() > 0) utc = gate.requestUtc(); }
            if (!gate.canRestore(active, status) && !completedFinish) { if ("Record".equals(page)) page = "Meetings"; finishing = false; }
        }
        void trustedPermissionGrant(long now) {
            if (gate.pending() || finishing) return;
            owner = gate.begin(now);
            if (owner != 0) { utc = now; page = "Record"; }
        }
        void restoredIntent(RecordEntryGuard guard, String token) {
            check(!guard.allowRecord("Record", token, true)); // Render only; saved page wins.
        }
    }
    public static void main(String[] args) {
        // Permission dialog alone is not live capture; trusted grant restores Record.
        RecordingStartGate permissionGate = new RecordingStartGate();
        Host dialog = new Host(permissionGate, null, false, "IDLE");
        dialog.page = "Record";
        dialog = new Host(permissionGate, dialog, false, "IDLE");
        check(dialog.page.equals("Meetings") && !permissionGate.pending());
        dialog.trustedPermissionGrant(777);
        check(dialog.page.equals("Record") && permissionGate.pending());
        long permissionOwner = dialog.owner;
        dialog.trustedPermissionGrant(778);
        check(dialog.owner == permissionOwner && permissionGate.owner() == permissionOwner);
        RecordingStartGate gate = new RecordingStartGate();
        RecordEntryGuard guard = new RecordEntryGuard(() -> 42L);
        Host host = new Host(gate, null, false, "IDLE");
        host.page = "Record"; host.base = 100; host.id = "same-session";
        host.utc = 1234; host.owner = gate.begin(host.utc);
        check(host.owner != 0);
        Host detailReentry = new Host(gate, null, false, "IDLE");
        check(detailReentry.owner == host.owner && detailReentry.utc == host.utc);
        // ASR remains busy through repeated portrait/landscape recreation.
        for (int i = 0; i < 12; i++) {
            host = new Host(gate, host, false, "IDLE");
            host.restoredIntent(guard, guard.issueFromDetailClick());
            check(host.page.equals("Record") && host.base == 100 && host.id.equals("same-session"));
            check(gate.begin() == 0); // Another UI cannot reset the pending request.
        }
        check(gate.claim(host.owner)); // ASR quiescent: exactly one START, before any service callback.
        for (int i = 0; i < 12; i++) {
            host = new Host(gate, host, false, "IDLE");
            check(host.page.equals("Record") && !gate.claim(host.owner));
        }
        check(!gate.cancelWaiting(host.owner)); // Dispatched start must be stopped through service.
        for (String status : new String[]{"STARTING", "RECORDING"}) {
            for (int i = 0; i < 12; i++) {
                host = new Host(gate, host, true, status);
                check(host.page.equals("Record") && host.base == 100 && host.id.equals("same-session"));
                check(1000 + i * 500 - host.base == 900 + i * 500); // Monotonic elapsed invariant.
            }
        }
        host.finishing = true;
        host = new Host(gate, host, false, "RECORDING");
        check(host.page.equals("Record") && host.finishing);
        // Service publishes completion before finally releases the request.
        gate.completed(host.owner, host.id);
        check(!gate.canResolveCompleted(host.owner, host.utc, host.id));
        check(gate.fail(host.owner));
        host = new Host(gate, host, false, "STOPPED");
        check(host.page.equals("Record") && host.finishing);
        check(gate.canResolveCompleted(host.owner, host.utc, host.id));
        check(!gate.canResolveCompleted(host.owner, host.utc + 1, host.id));
        check(!gate.canResolveCompleted(host.owner, host.utc, "other-session"));
        check(!new RecordingStartGate().canResolveCompleted(host.owner, host.utc, host.id));
        // Stale STOPPED cannot resolve Finish after a new START is dispatched.
        long preMic = gate.begin(5678);
        check(gate.claim(preMic) && !gate.cancelWaiting(preMic));
        check(!gate.canResolveCompleted(host.owner, host.utc, host.id));
        check(!gate.canResolveCompleted(preMic, 5678, null));
        // Model the cancelled initialization's normal completion, not Android audio.
        gate.completed(preMic, "zero-audio-session");
        check(!gate.canResolveCompleted(preMic, 5678, null));
        check(gate.fail(preMic));
        check(gate.canResolveCompleted(preMic, 5678, null));
        check(!gate.fail(preMic)); // A second cleanup cannot release ownership again.
        Host early = new Host(gate, null, false, "STOPPED");
        early.page = "Record"; early.finishing = true; early.owner = preMic; early.utc = 5678;
        early = new Host(gate, early, false, "STOPPED");
        check(early.finishing && early.page.equals("Record"));
        long cancelled = gate.begin();
        check(gate.cancelWaiting(cancelled));
        check(!gate.claim(cancelled));
        long failed = gate.begin();
        check(gate.claim(failed) && gate.fail(failed));
        long later = gate.begin();
        check(later != failed && !gate.claim(failed) && !gate.fail(failed));
        check(gate.claim(later));
        gate.serviceReleased();
        host.page = "Record"; host.finishing = true;
        Host restarted = new Host(new RecordingStartGate(), host, false, "IDLE");
        check(restarted.page.equals("Meetings") && !restarted.finishing);
        check(!gate.canRestore(false, "ERROR") && !gate.canRestore(false, "STOPPED"));
        check(gate.canRestore(true, "IDLE") && gate.canRestore(false, "STARTING"));
        check(!guard.allowRecord("Record", null, false));
        check(!guard.allowRecord("Record", "forged", false));
        String token = guard.issueFromDetailClick();
        check(guard.allowRecord("Record", token, false));
        check(!guard.allowRecord("Record", token, false));
        System.out.println("PASS: " + checks + " startup/rotation host model checks (production gate/guard; no Android runtime)");
    }
}
