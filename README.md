# JotterBook

**An experimental offline Android meeting notebook, previously called MARGIN.**

JotterBook explores a practical question: can an older Android phone capture a meeting, transcribe it locally and keep useful notes connected to the original evidence, without sending the meeting to a cloud service?

This repository preserves the prototype's source, tests, build instructions and limitations. Active development has ended. It is not a production release, a hosted service or an accuracy-certified recorder. The repository name remains `MARGIN`; the app label is **JotterBook**.

## What it does

| Capability | Implementation and limits |
| --- | --- |
| Record meetings | User-started microphone capture into local 16 kHz mono audio chunks; microphone permission is required. |
| Transcribe offline | Whisper tiny.en through sherpa-onnx, with Silero voice activity detection. English model; other languages are not supported by this configuration. |
| Keep evidence nearby | Local meeting history, transcript revisions, source excerpts, append-only notes and editable speaker labels. |
| Export | TXT, WAV and ZIP exports through Android's storage flow. Exported copies are independent of the app. |
| Draft meeting notes | Extractive/source-linked notes and optional manually requested Smol drafts. Generated claims require human review. |
| Navigate simply | Native Android notebook-style screens, large controls, reviewed texture assets and opt-in tactile UI sounds. |
| Delete meeting data | Confirmed per-meeting deletion and all-meetings reset, with busy-work, ownership and path-safety checks. |

Automatic transcription applies only to explicitly marked, newly finished sessions. It does not bulk-process old unmarked sessions. Interrupted transcription needs manual Resume. Recording/transcription handoff is constrained by Android background-service rules. AI draft generation is never automatic. Automatic speaker attribution has not been accepted as reliable; manual correction remains important.

## Status and provenance

- **Source version:** 68 / `1.0-pilot68`, with the 20 October 2026 Android directory-alias deletion repair preserved in source and regression tests.
- **Historical device:** Samsung Galaxy A70, Android 11. This is a historical test environment, not a compatibility guarantee for other devices.
- **Build 68:** source host checks, Android assembly and installation were completed during the original project. See [PHASE1.md](PHASE1.md) for their exact scope.
- **20 October repair:** the installed APK received a minimal bytecode repair; that was not a fresh full Gradle build of this source. The equivalent Java fix and host regression are included here. See [REPAIR.md](REPAIR.md).
- **Remaining limits:** long-duration recording, battery/thermal behaviour, broad Android lifecycle compatibility, transcription accuracy and generated-summary accuracy have not received comprehensive acceptance.

A passed host test is not a microphone-to-transcript benchmark. A citation is not proof that a generated claim is correct. The narrow lexical summary checks can reject valid wording and miss unsupported paraphrases. No comparison with a commercial recorder is claimed.

## Privacy and security

The app manifest contains **no Internet permission**. The intended recording and transcription path stays on the phone; dependencies and models are obtained separately on a development machine. Starting a recording requires a deliberate action. Incoming record navigation also uses a short-lived, one-time, process-local entry token.

Local-only does not mean risk-free. Audio and exports can contain confidential information. Obtain participants' consent where required, secure the device, and review files before sharing. This prototype does not claim encryption-at-rest, forensic secure erasure, production security certification or crash-proof deletion.

The source distribution excludes recordings, transcripts, personal test logs, credentials, signing keys, APKs, native binaries and model weights. The included audio files are credited UI cues, not meeting recordings. Do not commit private data or signing material when rebuilding.

## How the app fits together

```text
Microphone → RecorderService → durable audio chunks / SessionStore
                                  ↓
                   AutoTranscriptionService + work gate
                                  ↓
             Silero speech windows → Whisper tiny.en transcription
                                  ↓
          transcript revisions → evidence excerpts / manual corrections
                                  ↓
               source-linked notes → optional reviewed drafts
                                  ↓
                         TXT / WAV / ZIP export
```

Main source lives in `app/src/main/java/ai/codeandcraft/secondbrain/`:

| Area | Main classes |
| --- | --- |
| UI | `MainActivity`, `MeetingDetailActivity`, `NotebookSurface`, `NotebookNav`, `PopUi` |
| Capture and concurrency | `RecorderService`, `WavChunkWriter`, `RecordingStartGate`, `RecordEntryGuard` |
| Offline speech | `OfflineAsr`, `SileroSpeechGate`, `WindowedTranscription`, `AutoTranscription`, `AutoTranscriptionService` |
| Storage and evidence | `SessionStore`, `TranscriptTruthStore`, `EvidenceNoteStore`, `ManualSpeakerCorrections`, `SourceBundle` |
| Notes and drafts | `MeetingBriefEngine`, `MeetingBriefStore`, `SmolBriefEngine`, `BriefAccuracyPolicy` |
| Export and deletion | `MeetingExport`, `CleanSlate`, `SessionIndexPaths` |

## Build from source

Full instructions, official dependency URLs and verification commands are in **[BUILD.md](BUILD.md)**. This is source-only: cloning it is not sufficient to build a working ASR APK until the external dependencies are supplied.

Prerequisites:

- JDK 17 and installed Gradle 8.13. There is no Gradle wrapper.
- Android SDK platform 35 and build tools 35.0.0.
- Android Gradle Plugin 8.9.2; Kotlin runtime 2.2.0.
- ARM64 Android device, API 26 or newer for the base app. Optional native Smol setup targets API 30 and needs separate validation.
- sherpa-onnx 1.13.5 AAR, three Whisper tiny.en files and the Silero VAD model, placed exactly as documented.

Verify all five mandatory dependency files against [dependency-pins.json](dependency-pins.json), configure your SDK locally, then run:

```sh
gradle --no-daemon assembleDebug
```

The output is `app/build/outputs/apk/debug/app-debug.apk`. Use a controlled development device. An update to an existing installation requires the same signing certificate: do not uninstall or clear data to bypass a mismatch. A newly generated debug key will not preserve the identity of an older installation.

Optional Smol drafts need a separately built llama.cpp executable and the pinned SmolLM3 model. Neither is distributed here. Without them, the draft engine reports `MODEL_UNAVAILABLE`; recording and ASR do not depend on Smol. See the optional setup in [BUILD.md](BUILD.md).

## Test and verify

A fast dependency-free check with JDK 17:

```sh
sh tests/record-entry/run.sh
```

The full host commands are listed in [BUILD.md](BUILD.md). They cover record-entry rejection, recording gates/timers, UI-sound policy, session indexing, diagnostics, summary tripwires, deletion/isolation and storage flows. Some need Python, Pillow and the pinned external `org.json` jar. Keep downloaded jars outside the repository.

- [Meeting deletion tests](tests/meeting-delete/README.md)
- [Host storage-flow tests and JSON dependency](tests/flow-host/README.md)
- [Historical Phase 1 evidence and deferred device checks](PHASE1.md)
- [Directory-alias repair and validation](REPAIR.md)
- [Latest preservation checks, failures and environment limits](VERIFICATION.md)

Host shims model Android services, filesystems and native engines. They do not establish actual Whisper decoding, Smol inference, audio quality, Android durability or endurance. There is no configured GitHub Actions workflow.

## Data deletion behaviour

**One meeting:** open the meeting, then **More → Delete meeting → Delete permanently**. Cancel leaves it unchanged. The action targets that meeting's audio, transcript, drafts, notes, tasks, queue entry and owned temporary exports. Other meetings and their derivatives are preserved.

**All meetings:** open **About → Delete all meetings → Delete permanently**. Models and settings remain. Copies already saved outside the app are not removed.

Deletion is non-atomic logical filesystem removal. An I/O failure can leave selected data behind and is reported. Active recording/transcription blocks deletion. Unowned legacy exports can block per-meeting deletion because ownership cannot safely be established. Read [PHASE1.md](PHASE1.md) before changing this logic.

## Repository contents

- `app/`: Android source, resource assets and retained third-party notices.
- `tests/`: standalone JVM/Python regression and source-contract checks using synthetic fixtures.
- `approved-ui-assets/`: reviewed source artwork and UI sound credits.
- `tools/derive_woven_assets.py`: UI asset derivation helper.
- `BUILD.md`, `PHASE1.md`, `REPAIR.md`: reproduction instructions, historical evidence and known boundaries.
- `dependency-pins.json`, `THIRD_PARTY_LICENSES/`: dependency integrity records and notices.

## Reusing or continuing the prototype

Start with a fresh development device and synthetic audio. Reproduce host checks, provision the pinned dependencies, build normally, then test permission denial, rotation, backgrounding, notification Stop, force-stop/resume, export and deletion before trying real meetings. Perform device-level recording and long-duration tests rather than treating historical host results as acceptance.

No ongoing maintenance or support commitment is made. This preservation snapshot keeps an unfinished prototype understandable, not production-certified.

## Licence and credits

**No project licence has been selected.** Public visibility alone does not grant a general right to reuse, redistribute or commercialise the project's code. Third-party components retain their own terms.

See [THIRD_PARTY_LICENSES](THIRD_PARTY_LICENSES/), [UI sound credits](approved-ui-assets/MARGIN-sound-credits.txt) and the official model links in [BUILD.md](BUILD.md). No model weights are redistributed. Generated illustrations and host composites are not photographs of a tested device UI unless explicitly identified as such.
