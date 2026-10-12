package ai.codeandcraft.secondbrain;

import java.nio.file.*;
import java.util.*;

public final class RecorderDiagnosticsTest {
    static int checks;
    static void check(boolean ok, String message) {
        checks++; if (!ok) throw new AssertionError(message);
    }
    static void log(Path root, String event) {
        RecorderDiagnostics.append(root.toFile(), event, 123456789L, 98765L);
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path normal = Files.createDirectory(root.resolve("normal"));
        Path journal = normal.resolve("diagnostics/recorder.jsonl");
        String[] events = {"UI_FINISH_REQUEST", "UI_FINISH", "CANCEL_FINISH", "NOTIFICATION_STOP", "OTHER_STOP",
            "CANCEL_DIALOG_OPEN", "CANCEL_DIALOG_KEEP", "CANCEL_DIALOG_DISMISS", "CANCEL_DIALOG_FINISH",
            "CAPTURE_STARTED", "CAPTURE_ERROR", "CAPTURE_COMPLETED", "SERVICE_DESTROY_ACTIVE",
            "SERVICE_DESTROY_AFTER_COMPLETION", "SERVICE_DESTROY_AFTER_ERROR", "SERVICE_DESTROY_INACTIVE"};
        for (String event : events) log(normal, event);
        String before = Files.readString(journal);
        check(before.lines().count() == events.length, "Every allowed event appended");
        for (String event : events) check(before.contains("\"event\":\"" + event + "\""), "Missing event");
        for (String invalid : new String[]{null, "", "session-secret", "UI_FINISH\nsecret", "CAPTURE_ERROR:private"})
            log(normal, invalid);
        check(before.equals(Files.readString(journal)), "Arbitrary messages rejected without writes");
        check("OTHER_STOP".equals(RecorderDiagnostics.stopReason(null)), "Null origin normalized");
        check("OTHER_STOP".equals(RecorderDiagnostics.stopReason("private")), "Unknown origin normalized");
        for (String origin : new String[]{"UI_FINISH", "CANCEL_FINISH", "NOTIFICATION_STOP"})
            check(origin.equals(RecorderDiagnostics.stopReason(origin)), "Known origin retained");
        Path sentinel = normal.resolve("diagnostics/unrelated.jsonl");
        Files.writeString(sentinel, "synthetic-owned-sentinel");
        for (int i = 0; i < 1200; i++) {
            log(normal, events[i % events.length]);
            check(Files.size(journal) <= RecorderDiagnostics.FILE_LIMIT, "Current bounded after each append");
            Path previous = normal.resolve("diagnostics/recorder.previous.jsonl");
            check(!Files.exists(previous) || Files.size(previous) <= RecorderDiagnostics.FILE_LIMIT, "Previous bounded");
        }
        check(Files.exists(normal.resolve("diagnostics/recorder.previous.jsonl")), "Rotation occurred");
        check(Files.readString(sentinel).equals("synthetic-owned-sentinel"), "Only own journals rotated");
        for (String filename : new String[]{"recorder.jsonl", "recorder.previous.jsonl"}) {
            Path oversized = Files.createDirectory(root.resolve("oversized-" + filename));
            Files.createDirectory(oversized.resolve("diagnostics"));
            Files.writeString(oversized.resolve("diagnostics/" + filename), "x".repeat(40000));
            log(oversized, "UI_FINISH");
            check(Files.size(oversized.resolve("diagnostics/recorder.jsonl")) <= RecorderDiagnostics.FILE_LIMIT, "Oversize current repaired");
            Path previous = oversized.resolve("diagnostics/recorder.previous.jsonl");
            check(!Files.exists(previous) || Files.size(previous) <= RecorderDiagnostics.FILE_LIMIT, "Oversize previous repaired");
        }
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path target = outside.resolve("target"); Files.writeString(target, "synthetic-private-sentinel");
        for (String link : new String[]{"diagnostics", "recorder.jsonl", "recorder.previous.jsonl"}) {
            Path fixture = Files.createDirectory(root.resolve("link-" + link));
            if (link.equals("diagnostics")) Files.createSymbolicLink(fixture.resolve(link), outside);
            else {
                Files.createDirectory(fixture.resolve("diagnostics"));
                Files.createSymbolicLink(fixture.resolve("diagnostics/" + link), target);
            }
            log(fixture, "UI_FINISH");
            check(Files.readString(target).equals("synthetic-private-sentinel"), "Symlink target untouched");
            if (!link.equals("recorder.jsonl")) check(!Files.exists(fixture.resolve("diagnostics/recorder.jsonl")), "Symlink prevents append");
        }
        Path rootLink = root.resolve("root-link"); Files.createSymbolicLink(rootLink, outside);
        log(rootLink, "UI_FINISH");
        check(!Files.exists(outside.resolve("diagnostics")), "Symlink root rejected");
        Path blocked = Files.createDirectory(root.resolve("blocked"));
        Files.writeString(blocked.resolve("diagnostics"), "owned-blocker");
        log(blocked, "UI_FINISH");
        check(Files.readString(blocked.resolve("diagnostics")).equals("owned-blocker"), "Directory IO failure nonthrow");
        Path directoryJournal = Files.createDirectory(root.resolve("directory-journal"));
        Files.createDirectories(directoryJournal.resolve("diagnostics/recorder.jsonl"));
        log(directoryJournal, "UI_FINISH");
        check(Files.isDirectory(directoryJournal.resolve("diagnostics/recorder.jsonl")), "Journal IO failure nonthrow");
        RecorderDiagnostics.append(null, "UI_FINISH", 0, 0);
        RecorderDiagnostics.log(null, "UI_FINISH");
        check(true, "Null root/context failures nonthrow");
        System.out.println("PASS: " + checks + " actual logger checks");
    }
}
