#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
WORK=$(mktemp -d "/tmp/margin-record-entry.XXXXXXXX")
trap 'rm -rf -- "$WORK"' EXIT
trap 'exit 1' HUP INT TERM
javac -d "$WORK" "$ROOT/app/src/main/java/ai/codeandcraft/secondbrain/RecordEntryGuard.java" "$ROOT/tests/record-entry/RecordEntryGuardTest.java"
java -cp "$WORK" ai.codeandcraft.secondbrain.RecordEntryGuardTest "$ROOT"
