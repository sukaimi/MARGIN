# MARGIN / JotterBook

An experimental, local-first Android meeting notebook. MARGIN is the engineering project; JotterBook is the app's current name.

The design question is simple: can a meeting assistant keep the original evidence close enough that people can check its conclusions? This prototype combines durable local audio, offline transcription, editable speaker labels, source-linked notes and explicitly unverified AI drafts.

## Phase 1 scope

- User-initiated microphone capture into 16 kHz mono PCM/WAV chunks
- Offline Whisper tiny.en transcription with Silero voice activity detection
- Local meeting history, transcript revisions, evidence excerpts and manual speaker corrections
- TXT, WAV and ZIP exports
- Optional, manually requested Smol drafts with source references and conservative claim checks
- Notebook textures, contained raised edges, native controls and opt-in tactile UI sounds
- An explicit, in-app clean-slate reset with busy-work and path-safety guards

The app manifest has no Internet permission. Dependencies and models are downloaded separately on a development machine. Recording still requires microphone permission and a deliberate user action. Incoming record navigation uses a short-lived, process-local, one-time token.

Automatic ASR applies only to marked, newly finished sessions. Interrupted work requires manual Resume. Android short-service limits can constrain transcription and recording handoff. Draft generation is never automatic.

## Status

This is a source-only experimental prototype, not a production release or an accuracy-certified meeting service. Phase 1 engineering closes at a field-QA handoff; full microphone-to-transcript-to-summary, endurance, physical UI and sound acceptance remain pending. See [PHASE1.md](PHASE1.md) for the verification record, reset behavior and remaining field checks.

The summary guard catches a narrow set of lexical contradictions. It can reject valid wording and miss unsupported paraphrases. A citation does not prove a claim is correct. Every generated draft requires human review; no comparison with a commercial recorder or general accuracy guarantee is claimed.

## Build and verify

See [BUILD.md](BUILD.md). The candidate is version 67 / `1.0-pilot67`, ARM64, Android API 26+, compile/target API 35, JDK 17, Gradle 8.13 and Android Gradle Plugin 8.9.2. No Gradle wrapper is included. Required dependency locations, official download sources and SHA-256 pins are documented.

Model weights, native runners, AARs, APKs, signing keys, local configuration and user recordings/transcripts are excluded. Only the reviewed generated UI images and credited UI sound cues are included as media assets. An optional model/native runner must be acquired separately to exercise Smol drafts.

## Demonstrating the work

Use synthetic or expressly consented sample content. Show a source excerpt beside a draft claim, correct a speaker label, and explain which work runs locally. Label synthetic illustrations as such. Describe this as an engineering prototype while field acceptance is pending; host tests are not recording-quality or long-duration benchmarks.

## Licence

No project licence has been selected, so this repository includes no general project reuse grant. Third-party terms and retained notices apply to their respective components; see [THIRD_PARTY_LICENSES](THIRD_PARTY_LICENSES/) and [UI sound credits](approved-ui-assets/MARGIN-sound-credits.txt). Optional model terms are linked in [BUILD.md](BUILD.md). No model weights are redistributed.
