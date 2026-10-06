# Building MARGIN

## Standalone record-entry checks

From the repository root, with JDK 17 installed:

```sh
sh tests/record-entry/run.sh
```

No Android dependencies or downloads are required. The runner compiles the pure-Java guard and tests in an owned `/tmp` directory and removes it on exit. It checks rejected external/forged/replayed/expired/overwritten/restored tokens, one-time valid consumption, process-local state and source wiring for both Main entry points and the Detail click-only mint. These are JVM and static source checks, not device or Android runtime validation.

This public derivative is version code 57 / `1.0-pilot57`, based on Build 56 plus the privacy guard. The installed field phone remains 56. The historical 14-second build applies to the unfixed copy; the guarded derivative passed 68 standalone JVM guard/source checks and an offline Mac Android build in 10 seconds (10.923 seconds wall time). It is not physically tested.

## Mandatory dependencies

Install JDK 17, Gradle 8.13, Android SDK platform 35 and build tools 35.0.0 on your development machine. There is no Gradle wrapper; use installed `gradle`. Configure your SDK with `ANDROID_HOME` or an ignored `local.properties` containing your own `sdk.dir`. The generic Gradle JVM heap is 2 GB; no host-specific Java path is supplied.

Download on the development machine, outside Git staging. Do not commit weights, AARs, JNI binaries or APKs. [dependency-pins.json](dependency-pins.json) records the five mandatory files, their already verified official URLs, byte sizes and SHA-256 values matching the installed dependencies.

1. Download the official [sherpa-onnx 1.13.5 AAR](https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar) to `app/libs/sherpa-onnx-1.13.5.aar`.
2. Download and extract the official [Whisper tiny.en archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2). Copy its three specified members to the paths below; use the quantized encoder and decoder.
3. Download the official [Silero VAD release model](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx) to the fourth asset path below.

The exact mandatory ASR paths are:

```text
app/src/main/assets/models/whisper-tiny-en/tiny.en-encoder.int8.onnx
app/src/main/assets/models/whisper-tiny-en/tiny.en-decoder.int8.onnx
app/src/main/assets/models/whisper-tiny-en/tiny.en-tokens.txt
app/src/main/assets/models/vad/silero_vad.onnx
```

From the repository root, verify all five files before building:

```sh
python3 - <<'PY'
import hashlib, json
from pathlib import Path
for pin in json.loads(Path('dependency-pins.json').read_text()):
    path = Path(pin['destination'])
    if path.stat().st_size != pin['bytes']:
        raise SystemExit(f'Size mismatch: {path}')
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != pin['sha256']:
        raise SystemExit(f'SHA-256 mismatch: {path}')
    print(f'Verified: {path}')
PY
gradle --version
gradle --no-daemon assembleDebug
```

The local debug output is `app/build/outputs/apk/debug/app-debug.apk`. Do not publish or commit it. Installing an update requires the existing signing certificate; do not uninstall or clear data to bypass a mismatch, because recordings may be deleted. Device work requires separate permission and a controlled development phone. The field phone is outside this preparation.

## Optional native Smol drafts

Recording, storage and offline ASR do not depend on Smol. Without the optional native runner or verified model, the draft engine reports `MODEL_UNAVAILABLE`. Drafts require manual initiation and human verification.

Use a generic external upstream checkout outside this public repository so its history is excluded. With Git, CMake and an Android NDK installed, set `ANDROID_NDK_ROOT` to your own NDK directory. Set `MARGIN_ROOT` to this source root and run the following from a separate workspace outside it:

```sh
git clone https://github.com/ggml-org/llama.cpp.git llama.cpp
cd llama.cpp
git checkout --detach 11fe02151f79c41d0d4af7da708755d73b9c0da6
cmake -S . -B build-margin-android \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-30 \
  -DANDROID_STL=c++_static \
  -DBUILD_SHARED_LIBS=OFF \
  -DGGML_BACKEND_DL=OFF \
  -DGGML_NATIVE=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_LLAMAFILE=OFF \
  -DLLAMA_BUILD_EXAMPLES=ON \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_OPENSSL=OFF \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build-margin-android --target llama-cli --config Release
mkdir -p "$MARGIN_ROOT/app/src/main/jniLibs/arm64-v8a"
cp build-margin-android/bin/llama-cli "$MARGIN_ROOT/app/src/main/jniLibs/arm64-v8a/libmargin-llama.so"
```

Copy the output before building the APK. Despite its `.so` destination name, this file is the CLI executable used by the app. This optional native setup targets API 30; prior actual native validation was only on the physical A70 with Android 11. No NDK version or broader native compatibility is claimed here.

Acquire `SmolLM3-Q4_K_M.gguf` from [ggml-org/SmolLM3-3B-GGUF at revision 4965cb60b150737b68a0408c36aeefb65078f894](https://huggingface.co/ggml-org/SmolLM3-3B-GGUF/tree/4965cb60b150737b68a0408c36aeefb65078f894) on the development machine, outside this repository. Required size: **1,915,305,312 bytes**. Required SHA-256: `8334b850b7bd46238c16b0c550df2138f0889bf433809008cc17a8b05761863e`. On macOS, verify with `wc -c < SmolLM3-Q4_K_M.gguf` and `shasum -a 256 SmolLM3-Q4_K_M.gguf` before installation.

The code-confirmed private app destination is `files/models/smollm3/SmolLM3-Q4_K_M.gguf`. For local development only, after explicit permission to use your controlled development phone, install a debuggable build with matching signing credentials and use the following example. Set `DEV_SERIAL` to that phone's serial and `SMOL_MODEL` to the verified model's local path. These are documentation commands only; none were executed in this preparation.

```sh
adb -s "$DEV_SERIAL" shell run-as ai.codeandcraft.secondbrain mkdir -p files/models/smollm3
adb -s "$DEV_SERIAL" shell run-as ai.codeandcraft.secondbrain sh -c '"cat > files/models/smollm3/SmolLM3-Q4_K_M.gguf"' < "$SMOL_MODEL"
```

`run-as` requires a debuggable installation. The app verifies the model size and SHA-256. This sidecar is optional and is not an entry in the five-file mandatory dependency record. It is not packaged in the public source or APK here. Unsupported actions were observed in a prior hallucination experiment; treat all generated text as unverified drafts.
