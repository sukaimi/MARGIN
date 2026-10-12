#!/usr/bin/env python3
"""Run production queue/gate classes on JDK 17 with minimal host doubles.

Usage: JSON_JAR=/path/to/json-20240303.jar python3 tests/meeting-delete/queue_host.py
Android AtomicFile backup recovery / .new replacement are modeled with host files.
Directory fsync and Android service/engine APIs are doubles: this is not device,
power-loss, actual Android AtomicFile, or native transcription validation.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
jar_value = os.environ.get('JSON_JAR')
if not jar_value:
    raise SystemExit('Set JSON_JAR to the local org.json:json:20240303 jar.')
jar = Path(jar_value).expanduser().resolve()
if not jar.is_file():
    raise SystemExit(f'JSON_JAR is not a file: {jar}')
for command in ('java', 'javac'):
    if not shutil.which(command):
        raise SystemExit(f'{command} unavailable; put JDK 17 on PATH.')

stubs = {
    'android/content/Context.java': '''package android.content;
import java.io.File;
public class Context {
    private final File root;
    public int foregroundStarts;
    public Context(File root) { this.root = root; }
    public File getFilesDir() { return root; }
    public android.content.res.AssetManager getAssets() { return new android.content.res.AssetManager(); }
    public void startForegroundService(Intent intent) { foregroundStarts++; }
}''',
    'android/content/Intent.java': '''package android.content;
public class Intent { public Intent(Context context, Class<?> service) {} }''',
    'android/content/res/AssetManager.java': '''package android.content.res;
public class AssetManager {
    public java.io.InputStream open(String name) throws java.io.IOException {
        throw new java.io.IOException("Unexpected host engine asset read");
    }
}''',
    'android/system/OsConstants.java': '''package android.system;
public class OsConstants { public static final int O_RDONLY = 0; }''',
    'android/system/Os.java': '''package android.system;
import java.io.FileDescriptor;
public class Os {
    public static FileDescriptor open(String path, int flags, int mode) { return new FileDescriptor(); }
    public static void fsync(FileDescriptor fd) {}
    public static void close(FileDescriptor fd) {}
}''',
    'android/util/AtomicFile.java': '''package android.util;
import java.io.*;
import java.nio.file.*;
/** Host model of modern Android legacy .bak recovery and .new replacement. */
public class AtomicFile {
    private final File base, backup, fresh;
    public AtomicFile(File file) {
        base = file; backup = new File(file + ".bak"); fresh = new File(file + ".new");
    }
    private void restoreBackup() throws IOException {
        if (backup.exists()) Files.move(backup.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    public byte[] readFully() throws IOException {
        restoreBackup();
        if (fresh.exists() && base.exists()) Files.delete(fresh.toPath());
        return Files.readAllBytes(base.toPath());
    }
    public FileOutputStream startWrite() throws IOException {
        restoreBackup();
        Files.createDirectories(base.toPath().getParent());
        return new FileOutputStream(fresh);
    }
    public void finishWrite(FileOutputStream stream) throws IOException {
        stream.getFD().sync(); stream.close();
        Files.move(fresh.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    public void failWrite(FileOutputStream stream) throws IOException {
        stream.close(); Files.deleteIfExists(fresh.toPath());
    }
}''',
    'ai/codeandcraft/secondbrain/RecorderService.java': '''package ai.codeandcraft.secondbrain;
public class RecorderService {
    public static boolean recording;
    public static String status = "IDLE";
    public static boolean isRecording() { return recording; }
    public static String getStatus() { return status; }
}''',
    'ai/codeandcraft/secondbrain/PopUi.java': '''package ai.codeandcraft.secondbrain;
public class PopUi { public static boolean capturePending; }''',
    'ai/codeandcraft/secondbrain/AutoTranscriptionService.java': '''package ai.codeandcraft.secondbrain;
public class AutoTranscriptionService {}''',
    'ai/codeandcraft/secondbrain/AsrConfiguration.java': '''package ai.codeandcraft.secondbrain;
public class AsrConfiguration { public static String[] assetPaths() { return new String[0]; } }''',
    'ai/codeandcraft/secondbrain/WindowedTranscription.java': '''package ai.codeandcraft.secondbrain;
import android.content.Context;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
public class WindowedTranscription {
    public static JSONObject run(Context c, File m, int limit, AtomicBoolean pause) {
        throw new AssertionError("Native engine must not run in host test");
    }
    public static JSONObject inspectJob(Context c, File m, String id) {
        throw new AssertionError("Native engine must not run in host test");
    }
}''',
}

with tempfile.TemporaryDirectory(prefix='margin-meeting-queue-') as directory:
    work = Path(directory)
    for name, body in stubs.items():
        path = work / 'stubs' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
    production = ROOT / 'app/src/main/java/ai/codeandcraft/secondbrain'
    classes = work / 'classes'
    classes.mkdir()
    sources = [work / 'stubs' / name for name in stubs]
    sources += [production / 'AutoTranscription.java', production / 'RecordingStartGate.java',
                ROOT / 'tests/meeting-delete/MeetingQueueHostTest.java']
    subprocess.run(['javac', '--release', '17', '-cp', str(jar), '-d', str(classes),
                    *map(str, sources)], check=True)
    subprocess.run(['java', '-ea', '-cp', str(classes) + os.pathsep + str(jar),
                    'ai.codeandcraft.secondbrain.MeetingQueueHostTest', str(work / 'data')], check=True)
