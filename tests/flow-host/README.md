# Host storage flow regression

Run with JDK 17 or newer and `org.json:json:20240303` from Maven Central:

    JSON_JAR=/absolute/path/json-20240303.jar python3 tests/flow-host/run.py

Verified jar SHA-256: `3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`.

The runner compiles the production SessionStore, WavChunkWriter, PcmWavReader,
TranscriptTruthStore, EvidenceExcerptBaseline, MeetingBriefEngine,
MeetingBriefStore and AsrConfiguration. Three fresh JVM processes write, reopen
and tamper-check disposable synthetic data. It exercises real WAV construction,
completion, transcript/audio copy and hashes, extractive draft generation,
citations, revision chains, cancellation, stale-parent rejection, recording and
overlap gates. No user's recordings are used.

Important boundaries: synthesized PCM is not microphone input. Native ASR is a
supplied transcript fixture through a WindowedTranscription test double. Android
Context/AtomicFile and directory fsync have host shims. SourceBundle.sha uses a
host checksum shim. No Android runtime, UI lifecycle, actual Whisper decoding,
Smol model inference, Android filesystem durability, physical sound or endurance
is certified. A passed test must not be described as full device end-to-end.
