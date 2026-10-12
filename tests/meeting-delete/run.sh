#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
WORK=$(mktemp -d /tmp/margin-meeting-delete.XXXXXXXX)
trap 'rm -rf "$WORK"' EXIT HUP INT TERM
javac --release 17 -d "$WORK/classes" "$ROOT/app/src/main/java/ai/codeandcraft/secondbrain/CleanSlate.java" "$ROOT/app/src/main/java/ai/codeandcraft/secondbrain/SessionIndexPaths.java" "$ROOT/tests/meeting-delete/MeetingDeleteTest.java"
java -cp "$WORK/classes" ai.codeandcraft.secondbrain.MeetingDeleteTest "$WORK/data"
python3 "$ROOT/tests/meeting-delete/source_test.py"
