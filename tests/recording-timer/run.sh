#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
WORK=$(mktemp -d /tmp/build58-rotation.XXXXXXXX)
trap 'rm -rf -- "$WORK"' EXIT
javac -d "$WORK" "$ROOT/app/src/main/java/ai/codeandcraft/secondbrain/RecordingStartGate.java" "$ROOT/app/src/main/java/ai/codeandcraft/secondbrain/RecordEntryGuard.java" "$ROOT/tests/recording-timer/RecordingStartGateTest.java"
java -cp "$WORK" ai.codeandcraft.secondbrain.RecordingStartGateTest
python3 "$ROOT/tests/recording-timer/source_test.py"
