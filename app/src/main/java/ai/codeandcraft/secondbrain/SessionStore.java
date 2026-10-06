package ai.codeandcraft.secondbrain;

import java.io.*;
import java.util.*;
import org.json.JSONObject;
import org.json.JSONException;
import android.util.AtomicFile;
import java.nio.charset.StandardCharsets;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONArray;

public class SessionStore {
    private final File directory;
    private final AtomicFile metaFile;

    private SessionStore(File directory) {
        this.directory = directory;
        this.metaFile = new AtomicFile(new File(directory, "session.json"));
    }

    public static synchronized SessionStore create(File filesDir, int sampleRate) throws IOException {
        File sessionsDir = new File(filesDir, "sessions");
        if (!sessionsDir.exists() && !sessionsDir.mkdirs()) {
            throw new IOException("Failed to create sessions directory");
        }
        File sessionDir = new File(sessionsDir, UUID.randomUUID().toString());
        if (!sessionDir.mkdirs()) {
            throw new IOException("Failed to create session directory");
        }
        
        SessionStore store = new SessionStore(sessionDir);
        try {
            JSONObject json = new JSONObject();
            json.put("id", sessionDir.getName());
            json.put("sampleRate", sampleRate);
            json.put("startUtc", System.currentTimeMillis());
            json.put("status", "ACTIVE");
            json.put("updatedUtc", System.currentTimeMillis());
            
            writeMetadata(store.metaFile, json);
            fsyncDirectory(sessionDir);
            fsyncDirectory(sessionsDir);
        } catch (Exception e) {
            throw new IOException("Failed to initialize session", e);
        }
        return store;
    }

    public int nextChunkIndex() {
        File[] files = directory.listFiles((dir, name) -> (name.startsWith("chunk-") && (name.endsWith(".wav") || name.endsWith(".wav.part"))));
        int maxIndex = -1;
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                try {
                    int index = Integer.parseInt(name.substring(6, name.indexOf('.')));
                    if (index > maxIndex) maxIndex = index;
                } catch (Exception ignored) {}
            }
        }
        return maxIndex + 1;
    }

    private JSONObject refreshChunks(JSONObject json) throws IOException {
        try {
            int sampleRate = json.optInt("sampleRate", 16000);
            if (sampleRate <= 0) {
                throw new IOException("Invalid sampleRate: " + sampleRate);
            }
            File[] files = directory.listFiles();
            if (directory.exists() && files == null) {
                throw new IOException("Failed to list files in directory: " + directory);
            }
            JSONArray chunksArray = new JSONArray();
            JSONArray pendingArray = new JSONArray();
            long totalPayloadBytes = 0;
            long totalDurationMillis = 0;

            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File file : files) {
                    String name = file.getName();
                    if (name.startsWith("chunk-") && name.endsWith(".wav")) {
                        long length = file.length();
                        long payloadBytes = Math.max(0L, length - 44L);
                        long durationMillis = payloadBytes * 1000L / (sampleRate * 2L);

                        JSONObject chunkObj = new JSONObject();
                        chunkObj.put("filename", name);
                        chunkObj.put("payloadBytes", payloadBytes);
                        chunkObj.put("durationMillis", durationMillis);
                        chunksArray.put(chunkObj);

                        totalPayloadBytes += payloadBytes;
                        totalDurationMillis += durationMillis;
                    } else if (name.startsWith("chunk-") && name.endsWith(".wav.part")) {
                        pendingArray.put(name);
                    }
                }
            }

            json.put("chunks", chunksArray);
            json.put("pending", pendingArray);
            json.put("totalPayloadBytes", totalPayloadBytes);
            json.put("totalDurationMillis", totalDurationMillis);
        } catch (JSONException e) {
            throw new IOException("JSON error during refreshChunks", e);
        }
        return json;
    }

    public void checkpoint(String status, String error) throws IOException {
        try {
            JSONObject json = new JSONObject(new String(metaFile.readFully(), StandardCharsets.UTF_8));
            json.put("status", status);
            if (error != null) {
                json.put("error", error);
            }
            json.put("updatedUtc", System.currentTimeMillis());
            
            json = refreshChunks(json);
            
            writeMetadata(metaFile, json);
            fsyncDirectory(directory);
        } catch (JSONException e) {
            throw new IOException("Failed to checkpoint", e);
        }
    }

    public File directory() {
        return directory;
    }

    public void complete() throws IOException {
        checkpoint("COMPLETED", null);
    }

    private static void writeMetadata(AtomicFile af, JSONObject json) throws IOException {
        FileOutputStream fos = null;
        try {
            fos = af.startWrite();
            fos.write(json.toString().getBytes(StandardCharsets.UTF_8));
            af.finishWrite(fos);
            fos = null;
        } finally {
            if (fos != null) {
                af.failWrite(fos);
            }
        }
    }

    public static synchronized String describeSessions(File filesDir) {
        File sessionsDir = new File(filesDir, "sessions");
        File[] sessions = sessionsDir.listFiles();
        if (sessions == null) return "No sessions";
        
        StringBuilder sb = new StringBuilder();
        for (File sessionDir : sessions) {
            if (!sessionDir.isDirectory()) continue;
            File meta = new File(sessionDir, "session.json");
            if (!meta.exists()) {
                sb.append("ID: ").append(sessionDir.getName())
                  .append(" (ORPHAN, no session.json)\n");
                continue;
            }
            try {
                JSONObject json = new JSONObject(new String(new AtomicFile(meta).readFully(), StandardCharsets.UTF_8));
                sb.append("ID: ").append(json.optString("id"))
                  .append(", Status: ").append(json.optString("status"))
                  .append(", Duration: ").append(json.optLong("totalDurationMillis", 0L)).append("ms");
                
                JSONArray pending = json.optJSONArray("pending");
                if (pending != null && pending.length() > 0) {
                    sb.append(", Pending Chunks: ").append(pending.length());
                } else {
                    sb.append(", Pending Chunks: 0");
                }
                
                String error = json.optString("error", "");
                String lastError = json.optString("lastError", "");
                if (!error.isEmpty()) {
                    sb.append(", Error: ").append(error);
                }
                if (!lastError.isEmpty()) {
                    sb.append(", Last Error: ").append(lastError);
                }
                sb.append("\n");
            } catch (Exception e) {
                sb.append("Error parsing ").append(sessionDir.getName()).append(": ").append(e.getMessage()).append("\n");
            }
        }
        return sb.toString();
    }

    public static synchronized void recoverInterrupted(File filesDir) throws IOException {
        File sessionsDir = new File(filesDir, "sessions");
        File[] sessions = sessionsDir.listFiles();
        if (sessions == null) return;
        
        for (File sessionDir : sessions) {
            File meta = new File(sessionDir, "session.json");
            if (!meta.exists()) continue;
            
            AtomicFile af = new AtomicFile(meta);
            JSONObject json;
            try {
                json = new JSONObject(new String(af.readFully(), StandardCharsets.UTF_8));
            } catch (JSONException e) {
                throw new IOException("Failed to read metadata", e);
            }
            
            String status = json.optString("status");
            if ("COMPLETED".equals(status) || "RECOVERED_INTERRUPTED".equals(status) || "RECOVERY_ERROR".equals(status)) {
                continue;
            }
            
            int sampleRate = json.optInt("sampleRate", 16000);
            boolean hasError = false;
            JSONArray corruptPartials = new JSONArray();
            
            File[] parts = sessionDir.listFiles((dir, name) -> name.endsWith(".wav.part"));
            if (parts != null) {
                for (File part : parts) {
                    try {
                        WavChunkWriter.recover(part, sampleRate);
                    } catch (Exception e) {
                        hasError = true;
                        corruptPartials.put(part.getName() + ": " + e.getMessage());
                    }
                }
            }
            
            try {
                json.put("status", hasError ? "RECOVERY_ERROR" : "RECOVERED_INTERRUPTED");
                json.put("updatedUtc", System.currentTimeMillis());
                if (corruptPartials.length() > 0) {
                    json.put("corruptPartials", corruptPartials);
                    json.put("lastError", "Recovery encountered corrupt partials");
                }
                
                SessionStore store = new SessionStore(sessionDir);
                json = store.refreshChunks(json);
                
                writeMetadata(af, json);
                fsyncDirectory(sessionDir);
            } catch (JSONException e) {
                throw new IOException("Failed to update metadata during recovery", e);
            }
        }
    }

    private static void fsyncDirectory(File dir) throws IOException {
        try {
            FileDescriptor fd = Os.open(dir.getAbsolutePath(), OsConstants.O_RDONLY, 0);
            try {
                Os.fsync(fd);
            } finally {
                Os.close(fd);
            }
        } catch (Exception e) {
            throw new IOException("Failed to fsync directory: " + dir, e);
        }
    }
}
