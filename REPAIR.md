# Android directory-alias deletion repair

## Problem

The original Build 68 per-meeting delete path compared an absolute queue-file path with its canonical path. Android can expose app storage through `/data/user/0` while the canonical spelling is `/data/data`. This legitimate parent-directory alias was therefore rejected as unsafe, producing a generic I/O failure during deletion.

## Source correction

`AutoTranscription.removeDeletedMeeting` now canonicalises **only the queue file's parent**, then appends the original filename. The base file and `.bak` / `.new` sidecars still undergo symlink, regular-file and size validation. A leaf-file symlink must not be normalised away.

The regression test creates a parent-directory alias, deletes one selected queue entry and verifies that other IDs remain. Existing file/sidecar symlink rejection checks are retained. Host fixture roots use their real path so macOS temporary-directory aliases do not create false failures.

## Historical verification, 20 October 2026

- 154 production queue / recording-gate host checks passed.
- 162 filesystem/isolation checks and 27 deletion source checks passed.
- A disposable meeting failed deletion before the repair and was removed afterwards; successful deletion was also reported by the tester.
- The installed repair retained version 68 / `1.0-pilot68`.

The emergency installed APK was a minimal bytecode repair of the existing APK, not a full Gradle rebuild of this Java tree. Only the DEX payload changed; the original non-signature model, native, resource and manifest payloads were checked unchanged. This document intentionally does not distribute that APK or signing material.

These historical results do not prove transcription accuracy, audible quality, rotation handling or endurance. No all-meetings reset, uninstall or data clear was part of the repair.

## Re-run the focused checks

Use JDK 17 and the JSON dependency documented in `tests/flow-host/README.md`:

```sh
sh tests/meeting-delete/run.sh
JSON_JAR=/absolute/path/json-20240303.jar python3 tests/meeting-delete/queue_host.py
```

The queue suite uses Android service/engine doubles and models AtomicFile recovery. It is not Android lifecycle or crash-durability proof. Rebuild normally from source and validate on a disposable development device before relying on it.
