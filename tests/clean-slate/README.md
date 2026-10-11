# Clean-slate deletion checks

With JDK 17 available, run `sh tests/clean-slate/run.sh` from any directory.

The production filesystem helper runs against synthetic temporary files. Checks cover all owned meeting-data locations, repeated deletion, unchanged model/settings bytes, linked model and outside targets, broken links, aliased Android-style storage roots, preflight failure before deletion, and bounded recursion. Static source contracts check explicit confirmation, process-wide Main work ordering, detail-worker exclusion, active recorder/transcription gates and Activity recreation handling.

These are JVM/source checks. They do not perform Android/device deletion. Recording → native transcript → native summary acceptance remains field testing.
