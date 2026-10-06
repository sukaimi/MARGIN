# MARGIN

Experimental native Android meeting recorder with durable local audio chunks, offline ASR, append-only notes, manual speaker corrections, source quotes, and TXT, WAV and ZIP exports. Capture requires the user to start recording and grant microphone access. Audio uses 16 kHz mono PCM chunks. The installed app has no Internet permission; dependencies and models are downloaded on a Mac or other development machine.

Automatic ASR applies only to explicitly marked, newly finished sessions. Old unmarked sessions are not bulk processed. Interrupted transcription requires manual Resume. Android API 34 imposes a genuine OS short-service time limit; recording may wait for the current transcription window to finish. Generative drafts require manual initiation; there is no automatic AI drafting.

## Build from source

See [BUILD.md](BUILD.md) for the mandatory AAR and four ASR asset paths, SHA-256 verification, and separate optional native Smol setup. Use JDK 17, installed Gradle 8.13, Android SDK 35 and build tools 35.0.0. This snapshot has no Gradle wrapper. Android Gradle Plugin remains 8.9.2, Kotlin runtime 2.2.0, application ID `ai.codeandcraft.secondbrain`, minimum API 26, target/compile API 35, public derivative version code 57 / version name `1.0-pilot57` and ARM64 ABI.

This public derivative is based on Build 56 plus a static privacy entry guard; it is not byte-identical to Build 56. Incoming Record navigation requires a fresh, unpredictable, process-local one-time token minted solely by the Detail Record click handler. Tokens expire after 10 seconds on a monotonic clock; absent, forged, replayed, overwritten, expired or restored tokens return to Meetings without starting recording. Main Record buttons still start from their actual UI clicks. The Gradle AAR location is portable. This repository excludes the private debug harness, native binaries, model weights, APKs, real user data, secrets and original Git history. The previous 14-second Mac build with five checksum-verified dependencies pertains to the unfixed public copy, not this guarded derivative. This derivative passed 68 standalone JVM guard/source checks and compiled successfully on the Mac offline in 10 seconds (10.923 seconds wall time), with the five previously checksum-verified local dependencies. It is not physically tested. The installed field phone remains Build 56. The optional native runner and private debug harness are absent, so public builds do not reproduce that field APK.

## Verification and limits

Run `sh tests/record-entry/run.sh` for standalone JVM negative tests and source integration checks. These checks cover both incoming lifecycle paths and the Detail click-only mint site; they do not exercise Android lifecycle, permission dialogs or microphone capture on a device. This change addresses a static finding only; no phone attack reproduction was performed.

Prior controlled checks used a physical Samsung Galaxy A70 running Android 11 with about 5.4 GiB RAM. These are historical private-build results, not tests run on this public source:

| Check | Reported result | App / ADB elapsed |
| --- | --- | --- |
| 95-second synthetic input, five new ASR windows | PASS | 35.33 s / 36.47 s |
| 31 injected queue fixtures | PASS | 1.428 s / 2.835 s |
| Seven four-format export checks | PASS | 21.114 s / 22.347 s |

The queue fixtures did not test actual process restart, service lifecycle or microphone capture end to end. Fourteen existing files were unchanged. Default native Home, Tasks, Summary and Transcript screens were visually verified. Large-font layout, picker save, 60-minute endurance and real notification Stop were not tested in this update. Current user field-test results are unknown. No Plaud comparison is available. Automatic speaker accuracy remains unaccepted. A Smol experiment produced an unsupported action; generative drafts are unverified and have no accuracy guarantee.

The private handed Build 56 APK SHA-256 was `b79088771cbc1099aa9474265c3a00a08c8160ff012a1dc7d04fb2b7d2d60524`. This is a verification record only, not a public APK or reproducibility claim. Other devices and Android versions, including API 30 optional native execution beyond that A70, have not been validated.

This is a source-only experimental pilot. No APK or model weights are distributed here. The current 60-minute field test has no reported result yet; the source is not certified for long recordings or transcript accuracy.

## Licence

No project licence has been selected, and no project reuse grant is included. Third-party upstream terms and retained notices apply to their respective components; see [THIRD_PARTY_LICENSES](THIRD_PARTY_LICENSES/). The notices are also included in the app's licence assets. Official licence sources: [Kotlin (Apache 2.0)](https://github.com/JetBrains/kotlin/blob/v2.2.0/license/LICENSE.txt), [sherpa-onnx (Apache 2.0)](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.5/LICENSE), [Whisper (MIT)](https://github.com/openai/whisper/blob/main/LICENSE), [Silero VAD (MIT)](https://github.com/snakers4/silero-vad/blob/master/LICENSE), [ONNX Runtime (MIT)](https://github.com/microsoft/onnxruntime/blob/main/LICENSE), and [llama.cpp (MIT)](https://github.com/ggml-org/llama.cpp/blob/11fe02151f79c41d0d4af7da708755d73b9c0da6/LICENSE). The optional [SmolLM3 model](https://huggingface.co/ggml-org/SmolLM3-3B-GGUF/tree/4965cb60b150737b68a0408c36aeefb65078f894) has its own Apache 2.0 terms; no model weights are redistributed.
