# Phase 1 engineering handoff

Candidate: **67 / 1.0-pilot67**. Field acceptance remains pending. This document separates source/build checks from work that requires the actual Android app and hardware.

## Included

- Brings the public source forward from the earlier Build 57 snapshot to the JotterBook notebook UI and local meeting workflow.
- Retains record-entry, recording-start, session-index, UI-sound and privacy-oriented recorder-diagnostic guards.
- Improves visible paper grain and contained raised edges without replacing the approved artwork.
- Adds conservative summary tripwires and source-linked draft checks. Generated claims remain unverified.
- Adds an explicit clean-slate action for app-owned meeting data.

## Clean-slate behavior

Open **About → Delete all meetings → Delete permanently**. The confirmation explains that deletion cannot be undone.

The action removes local meetings, recordings, transcripts, summaries, notes, tasks, prepared private exports, the auto-transcription index and recognized temporary export/prompt files. It preserves downloaded models, model-verification cache and app settings. Files already exported outside the app remain outside its scope.

Recording, transcription and open or retiring meeting work block deletion. The filesystem helper preflights the targeted paths, rejects symlinks/path escape and bounds recursion. Deletion is logical filesystem removal, not a forensic secure-erase claim. It is not atomic: an I/O failure can leave some data; the app reports incomplete deletion and allows retry.

The host regression uses disposable synthetic directories only. It does not delete data on an installed app or establish Android lifecycle acceptance.

## Verification

Host checks passed on the final sanitized source on 2026-10-10, using JDK 17:

- 77 record-entry guard/source checks
- 131 recording-start/rotation checks plus timer, ownership and rejected-edge source assertions
- 96 UI-sound gate checks plus source/lifecycle contracts
- 55 session-index checks plus source contracts
- 2,438 recorder-logger checks; 188 retained synthetic rows verified against the five-field privacy schema
- 40 summary tripwire/contract checks
- 33 clean-slate filesystem checks plus 20 UI/lifecycle/worker-exclusion source contracts
- 30 storage-flow checks across write/reopen/tamper JVM runs
- UI-icon and asset/source checks; production surface against null/OOM/success decode stubs; synthetic texture/contrast checks

These are host/source tests. Native ASR and Android APIs in the storage-flow suite use explicit doubles. No native model inference, microphone capture or installed-device reset was performed. Synthetic paper contrast measured 5.35:1 at the worst tested secondary-text sample; this does not establish physical-screen acceptance.

Android `assembleRelease` passed on the same production source with Gradle 8.13 and Android SDK/build-tools 35, including release lint, resource linking, Java compilation, DEX and packaging (45 tasks). The resulting APK was verified as version 67 and unsigned; ZIP integrity/alignment and expected native/model payload hashes passed. The public repository omits those acquired dependencies, all APKs and signing material. See BUILD.md to reconstruct the build environment.

A separately signed candidate has been installed on one test device. This installation does not establish recording, transcription, summary or clean-slate acceptance; installed-app deletion remains pending. The cloud-built unsigned APK cannot be installed as-is. No GitHub Actions workflow is included in this repository; local cloud checks above are the verification record.

## Field checks intentionally deferred

1. Record real spoken input, stop, and run actual Whisper/VAD transcription. Confirm saved audio and transcript after force-stop/reopen.
2. If using the optional Smol model, generate a draft and compare every claim, name, date, qualifier and citation with the original evidence.
3. Exercise permission denial, rotation, backgrounding, notification Stop, interruption/resume and repeated navigation.
4. Validate a long recording, memory/thermal/battery behavior, export destination, font scaling, texture visibility and UI-sound policy.
5. On disposable test meetings, exercise clean-slate cancel, busy rejection, successful reset, reopen and a fresh recording; verify models/settings and externally saved exports survive.

Use the authorized signing key for an installed-app update. Do not uninstall or clear app data to work around a signing mismatch. An unsigned build cannot be installed as delivered.

## Showcase boundary

The defensible story is a local-first, evidence-linked Android prototype with explicit verification boundaries. Demonstrate using synthetic or expressly consented material. Do not present generated design boards or host pixel composites as device screenshots, claim production readiness, benchmark accuracy or long-duration reliability, or describe pending field checks as passed.
