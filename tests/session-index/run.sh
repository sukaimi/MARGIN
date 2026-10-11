#!/bin/sh
set -eu
# Run from the isolated checkout; leave synthetic temp fixtures for review.
WORK=$(mktemp -d /tmp/synthetic-index-classes.XXXXXXXX)
javac -d "$WORK" app/src/main/java/ai/codeandcraft/secondbrain/SessionIndexPaths.java tests/session-index/SessionIndexPathsTest.java
java -cp "$WORK" ai.codeandcraft.secondbrain.SessionIndexPathsTest

python3 tests/session-index/source_test.py
