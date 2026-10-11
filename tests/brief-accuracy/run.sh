#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
WORK=$(mktemp -d "/tmp/margin-brief-accuracy.XXXXXXXX")
trap 'rm -rf -- "$WORK"' EXIT
javac -d "$WORK" "$ROOT/app/src/main/java/ai/codeandcraft/secondbrain/BriefAccuracyPolicy.java" "$ROOT/tests/brief-accuracy/BriefAccuracyPolicyTest.java"
java -cp "$WORK" ai.codeandcraft.secondbrain.BriefAccuracyPolicyTest "$ROOT"
