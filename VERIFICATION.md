# Preservation verification

Rechecked on macOS with JDK 17 in November 2026. Synthetic host fixtures only; no phone changes, microphone recording or native model inference.

## Passed

- Record entry: 77 guard/source checks.
- Recording startup/rotation: 131 host checks plus timer, ownership and rejected-edge source contracts.
- UI sound: 96 deterministic checks plus lifecycle/asset/source contracts.
- Session index: 55 checks plus source contracts.
- Brief-accuracy tripwires: 40 checks.
- All-meetings deletion: 33 filesystem checks and 20 source contracts.
- Per-meeting deletion: 162 filesystem/isolation checks and 27 source contracts.
- Repaired queue/gate: 154 checks, including parent-directory alias handling.
- UI icon source contracts, recorder diagnostics (2,438 logger checks; 188 retained rows validated), woven asset/source checks and synthetic decode fallback checks.
- Storage flow: 18 write, 5 reopen and 7 tamper checks, after setting `TMPDIR=/private/tmp` on macOS.
- `git diff --check`.

## Failures and limits retained

The first storage-flow run failed with `BRIEF_INVALID` under macOS's aliased default temporary path. The exact same suite passed with a canonical temporary directory. This is not proof that production brief storage supports every aliased Android path; its canonical-path guards still deserve device validation. No production brief-store behaviour was changed during preservation.

`tests/woven-ui/texture_visibility.py` could not finish because its hard-coded Linux DejaVu Sans path is absent on this Mac. Earlier historical composite results are not presented as a fresh pass. Other woven checks passed.

A fresh Android Gradle assembly was not run: this checkout lacks the mandatory external AAR/model inputs and installed Gradle command. Historical Build 68 assembly evidence is recorded separately in `PHASE1.md`; it is not a new build result.

No current physical-device acceptance, endurance, privacy penetration test or general accuracy claim follows from these results.
