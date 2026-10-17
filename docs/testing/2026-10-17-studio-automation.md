# Studio automation — 2026-10-17

Automation opens from Arrange or Mixer. Choose a strip and control, then tap/drag points or enter an exact beat and value. Volume, pan, sends, every numeric effect parameter and bypass are supported. Lanes follow tempo changes, retain effect identity when racks are reordered, support saved undo and are rendered in exports. New/older projects start with empty lanes (format 6).

## Validation on ops

- Pure-Java suite passes, including 24 automation assertions for serialization, tempo-boundary packing, effect identity, saved undo, track deletion/restoration, isolated stems, wet-tail estimates, validation and migration.
- Native ASan/UBSan suite passes, including 25 automation assertions for interpolation, pan, track/group/master/return levels, sends, effect parameters, bypass, deterministic reset and malformed data. Existing effects/mixer/streaming tests pass.
- Threaded TSan suite passes. Optional `STRESS=1` host stress is not included.
- JNI/WAV suite passes, including 6 automation assertions for changing tempo, repeated identical exports, disabled lanes restoring manual values and automation following a reordered effect. The 66 effects assertions also pass.
- Android API 37 resources, Java and dex compile.

Logs: `/tmp/killa-automation-java.log`, `/tmp/killa-automation-native.log`, `/tmp/killa-automation-app.log` on ops. The test APK rebuilds Studio and AI libraries from this branch.

## Device checks pending

No S25 touch, screenshot, listening, route or performance results are claimed. The `automation` test fixture runs the offline JNI/export checks, then exercises 16 tracks, all seven effects, 16 wet inserts, groups/sends and 48 volume/pan/EQ lanes across a tempo change, with concurrent mixer edits.

```sh
adb -s 10.0.0.71:45515 shell am start -n ai.jackknife.killayue/.studio.StudioTestActivity --es test automation --ei seconds 600 --ef master -40
```

Run only when the phone is free from generation/engine testing. Also test point gestures, exact values, cancel, undo after reopening, lane mute/delete, effect reorder and export in a throwaway project; capture small JPEG previews and preserve user projects. Keep the PR draft until this is done.

## Boundaries

- 128 lanes, 4096 stored points. The packed native copy is also limited to 4096 points, including points inserted at tempo changes. Lane validation checks that expansion before an edit is accepted.
- Points are stored in quarter-note beats. Drawing snaps to quarter beats; exact entry allows other positions. First/last values are held outside a lane. Filter mode and bypass are discrete; other curves interpolate.
- Native automation uses control blocks of at most 128 frames (about 2.67 ms at 48 kHz), split at points and tempo boundaries. Gains, continuous effect values and filter coefficients glide; bypass fades to dry. This is not advertised as sample-accurate parameter modulation.
- The callback uses preallocated configuration queues and numeric envelopes. Tempo conversion and serialization happen off the callback. Mixer updates preserve active readers/playhead.
- Drag previews are local until release, making one history transaction without flooding the engine queue. Enabled lanes override the manual values shown by the mixer faders.
- Full mixes retain all lanes. Isolated stems keep their track volume/pan/insert lanes; group/send/return/Master lanes are excluded. Tail estimates include automated sends, wet parameters and bypass enabling a wet insert.
