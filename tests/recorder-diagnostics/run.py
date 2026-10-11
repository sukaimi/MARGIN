"""Owned synthetic fixtures only; no Android build, dependencies, or device access."""
from pathlib import Path
import json
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix="build58-diagnostics-") as work:
    work = Path(work)
    context = work / "android/content/Context.java"
    clock = work / "android/os/SystemClock.java"
    context.parent.mkdir(parents=True)
    clock.parent.mkdir(parents=True)
    context.write_text("package android.content; public abstract class Context { public abstract java.io.File getFilesDir(); }")
    clock.write_text("package android.os; public final class SystemClock { public static long elapsedRealtime() { return 98765L; } }")
    classes = work / "classes"
    fixtures = work / "fixtures"
    fixtures.mkdir()
    subprocess.run(["javac", "-d", str(classes), str(context), str(clock),
        str(ROOT / "app/src/main/java/ai/codeandcraft/secondbrain/RecorderDiagnostics.java"),
        str(Path(__file__).with_name("RecorderDiagnosticsTest.java"))], check=True)
    subprocess.run(["java", "-cp", str(classes), "ai.codeandcraft.secondbrain.RecorderDiagnosticsTest", str(fixtures)], check=True)
    rows = 0
    for name in ("recorder.jsonl", "recorder.previous.jsonl"):
        for line in (fixtures / "normal/diagnostics" / name).read_text().splitlines():
            record = json.loads(line)
            assert set(record) == {"schemaVersion", "buildVersion", "event", "utcMillis", "elapsedMillis"}
            assert record["schemaVersion"] == 1 and record["buildVersion"] == 58
            assert record["utcMillis"] == 123456789 and record["elapsedMillis"] == 98765
            assert "secret" not in line and "sentinel" not in line
            rows += 1
    print(f"PASS: {rows} retained rows parsed with exact five-field privacy schema")
