package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Fixed technical vocabulary only; never receives session or audio data. */
final class RecorderDiagnostics {
    static final int FILE_LIMIT = 16 * 1024;
    private RecorderDiagnostics() {}

    static String stopReason(String reason) {
        if ("UI_FINISH".equals(reason) || "CANCEL_FINISH".equals(reason)
                || "NOTIFICATION_STOP".equals(reason)) return reason;
        return "OTHER_STOP";
    }

    private static boolean allowed(String event) {
        if (event == null) return false;
        switch (event) {
            case "UI_FINISH_REQUEST": case "UI_FINISH": case "CANCEL_FINISH":
            case "NOTIFICATION_STOP": case "OTHER_STOP":
            case "CANCEL_DIALOG_OPEN": case "CANCEL_DIALOG_KEEP":
            case "CANCEL_DIALOG_DISMISS": case "CANCEL_DIALOG_FINISH":
            case "CAPTURE_STARTED": case "CAPTURE_ERROR": case "CAPTURE_COMPLETED":
            case "SERVICE_DESTROY_ACTIVE": case "SERVICE_DESTROY_AFTER_COMPLETION":
            case "SERVICE_DESTROY_AFTER_ERROR": case "SERVICE_DESTROY_INACTIVE":
                return true;
            default: return false;
        }
    }

    static void log(Context context, String event) {
        try { append(context.getFilesDir(), event, System.currentTimeMillis(),
                SystemClock.elapsedRealtime()); } catch (Exception ignored) {}
    }

    // Package-private for synthetic host tests. All failures are best effort.
    static synchronized void append(File filesDir, String event, long utc, long elapsed) {
        if (!allowed(event)) return;
        try {
            File root = filesDir.getCanonicalFile();
            if (Files.isSymbolicLink(filesDir.toPath())) return;
            File dir = new File(root, "diagnostics");
            if (!safe(dir, root)) return;
            if (!dir.exists() && !dir.mkdir()) return;
            if (!dir.isDirectory() || !safe(dir, root)) return;
            File current = new File(dir, "recorder.jsonl");
            File previous = new File(dir, "recorder.previous.jsonl");
            if (!safe(current, dir) || !safe(previous, dir)) return;
            if ((current.exists() && !current.isFile())
                    || (previous.exists() && !previous.isFile())) return;
            byte[] row = ("{\"schemaVersion\":1,\"buildVersion\":58,\"event\":\"" + event
                    + "\",\"utcMillis\":" + utc + ",\"elapsedMillis\":" + elapsed + "}\n")
                    .getBytes(StandardCharsets.UTF_8);
            // Only our two journals may be removed or rotated.
            if (previous.length() > FILE_LIMIT && !previous.delete()) return;
            if (current.length() + row.length > FILE_LIMIT) {
                if (previous.exists() && !previous.delete()) return;
                if (current.length() > FILE_LIMIT) {
                    if (!current.delete()) return;
                } else if (current.exists() && !current.renameTo(previous)) return;
            }
            if (!safe(dir, root) || !safe(current, dir) || !safe(previous, dir)) return;
            try (FileOutputStream out = new FileOutputStream(current, true)) {
                out.write(row);
                out.flush();
                out.getFD().sync();
            }
        } catch (Exception ignored) {}
    }

    private static boolean safe(File file, File parent) throws java.io.IOException {
        return !Files.isSymbolicLink(file.toPath())
                && file.getCanonicalFile().equals(new File(parent, file.getName()));
    }
}
