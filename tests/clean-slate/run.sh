#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
WORK=$(mktemp -d /tmp/margin-clean-slate.XXXXXXXX)
trap 'rm -rf "$WORK"' EXIT HUP INT TERM
javac --release 17 -d "$WORK/classes" "$ROOT/app/src/main/java/ai/codeandcraft/secondbrain/CleanSlate.java" "$ROOT/tests/clean-slate/CleanSlateTest.java"
java -cp "$WORK/classes" ai.codeandcraft.secondbrain.CleanSlateTest "$WORK/data"
python3 "$ROOT/tests/clean-slate/source_test.py"
