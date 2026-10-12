package ai.codeandcraft.secondbrain;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Executes production queue/gate methods. Android and native APIs remain host doubles. */
public final class MeetingQueueHostTest {
    private static final String SELECTED = "12345678-1234-1234-1234-123456789abc";
    private static final String FIRST = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String LAST = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ABSENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static int checks, fixtures;
    private static Path root;

    @FunctionalInterface interface Action { void run() throws Exception; }
    private static void check(boolean good, String message) {
        checks++;
        if (!good) throw new AssertionError(message);
    }
    private static void rejected(Action action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) { check(true, message); return; }
        throw new AssertionError("Expected IOException: " + message);
    }
    private static Context fixture() throws Exception {
        return new Context(Files.createDirectories(root.resolve("fixture-" + (++fixtures))).toFile());
    }
    private static Path index(Context context) {
        return context.getFilesDir().toPath().resolve("auto-transcription-index.json");
    }
    private static JSONObject record(String... ids) throws Exception {
        return new JSONObject().put("ids", new JSONArray(Arrays.asList(ids)))
                .put("version", 7).put("unknown", new JSONObject().put("keep", new JSONArray("[true,null,17,\"extra\"]")))
                .put("description", "retain extra JSON fields");
    }
    private static void put(Context context, JSONObject value) throws Exception {
        Files.writeString(index(context), value.toString());
    }
    private static JSONObject get(Context context) throws Exception {
        return new JSONObject(Files.readString(index(context)));
    }
    private static void unchanged(Path file, byte[] before, String message) throws Exception {
        check(Arrays.equals(before, Files.readAllBytes(file)), message);
    }
    private static void withCleanSlate(Action action) throws Exception {
        check(AutoTranscription.beginCleanSlate(), "Idle queue obtains clean-slate gate");
        try { action.run(); } finally { AutoTranscription.endCleanSlate(); }
    }

    private static void preserveOtherMeetings() throws Exception {
        Context context = fixture();
        JSONObject original = record(FIRST, SELECTED, LAST, SELECTED, FIRST);
        put(context, original);
        Path unrelated = context.getFilesDir().toPath().resolve("sessions").resolve(FIRST).resolve("notes.txt");
        Files.createDirectories(unrelated.getParent());
        Files.writeString(unrelated, "unrelated meeting bytes");
        byte[] bytes = Files.readAllBytes(unrelated);
        withCleanSlate(() -> {
            AutoTranscription.removeDeletedMeeting(context, SELECTED);
            JSONObject expected = record(FIRST, LAST, FIRST);
            check(get(context).similar(expected), "Remove every selected-ID occurrence; preserve other IDs, duplicates, order and all extra JSON");
            byte[] once = Files.readAllBytes(index(context));
            AutoTranscription.removeDeletedMeeting(context, SELECTED);
            unchanged(index(context), once, "Repeated removal is idempotent");
            AutoTranscription.removeDeletedMeeting(context, ABSENT);
            unchanged(index(context), once, "Missing selected ID leaves serialized index unchanged");
            unchanged(unrelated, bytes, "Unrelated meeting file preserved");
            check(!Files.exists(Path.of(index(context) + ".new")), "Successful replacement leaves no .new");
        });
        Context absent = fixture();
        withCleanSlate(() -> {
            AutoTranscription.removeDeletedMeeting(absent, SELECTED);
            AutoTranscription.removeDeletedMeeting(absent, SELECTED);
            check(!Files.exists(index(absent)), "No index remains absent after repeated deletion");
        });
        Context only = fixture(); put(only, record(SELECTED));
        withCleanSlate(() -> {
            AutoTranscription.removeDeletedMeeting(only, SELECTED);
            check(get(only).similar(record()), "Last ID removal preserves an empty ids array and extra JSON");
        });
    }

    private static void gateRules() throws Exception {
        Context context = fixture(); put(context, record(SELECTED, FIRST));
        byte[] before = Files.readAllBytes(index(context));
        rejected(() -> AutoTranscription.removeDeletedMeeting(context, SELECTED), "Deletion without gate");
        unchanged(index(context), before, "Unauthorized deletion preserves queue");
        withCleanSlate(() -> {
            check(AutoTranscription.cleanSlateRequested(), "Exclusive operation is visible");
            check(AutoTranscription.blocked(), "Queue reports blocked during deletion");
            check(!AutoTranscription.beginCleanSlate(), "Second clean-slate operation is rejected");
            check(!AutoTranscription.eligible(context), "Eligibility is suppressed while clean-slate is held");
            check(!AutoTranscription.step(context, (c, m, p) -> { throw new AssertionError("Runner entered during clean slate"); }), "Worker cannot begin while clean-slate is held");
            AutoTranscription.kick(context);
            check(context.foregroundStarts == 0, "No service launch while clean-slate is held");
            // Inject an otherwise unreachable combination to test the defensive idle-worker guard.
            Field working = AutoTranscription.class.getDeclaredField("working");
            working.setAccessible(true);
            synchronized (AutoTranscription.class) { working.setBoolean(null, true); }
            try {
                rejected(() -> AutoTranscription.removeDeletedMeeting(context, SELECTED), "Deletion requires idle worker even with clean-slate held");
                unchanged(index(context), before, "Busy-worker guard preserves queue");
            } finally { synchronized (AutoTranscription.class) { working.setBoolean(null, false); } }
        });
        check(!AutoTranscription.cleanSlateRequested(), "Release clears exclusive operation");
        rejected(() -> AutoTranscription.removeDeletedMeeting(context, SELECTED), "Released gate no longer permits deletion");
        AutoTranscription.requestCapture();
        check(!AutoTranscription.beginCleanSlate(), "Requested capture blocks deletion gate");
        AutoTranscription.releaseCapture();
        PopUi.capturePending = true;
        check(!AutoTranscription.beginCleanSlate(), "Pending UI capture blocks deletion gate");
        PopUi.capturePending = false;
        RecorderService.recording = true;
        check(!AutoTranscription.beginCleanSlate(), "Active recording blocks deletion gate");
        RecorderService.recording = false;
        for (String status : new String[]{"STARTING", "RECORDING"}) {
            RecorderService.status = status;
            check(!AutoTranscription.beginCleanSlate(), "Recorder status " + status + " blocks deletion gate");
        }
        RecorderService.status = "IDLE";
        long request = RecordingStartGate.INSTANCE.begin(1234);
        check(request != 0, "Production start gate obtains recording ownership");
        check(!AutoTranscription.beginCleanSlate(), "Pending production recording owner blocks deletion gate");
        check(RecordingStartGate.INSTANCE.claim(request), "Production recording request dispatches");
        check(!AutoTranscription.beginCleanSlate(), "Dispatched production recording owner blocks deletion gate");
        RecordingStartGate.INSTANCE.serviceReleased();
        withCleanSlate(() -> check(true, "Released recording owner allows deletion"));

        CyclicBarrier barrier = new CyclicBarrier(3);
        AtomicInteger winners = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Runnable contender = () -> {
            try { barrier.await(5, TimeUnit.SECONDS); if (AutoTranscription.beginCleanSlate()) winners.incrementAndGet(); }
            catch (Throwable failure) { error.compareAndSet(null, failure); }
        };
        Thread a = new Thread(contender, "clean-slate-a"), b = new Thread(contender, "clean-slate-b");
        a.start(); b.start(); barrier.await(5, TimeUnit.SECONDS); a.join(5000); b.join(5000);
        check(!a.isAlive() && !b.isAlive() && error.get() == null, "Concurrent contenders terminate cleanly");
        check(winners.get() == 1, "Exactly one concurrent deletion gate owner");
        AutoTranscription.endCleanSlate();
    }

    private static void activeWorkerBlocksGate() throws Exception {
        Context context = fixture();
        Path meeting = context.getFilesDir().toPath().resolve("sessions").resolve(SELECTED);
        Files.createDirectories(meeting);
        Files.writeString(meeting.resolve("session.json"), "{\"status\":\"COMPLETED\",\"chunks\":[{\"filename\":\"chunk-0.wav\"}]}");
        Files.write(meeting.resolve("chunk-0.wav"), new byte[]{1, 2, 3});
        Files.writeString(meeting.resolve("auto-transcription.json"), "{\"status\":\"QUEUED\",\"automatic\":true}");
        put(context, record(SELECTED));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean stepped = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                stepped.set(AutoTranscription.step(context, (c, m, pause) -> {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Host runner release timeout");
                    return new JSONObject().put("status", "COMPLETED").put("jobId", "host-job");
                }));
            } catch (Throwable failure) { error.set(failure); }
        }, "production-queue-worker");
        worker.start();
        try {
            check(entered.await(5, TimeUnit.SECONDS), "Production step entered injected runner");
            check(!AutoTranscription.quiescent(), "Running worker reports busy");
            check(!AutoTranscription.beginCleanSlate(), "Running production worker blocks clean-slate acquisition");
            byte[] before = Files.readAllBytes(index(context));
            rejected(() -> AutoTranscription.removeDeletedMeeting(context, SELECTED), "Running queue cannot delete meeting");
            unchanged(index(context), before, "Running queue index preserved");
        } finally { release.countDown(); worker.join(5000); }
        check(!worker.isAlive() && error.get() == null && stepped.get(), "Production worker completes cleanly");
        check(AutoTranscription.quiescent(), "Worker returns to idle");
        withCleanSlate(() -> AutoTranscription.removeDeletedMeeting(context, SELECTED));
    }

    private static void invalidInputs() throws Exception {
        Context context = fixture(); put(context, record(SELECTED, FIRST));
        byte[] before = Files.readAllBytes(index(context));
        withCleanSlate(() -> {
            for (String id : new String[]{null, "", "../outside", "1-1-1-1-1", SELECTED.toUpperCase(Locale.ROOT), SELECTED + "/file", "not-a-uuid"}) {
                rejected(() -> AutoTranscription.removeDeletedMeeting(context, id), "Malformed selected ID rejected: " + id);
                unchanged(index(context), before, "Malformed ID preserves index");
            }
        });
        for (String malformed : new String[]{"{", "[]", "{}", "{\"ids\":null}", "{\"ids\":\"wrong\"}", "{\"ids\":[17]}", "{\"ids\":[null]}"}) {
            Context bad = fixture(); Files.writeString(index(bad), malformed);
            byte[] bytes = Files.readAllBytes(index(bad));
            withCleanSlate(() -> {
                rejected(() -> AutoTranscription.removeDeletedMeeting(bad, SELECTED), "Malformed index rejected: " + malformed);
                unchanged(index(bad), bytes, "Malformed index preserved");
            });
        }
        Context many = fixture(); JSONArray ids = new JSONArray();
        for (int i = 0; i <= AutoTranscription.LIMIT; i++) ids.put(FIRST);
        put(many, new JSONObject().put("ids", ids));
        byte[] manyBytes = Files.readAllBytes(index(many));
        withCleanSlate(() -> {
            rejected(() -> AutoTranscription.removeDeletedMeeting(many, SELECTED), "Oversize ID count rejected");
            unchanged(index(many), manyBytes, "Oversize ID count preserved");
        });
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            Context oversized = fixture(); put(oversized, record(SELECTED));
            Path target = Path.of(index(oversized) + suffix);
            Files.write(target, new byte[262145]);
            byte[] indexBytes = Files.readAllBytes(index(oversized)), targetBytes = Files.readAllBytes(target);
            withCleanSlate(() -> {
                rejected(() -> AutoTranscription.removeDeletedMeeting(oversized, SELECTED), "Oversized queue path rejected: " + suffix);
                unchanged(index(oversized), indexBytes, "Oversized path preserves index");
                unchanged(target, targetBytes, "Oversized path preserves file");
            });
        }
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            Context directory = fixture();
            if (!suffix.isEmpty()) put(directory, record(SELECTED));
            Path target = Path.of(index(directory) + suffix); Files.createDirectory(target);
            withCleanSlate(() -> {
                rejected(() -> AutoTranscription.removeDeletedMeeting(directory, SELECTED), "Directory queue path rejected: " + suffix);
                check(Files.isDirectory(target), "Rejected directory remains intact");
            });
        }
    }

    private static void linkedPaths() throws Exception {
        Path outside = root.resolve("outside.json");
        Files.writeString(outside, record(SELECTED, FIRST).toString());
        byte[] outsideBytes = Files.readAllBytes(outside);
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            for (boolean dangling : new boolean[]{false, true}) {
                Context context = fixture();
                if (!suffix.isEmpty()) put(context, record(SELECTED, FIRST));
                Path link = Path.of(index(context) + suffix);
                Path target = dangling ? root.resolve("nonexistent-outside") : outside;
                Files.createSymbolicLink(link, target);
                byte[] before = suffix.isEmpty() ? null : Files.readAllBytes(index(context));
                withCleanSlate(() -> {
                    rejected(() -> AutoTranscription.removeDeletedMeeting(context, SELECTED), "Linked queue path rejected: " + suffix + " dangling=" + dangling);
                    check(Files.isSymbolicLink(link), "Rejected link remains unchanged");
                    unchanged(outside, outsideBytes, "Outside file preserved after linked path rejection");
                    if (before != null) unchanged(index(context), before, "Sidecar preflight preserves base index");
                    if (dangling) check(!Files.exists(target), "Dangling link target not created");
                });
            }
        }
    }

    private static void atomicFileModel() throws Exception {
        Context context = fixture();
        put(context, record(SELECTED, LAST));
        Files.writeString(Path.of(index(context) + ".bak"), record(FIRST, SELECTED).toString());
        Files.writeString(Path.of(index(context) + ".new"), "interrupted write");
        withCleanSlate(() -> {
            AutoTranscription.removeDeletedMeeting(context, SELECTED);
            check(get(context).similar(record(FIRST)), "Host AtomicFile restores legacy backup before selected-ID rewrite");
            check(!Files.exists(Path.of(index(context) + ".bak")), "Restored backup consumed");
            check(!Files.exists(Path.of(index(context) + ".new")), "Interrupted .new replaced/removed");
        });
        Context backupOnly = fixture();
        Path backup = Path.of(index(backupOnly) + ".bak");
        Files.writeString(backup, record(FIRST, SELECTED, LAST).toString());
        withCleanSlate(() -> {
            AutoTranscription.removeDeletedMeeting(backupOnly, SELECTED);
            check(get(backupOnly).similar(record(FIRST, LAST)), "Valid backup-only index recovers, preserving other IDs and metadata");
            check(!Files.exists(backup), "Recovered backup-only file consumed after successful commit");
        });
        Context badBackup = fixture(); put(badBackup, record(SELECTED, FIRST));
        Path badBackupPath = Path.of(index(badBackup) + ".bak");
        Files.writeString(badBackupPath, "{malformed backup");
        byte[] badBaseBytes = Files.readAllBytes(index(badBackup)), badBackupBytes = Files.readAllBytes(badBackupPath);
        withCleanSlate(() -> {
            rejected(() -> AutoTranscription.removeDeletedMeeting(badBackup, SELECTED), "Malformed authoritative backup fails closed");
            unchanged(index(badBackup), badBaseBytes, "Malformed backup preserves base before any write");
            unchanged(badBackupPath, badBackupBytes, "Malformed backup itself preserved");
        });
        // A .new-only write is uncommitted and cannot be safely selected as authoritative.
        Context onlyFresh = fixture(); Path fresh = Path.of(index(onlyFresh) + ".new");
        Files.writeString(fresh, record(SELECTED, FIRST).toString()); byte[] freshBytes = Files.readAllBytes(fresh);
        withCleanSlate(() -> {
            rejected(() -> AutoTranscription.removeDeletedMeeting(onlyFresh, SELECTED), "Uncommitted .new-only index fails closed");
            check(!Files.exists(index(onlyFresh)), "Uncommitted .new does not synthesize a base index");
            unchanged(fresh, freshBytes, "Uncommitted .new preserved");
        });
        Context fail = fixture(); put(fail, record(FIRST)); byte[] before = Files.readAllBytes(index(fail));
        AtomicFile atomic = new AtomicFile(index(fail).toFile());
        FileOutputStream stream = atomic.startWrite(); stream.write("partial".getBytes(java.nio.charset.StandardCharsets.UTF_8)); atomic.failWrite(stream);
        unchanged(index(fail), before, "Host AtomicFile failed write preserves old base");
        check(!Files.exists(Path.of(index(fail) + ".new")), "Host AtomicFile failed write removes fresh file");
    }

    public static void main(String[] args) throws Exception {
        root = Files.createDirectories(Path.of(args[0]));
        preserveOtherMeetings(); gateRules(); activeWorkerBlocksGate(); invalidInputs(); linkedPaths(); atomicFileModel();
        System.out.println("PASS: " + checks + " production meeting queue / recording gate host checks");
        System.out.println("HOST ONLY: Android service/engine and directory fsync are doubles; AtomicFile backup/.new behavior is modeled, not device or crash-durability proof.");
    }
}
