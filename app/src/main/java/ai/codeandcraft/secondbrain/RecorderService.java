package ai.codeandcraft.secondbrain;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.util.Log;
import java.io.*;

public class RecorderService extends Service {
    private static final String TAG = "SecondBrainRecorder";
    public static final String ACTION_START = "ai.codeandcraft.secondbrain.START";
    public static final String ACTION_STOP = "ai.codeandcraft.secondbrain.STOP";
    static final String EXTRA_STOP_REASON = "ai.codeandcraft.secondbrain.STOP_REASON";
    private volatile boolean cleanupRequested;
    private long captureRequestOwner;
    private static final int SAMPLE_RATE = 16000;
    private static final int BUFFER_SIZE = SAMPLE_RATE * 2 * 60; // 60 seconds

    private static volatile boolean recording = false;
    private static volatile String status = "IDLE";
    private volatile AudioRecord audioRecord;
    private Thread recordingThread;
    private PowerManager.WakeLock wakeLock;

    public static boolean isRecording() { return recording; }
    public static String getStatus() { return status; }

    private synchronized void startRecording() {
        if (AutoTranscription.cleanSlateRequested() || recording || "STARTING".equals(status) || "RECORDING".equals(status)) return;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            RecordingStartGate.INSTANCE.fail(captureRequestOwner);
            AutoTranscription.releaseCapture();
            PopUi.capturePending = false;
            return;
        }
        cleanupRequested = false;
        status = "STARTING";
        recording = true;
        
        startForeground(1, createNotification());

        recordingThread = new Thread(() -> {
            WavChunkWriter writer = null;
            SessionStore store = null;
            String completedId = null;
            boolean hasAudio = false;
            try {
                SessionStore.recoverInterrupted(getFilesDir());
                store = SessionStore.create(getFilesDir(), SAMPLE_RATE);
                
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SecondBrain:Recorder");
                wakeLock.acquire(4 * 60 * 60 * 1000);

                int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBufferSize, BUFFER_SIZE));
                
                // STOP and the final cancellation check share the microphone-start lock.
                synchronized (RecorderService.this) {
                    if (recording) {
                        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                            throw new IOException("AudioRecord failed initialization");
                        }
                        audioRecord.startRecording();
                        status = "RECORDING";
                        RecorderDiagnostics.log(this, "CAPTURE_STARTED");
                        Log.i(TAG, "session_start");
                    }
                }

                if (recording) {
                writer = new WavChunkWriter(store.directory(), store.nextChunkIndex(), 16000);
                long lastCheckpoint = SystemClock.elapsedRealtime();
                long totalPayload = 0;
                long lastDataTime = SystemClock.elapsedRealtime();
                byte[] buffer = new byte[4096];
                
                while (recording) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (!recording) break; // break BEFORE negative/zero handling
                    if (read > 0) {
                        if (read % 2 != 0) {
                            throw new IOException("Odd read size: " + read);
                        }
                        lastDataTime = SystemClock.elapsedRealtime();
                        writer.write(buffer, read);
                        totalPayload += read;
                        
                        if (totalPayload >= 1920000) {
                            writer.finish();
                            store.checkpoint("ACTIVE", null);
                            Log.i(TAG, "chunk_complete");
                            writer = new WavChunkWriter(store.directory(), store.nextChunkIndex(), 16000);
                            totalPayload = 0;
                        } else if (SystemClock.elapsedRealtime() - lastCheckpoint >= 1000) {
                            writer.checkpoint();
                            store.checkpoint("ACTIVE", null);
                            lastCheckpoint = SystemClock.elapsedRealtime();
                        }
                    } else if (read == 0) {
                        if (SystemClock.elapsedRealtime() - lastDataTime > 5000) {
                            throw new IOException("No data for 5 seconds");
                        }
                        Thread.sleep(10);
                    } else {
                         throw new IOException("AudioRecord read error: " + read);
                    }
                }

                hasAudio = writer.getPayloadBytes() > 0 || store.nextChunkIndex() > 1;
                } // Cancelled initialization still uses normal zero-audio completion.

                if (writer != null) {
                    writer.finish();
                }
                if (store != null) {
                    store.complete();
                    completedId = store.directory().getName();
                    RecordingStartGate.INSTANCE.completed(captureRequestOwner, completedId);
                    try { if (hasAudio) AutoTranscription.enqueueRecorded(this, completedId); }
                    catch (Exception queueError) { Log.e(TAG, "Saved audio; auto queue unavailable", queueError); }
                }
                status = "STOPPED";
                RecorderDiagnostics.log(this, "CAPTURE_COMPLETED");
            } catch (Exception e) {
                Log.e(TAG, "error", e);
                status = "ERROR";
                RecorderDiagnostics.log(this, "CAPTURE_ERROR");
                if (writer != null) {
                    try { writer.close(); } catch (Exception ignored) {}
                }
                if (store != null) {
                    try {
                        store.checkpoint("ERROR", e.getMessage());
                    } catch (Exception ex) {
                        Log.e(TAG, "checkpoint failure", ex);
                    }
                }
            } finally {
                cleanup();
                try { AutoTranscription.captureReleased(this, completedId); AutoTranscription.kick(this); }
                catch (Exception queueError) { Log.e(TAG, "Saved audio; queue release unavailable", queueError); }
                finally { RecordingStartGate.INSTANCE.fail(captureRequestOwner); }
            }
        });
        recordingThread.start();
    }

    private void cleanup() {
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
            wakeLock = null;
        }
        recording = false;
        if (!"ERROR".equals(status) && !"STOPPED".equals(status)) {
            status = "IDLE";
        }
        stopForeground(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? Service.STOP_FOREGROUND_REMOVE : Service.STOP_FOREGROUND_REMOVE);
        cleanupRequested = true;
        stopSelf();
    }

    private void stopRecording() {
        synchronized (this) {
            recording = false;
            if (audioRecord != null) {
                try { audioRecord.stop(); } catch (Exception ignored) {}
            }
        }
        if (recordingThread != null) {
            try { recordingThread.join(1000); } catch (InterruptedException ignored) {}
        }
        Log.i(TAG, "session_stop");
    }

    private Notification createNotification() {
        NotificationChannel channel = new NotificationChannel("recorder", "Recorder", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        
        Intent stopIntent = new Intent(this, RecorderService.class);
        stopIntent.setAction(ACTION_STOP);
        stopIntent.putExtra(EXTRA_STOP_REASON, "NOTIFICATION_STOP");
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, "recorder")
                .setContentTitle("Recording")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                if (!recording && !"STARTING".equals(status) && !"RECORDING".equals(status))
                    captureRequestOwner = RecordingStartGate.INSTANCE.owner();
                try { startRecording(); }
                catch (RuntimeException failure) {
                    status = "ERROR";
                    cleanup();
                    AutoTranscription.releaseCapture();
                    PopUi.capturePending = false;
                    RecordingStartGate.INSTANCE.fail(captureRequestOwner);
                    throw failure;
                }
            } else if (ACTION_STOP.equals(action)) {
                String reason = null;
                try { reason = intent.getStringExtra(EXTRA_STOP_REASON); } catch (Exception ignored) {}
                RecorderDiagnostics.log(this, RecorderDiagnostics.stopReason(reason));
                stopRecording();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
    @Override
    public void onDestroy() {
        String event = cleanupRequested && "ERROR".equals(status) ? "SERVICE_DESTROY_AFTER_ERROR"
                : cleanupRequested && "STOPPED".equals(status) ? "SERVICE_DESTROY_AFTER_COMPLETION"
                : recording || "STARTING".equals(status) || "RECORDING".equals(status)
                ? "SERVICE_DESTROY_ACTIVE" : "SERVICE_DESTROY_INACTIVE";
        RecorderDiagnostics.log(this, event);
        stopRecording();
        if (!cleanupRequested && RecordingStartGate.INSTANCE.fail(captureRequestOwner)) {
            AutoTranscription.releaseCapture();
            PopUi.capturePending = false;
        }
        super.onDestroy();
    }
}
