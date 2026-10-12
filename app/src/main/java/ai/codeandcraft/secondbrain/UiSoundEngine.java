package ai.codeandcraft.secondbrain;

import android.app.NotificationManager;
import android.content.Context;
import android.media.*;
import android.os.*;
import android.provider.Settings;
import java.util.HashSet;

/** Process-local UI audio; retains only application context. All entry points are on main. */
final class UiSoundEngine {
    static final UiSoundEngine INSTANCE = new UiSoundEngine();
    private final UiSoundGate gate = new UiSoundGate();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler resourceHandler = new Handler(Looper.getMainLooper());
    private final Handler readinessHandler = new Handler(Looper.getMainLooper());
    private final HashSet<Integer> loaded = new HashSet<>();
    private Context app;
    private SoundPool pool;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private int ceramic, paper, wood, stream;
    private long focusSequence;
    private Runnable cleanup;
    private boolean foreground;
    private Runnable resourceRelease;
    private static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    private boolean enabled() { return app != null && app.getSharedPreferences("pop-ui",0).getBoolean("sound",true); }
    private boolean capture() {
        return UiSoundGate.captureBlocked(RecordingStartGate.INSTANCE.pending(), PopUi.capturePending,
                AutoTranscription.captureRequested(), RecorderService.isRecording(), RecorderService.getStatus());
    }
    void resume(Context context) {
        app = context.getApplicationContext();
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (resourceRelease != null) resourceHandler.removeCallbacks(resourceRelease);
        resourceRelease = null;
        foreground = true; gate.resume(); preload();
    }
    void pause() {
        foreground = false; gate.pause(); stop();
        final long token = gate.resourceGeneration();
        resourceRelease = () -> { if (gate.releasable(token)) releaseResources(); };
        resourceHandler.postDelayed(resourceRelease,180);
    }
    void playbackStarted(Object owner) { gate.retain(owner); stop(); }
    void playbackClosed(Object owner) { gate.close(owner); }
    long muteEpoch() { return gate.muteEpoch(); }
    long generation() { return gate.generation(); }
    boolean current(long token) { return gate.current(token); }
    void prepare(Context context) { if (app == null) app = context.getApplicationContext(); preload(); }
    private void preload() {
        if (pool != null || !foreground || !enabled() ) return;
        try {
            final SoundPool candidate = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(ATTRIBUTES).build();
            pool = candidate;
            candidate.setOnLoadCompleteListener((p,id,status) -> readinessHandler.post(() -> {
                // Loading only marks readiness; it never replays the skipped action.
                if (pool == p && enabled() && status == 0) loaded.add(id);
            }));
            ceramic = candidate.load(app,R.raw.ui_ceramic,1);
            paper = candidate.load(app,R.raw.ui_paper,1);
            wood = candidate.load(app,R.raw.ui_wood,1);
        } catch (RuntimeException error) { release(); }
    }
    private boolean policy() {
        try {
            NotificationManager notifications = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            return audio != null && notifications != null
                    && audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL
                    && audio.getStreamVolume(ATTRIBUTES.getVolumeControlStream()) > 0
                    && Settings.System.getInt(app.getContentResolver(),Settings.System.SOUND_EFFECTS_ENABLED,0) == 1
                    && notifications.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (RuntimeException error) { return false; }
    }
    void play(Context context,String kind) {
        if (app == null) app = context.getApplicationContext();
        if (!foreground || !enabled()) { release(); return; }
        if (capture() || gate.playback() || !policy()) { stop(); return; }
        preload();
        int id = "tab".equals(kind) ? paper : ("save".equals(kind) || "finish".equals(kind)) ? wood : ceramic;
        if (pool == null || !loaded.contains(id)) return;
        final long token = gate.generation();
        if (!gate.eligible(SystemClock.elapsedRealtime(),token,enabled(),loaded.contains(id),capture(),gate.playback(),policy())) return;
        try {
            if (cleanup != null) handler.removeCallbacks(cleanup);
            endCue();
            final long focusToken = ++focusSequence;
            final AudioFocusRequest[] request = new AudioFocusRequest[1];
            request[0] = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(ATTRIBUTES).setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(change -> {
                        if (change != AudioManager.AUDIOFOCUS_GAIN && focus == request[0] && focusToken == focusSequence && gate.current(token)) stop();
                    },handler).build();
            focus = request[0];
            boolean granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!gate.admit(SystemClock.elapsedRealtime(),token,enabled(),loaded.contains(id),capture(),gate.playback(),policy(),granted)) {
                stop(); return;
            }
            if (stream != 0) pool.stop(stream);
            stream = pool.play(id,.30f,.30f,1,0,1f);
            cleanup = () -> { if (gate.complete(token) && focus == request[0] && focusToken == focusSequence) endCue(); };
            handler.postDelayed(cleanup,650);
        } catch (RuntimeException error) { stop(); }
    }
    private void endCue() {
        focusSequence++;
        if (cleanup != null) handler.removeCallbacks(cleanup);
        cleanup = null;
        if (pool != null && stream != 0) try { pool.stop(stream); } catch (RuntimeException ignored) {}
        stream = 0;
        AudioFocusRequest previous = focus; focus = null;
        if (audio != null && previous != null) try { audio.abandonAudioFocusRequest(previous); } catch (RuntimeException ignored) {}
    }
    // Cancellation invalidates action callbacks, but loaded samples remain usable.
    void stop() {
        gate.invalidate(); handler.removeCallbacksAndMessages(null);
        endCue();
    }
    // Mute invalidates one-shot markers and immediately releases resources.
    void release() {
        gate.mute(); stop(); releaseResources();
    }
    private void releaseResources() {
        if (resourceRelease != null) resourceHandler.removeCallbacks(resourceRelease);
        resourceRelease = null;
        readinessHandler.removeCallbacksAndMessages(null);
        loaded.clear();
        SoundPool old = pool; pool = null;
        ceramic = paper = wood = 0;
        if (old != null) try { old.release(); } catch (RuntimeException ignored) {}
    }
}
