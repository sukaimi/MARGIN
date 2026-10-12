# Phase 1 handoff

Current source: **68 / 1.0-pilot68**. MARGIN is the repository; JotterBook is the app name. This remains an experimental, local-first Android prototype.

## Closeout status

- Existing field-device recordings were cleared with the all-meetings reset on 12 October 2026. The empty recordings/tasks state persisted after relaunch.
- Per-meeting deletion is implemented, reviewed, host-tested and compiled in Build 68.
- The current source and handoff are tracked in [PR #1](https://github.com/sukaimi/MARGIN/pull/1). Main has not been merged.
- Build 68 installation on the phone is pending. Full recording, native transcription/summary and endurance acceptance remain field work.

## Delete one meeting

Open a meeting, then **More → Delete meeting → Delete permanently**. Cancel leaves the meeting unchanged.

Deletion targets that meeting's recording, transcripts, summaries, notes, tasks, automatic-transcription queue entry and session-owned prepared export/prompt files. Other meetings, their queue entries and derivatives, models, settings and externally saved copies stay.

New private derivatives use an exact meeting UUID plus a separate request UUID. Recognized legacy unowned exports or temporary files block per-meeting deletion before mutation because ownership cannot be proven. The error explains that the all-meetings reset can clear the legacy set and also deletes every other meeting. Do not use it when other meetings must be kept.

The action reserves the recording/transcription gate, blocks new meeting work, drains the relevant workers and validates its paths before deletion. It rejects invalid IDs, symlinks and path escape. Queue updates preserve other IDs and metadata, including safe backup recovery. Target meeting metadata is removed last so ordinary partial failures can still be retried, including when audio loading fails.

Deletion is logical filesystem removal and is non-atomic. An I/O failure can leave some selected-meeting data; the app reports the failure. This is not a forensic secure-erase or crash-durability guarantee.

## Delete all meetings

Open **About → Delete all meetings → Delete permanently**.

This removes app-private meeting data, prepared exports, the automatic-transcription index and recognized temporary export/prompt files. Models, model-verification cache, settings and copies already saved outside the app remain outside its scope. Recording, transcription and open or retiring meeting work block the action.

## Verification

Final Build 68 source passed:

- 162 per-meeting filesystem/isolation checks
- 27 per-meeting confirmation, lifecycle, queue and ownership source contracts
- 152 checks against the production automatic-transcription queue and recording-start gate
- The existing 33 all-delete filesystem checks and 20 source contracts
- Existing entry, recording-start/timer, UI-sound, session-index, recorder-diagnostics, summary-tripwire, storage-flow and notebook-UI host suites

Fixtures are synthetic. Android services, engine calls, AtomicFile recovery and directory fsync in the queue harness are explicit host doubles or models. These checks do not establish Android lifecycle or crash-durability behavior. No microphone capture or model inference was run in the host suites. See [per-meeting tests](tests/meeting-delete/README.md) and [BUILD.md](BUILD.md) for commands.

The final Android release assembly passed with JDK 17, Gradle 8.13 and Android SDK/build-tools 35, including release lint, resource linking, Java compilation, DEX and packaging. The final incremental build completed 45 tasks, with 8 executed and 37 up-to-date, after a successful 45-task full build. Version/package, ZIP integrity/alignment and preserved model/native payloads were checked. The cloud APK is unsigned and cannot be installed as-is; installation requires the authorized matching signing key.

The public tree contains source, tests, notices and reviewed UI assets. Model weights, AAR/native/APK binaries, credentials, signing material, local configuration and private recordings/transcripts are excluded. No GitHub Actions workflow is configured; the above checks were run in the cloud build environment.

## Device evidence

On 12 October 2026, the Build 67 all-meetings reset completed on the field device. After relaunch, the app showed zero recordings and zero tasks and confirmed that models/settings were kept. This was a UI-level cleanup check; individual retained model files were not hash-verified. No new recording was created for the check.

Build 68 installation remains pending. The per-meeting flow has not been exercised on the phone; no sample meeting was added to the clean device.

## Deferred field acceptance

The following are deliberately outside this quick-fix closeout:

1. Real spoken recording through Whisper/VAD, followed by force-stop/reopen and audio/transcript comparison
2. Optional native Smol drafts checked claim by claim against evidence
3. Permission denial, rotation, backgrounding, notification Stop, interruption/resume and repeated navigation on Android
4. Long-recording memory, thermal and battery behavior; export destinations; font scaling, texture appearance and sound policy
5. On future disposable test meetings, Android per-meeting delete/cancel/busy/failure/reopen behavior

Do not uninstall or clear app data to work around a signing mismatch.

## Showcase boundary

Describe the work as a local-first, evidence-linked engineering prototype. Use synthetic or expressly consented material. Label generated design boards and host pixel composites as illustrations. Production readiness, general accuracy, commercial comparisons and long-duration reliability have not been established.
