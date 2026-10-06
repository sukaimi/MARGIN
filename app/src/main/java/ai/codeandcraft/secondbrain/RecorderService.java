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
        if (recording || "STARTING".equals(status) || "RECORDING".equals(status)) return;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        status = "STARTING";
        recording = true;
        
        startForeground(1, createNotification());

        recordingThread = new Thread(() -> {
            WavChunkWriter writer = null;
            SessionStore store = null;
            String completedId = null;
            try {
                SessionStore.recoverInterrupted(getFilesDir());
                store = SessionStore.create(getFilesDir(), SAMPLE_RATE);
                
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SecondBrain:Recorder");
                wakeLock.acquire(4 * 60 * 60 * 1000);

                int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBufferSize, BUFFER_SIZE));
                
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IOException("AudioRecord failed initialization");
                }
                
                if (!recording) return; // Check cancellation before AudioRecord.startRecording

                audioRecord.startRecording();
                status = "RECORDING";
                Log.i(TAG, "session_start");

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

                if (writer != null) {
                    writer.finish();
                }
                if (store != null) {
                    store.complete();
                    completedId = store.directory().getName();
                    try { AutoTranscription.enqueueRecorded(this, completedId); }
                    catch (Exception queueError) { Log.e(TAG, "Saved audio; auto queue unavailable", queueError); }
                }
                status = "STOPPED";
            } catch (Exception e) {
                Log.e(TAG, "error", e);
                status = "ERROR";
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
        stopSelf();
    }

    private void stopRecording() {
        recording = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
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
                startRecording();
            } else if (ACTION_STOP.equals(action)) {
                stopRecording();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
    @Override
    public void onDestroy() { stopRecording(); super.onDestroy(); }
}
