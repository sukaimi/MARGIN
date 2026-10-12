# Per-meeting deletion checks

Run `sh tests/meeting-delete/run.sh` with JDK 17. It compiles the production deletion and index-path helpers against synthetic temporary fixtures. Another meeting, its exports/cache, models/settings and unrelated cache bytes must remain unchanged. The selected meeting's audio, transcripts, summaries, notes, task revisions, prepared exports and recognized temporary files are removed. Repeated deletion, a fresh disk index, invalid IDs, symlinks, legacy blockers and queue-write failure are covered.

`python3 tests/meeting-delete/queue_host.py` requires `JSON_JAR` pointing to org.json:json:20240303. It compiles the actual automatic-transcription queue and recording-start gate with explicit Android/engine host doubles. This is not device lifecycle, recording or model-inference verification.

The UI path is Meeting → More → Delete meeting → Delete permanently. Cancel does not call deletion. Source contracts check confirmation and cancellation wiring, active-work guards, sequential worker draining, export ownership and lifecycle completion; real Android UI checks are separate.

New prepared files are tagged with the owning meeting UUID. Older untagged prepared exports or recognized temporary artifacts cannot safely be assigned to one meeting, so per-item deletion fails before mutation. The existing Delete all meetings action can remove that legacy set, but it removes every meeting; do not use it when other meetings should be retained. Externally saved copies are never deleted.

Deletion is logical and non-atomic. An I/O failure can leave selected-meeting data; retry after reopening. Full recording → native transcript → native summary acceptance remains field testing.
