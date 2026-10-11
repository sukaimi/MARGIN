#!/bin/sh
set -eu
# Supervisor runs from checkout. Synthetic classes retained for review; no downloads.
WORK=$(mktemp -d /tmp/jotterbook-sound-classes.XXXXXXXX)
javac -d "$WORK" app/src/main/java/ai/codeandcraft/secondbrain/UiSoundGate.java tests/ui-sound/UiSoundGateTest.java
java -cp "$WORK" ai.codeandcraft.secondbrain.UiSoundGateTest
python3 tests/ui-sound/source_test.py
