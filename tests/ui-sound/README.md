# UI sound host checks

Run `sh tests/ui-sound/run.sh` with JDK 17 and Python 3. The deterministic gate tests cover admission, completion, cancellation, stale generations, rate limits and repeated deliberate taps. Source contracts check readiness-only loading, focus identity, mute, lifecycle and capture/playback suppression wiring.

Sound is opt-in. Loading never plays or queues a cue. Mute releases samples; capture/playback cancellation invalidates action callbacks. Gain is 0.30. UI state remains visible without sound.

These tests do not prove audible behavior on Android. Device acceptance must cover ringer/DND/system-effects/volume policy, cross-app focus restoration, navigation timing, repeated taps and absence of cue contamination in recordings. See [credits](../../approved-ui-assets/MARGIN-sound-credits.txt) for the three CC0 source clips.
