package ai.codeandcraft.secondbrain;

/** Deterministic host checks of the production gate; no device audio claims. */
public final class UiSoundGateTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static boolean attempt(UiSoundGate g,long now,long token,boolean enabled,boolean loaded,boolean capture,boolean playback,boolean policy,boolean focus) {
        return g.admit(now,token,enabled,loaded,capture,playback,policy,focus);
    }
    public static void main(String[] args) {
        for (String state : new String[]{"IDLE","STOPPED","ERROR"}) {
            check(!UiSoundGate.captureBlocked(false,false,false,false,state));
            for (int flag=0;flag<4;flag++) check(UiSoundGate.captureBlocked(flag==0,flag==1,flag==2,flag==3,state));
        }
        for (String state : new String[]{"STARTING","RECORDING","STOPPING","SAVING","UNKNOWN",null})
            check(UiSoundGate.captureBlocked(false,false,false,false,state));
        // Early recording=false, status still RECORDING; then STOPPED before request release.
        check(UiSoundGate.captureBlocked(false,false,true,false,"RECORDING"));
        check(UiSoundGate.captureBlocked(false,false,true,false,"STOPPED"));
        check(!UiSoundGate.captureBlocked(false,false,false,false,"STOPPED"));
        UiSoundGate g=new UiSoundGate(); long token=g.generation();
        check(!attempt(g,0,token,true,true,false,false,true,true));
        g.resume(); token=g.generation();
        // Each independent blocker rejects and does not spend the rate limit.
        for(int blocker=0;blocker<6;blocker++) check(!attempt(g,1000,token,blocker!=0,blocker!=1,blocker==2,blocker==3,blocker!=4,blocker!=5));
        check(attempt(g,1000,token,true,true,false,false,true,true));
        check(!attempt(g,1179,token,true,true,false,false,true,true));
        check(attempt(g,1180,token,true,true,false,false,true,true));
        check(!attempt(g,1100,token,true,true,false,false,true,true));
        // Normal expiry retains the session: repeated isolated deliberate taps work.
        check(g.complete(token));check(g.current(token));
        check(attempt(g,1830,token,true,true,false,false,true,true));
        check(g.complete(token));
        check(attempt(g,2480,token,true,true,false,false,true,true));
        // Mute/focus loss/start capture invalidates queued action and stop callbacks.
        g.invalidate();check(!g.current(token));check(!g.complete(token));
        check(!attempt(g,2700,token,true,true,false,false,true,true));
        token=g.generation();check(!attempt(g,2700,token,false,true,false,false,true,true));
        check(!attempt(g,2700,token,true,false,false,false,true,true));
        // Readiness alone neither admits nor spends a tap: only a deliberate attempt follows.
        check(g.current(token));
        check(attempt(g,2700,token,true,true,false,false,true,true));
        g.pause();check(!g.current(token));
        check(!attempt(g,2900,g.generation(),true,true,false,false,true,true));
        g.resume();check(!g.current(token));token=g.generation();
        // Persistent OFF is supplied on recreation/reopen, independent of lifecycle state.
        check(!attempt(g,2900,token,false,true,false,false,true,true));
        check(!attempt(g,2900,token,true,true,false,true,true,true)); // retained paused/preparing player
        check(attempt(g,2900,token,true,true,false,false,true,true)); // only after close
        // Model side effects only after the production preflight: rapid rejection
        // must leave both the current cue and other-app focus undisturbed.
        UiSoundGate taps=new UiSoundGate();taps.resume();long tapToken=taps.generation();
        int ends=0, focusRequests=0;
        for(long now:new long[]{1000,1001,1100,1179,1180}) {
            if(taps.eligible(now,tapToken,true,true,false,false,true)) {
                ends++;focusRequests++;
                check(taps.admit(now,tapToken,true,true,false,false,true,true));
            }
        }
        check(ends==2 && focusRequests==2);check(taps.complete(tapToken));
        // Mute keeps retained/preparing playback suppression until the owner closes.
        Object retained=new Object();taps.retain(retained);taps.mute();check(taps.playback());
        taps.close(new Object());check(taps.playback());taps.close(retained);check(!taps.playback());
        // Preflight rejects rapid taps without touching a current cue/focus.
        check(!g.eligible(3000,token,true,true,false,false,true));
        check(g.complete(token));
        check(g.eligible(3080,token,true,true,false,false,true));
        Object oldOwner=new Object(), newOwner=new Object();
        g.retain(oldOwner);check(g.playback());
        g.retain(newOwner);g.close(oldOwner);check(g.playback());
        check(!g.eligible(3300,token,true,true,false,g.playback(),true));
        g.close(newOwner);check(!g.playback());
        // Old pause resource release cannot free a resumed session.
        g.pause();long releaseToken=g.resourceGeneration();check(g.releasable(releaseToken));
        g.invalidate();check(g.releasable(releaseToken)); // stopping again cannot leak the inactive pool
        check(!g.eligible(3500,releaseToken,true,true,false,false,true));
        g.resume();check(!g.releasable(releaseToken));
        check(g.current(g.generation())); // readiness survives normal expiry/resume
        UiSoundGate.FinishMarker marker=new UiSoundGate.FinishMarker(true,false,g.muteEpoch());
        check(marker.consume(g.muteEpoch()));check(!marker.consume(g.muteEpoch()));
        check(!new UiSoundGate.FinishMarker(true,true,g.muteEpoch()).consume(g.muteEpoch()));
        check(!new UiSoundGate.FinishMarker(false,false,g.muteEpoch()).consume(g.muteEpoch()));
        marker=new UiSoundGate.FinishMarker(true,false,g.muteEpoch());g.mute();
        check(!marker.consume(g.muteEpoch()));check(!marker.consume(g.muteEpoch()));
        // A blocked/not-ready first resume consumes forever, even after closure/readiness.
        for(int blocker=0;blocker<6;blocker++) {
            if(blocker==5) g.pause();
            marker=new UiSoundGate.FinishMarker(true,false,g.muteEpoch());
            check(marker.consume(g.muteEpoch()));
            check(!g.eligible(4000,g.generation(),blocker!=0,blocker!=1,blocker==2,blocker==3,blocker!=4));
            check(!marker.consume(g.muteEpoch()));
        }
        System.out.println("PASS: " + checks + " deterministic UI sound host checks; Android/physical acceptance pending");
    }
}
