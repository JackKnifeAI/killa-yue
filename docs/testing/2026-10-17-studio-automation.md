# Studio automation — 2026-10-17

Automation opens from Arrange or Mixer. Choose a strip and control, then tap/drag points or enter an exact beat and value. Volume, pan, sends, every numeric effect parameter and bypass are supported. Lanes follow tempo changes, retain effect identity when racks are reordered, support saved undo and are rendered in exports. New/older projects start with empty lanes (format 6).

## Validation on ops

- Pure-Java suite passes, including 24 automation assertions for serialization, tempo-boundary packing, effect identity, saved undo, track deletion/restoration, isolated stems, wet-tail estimates, validation and migration.
- Native ASan/UBSan suite passes, including 25 automation assertions for interpolation, pan, track/group/master/return levels, sends, effect parameters, bypass, deterministic reset and malformed data. Existing effects/mixer/streaming tests pass.
- Threaded TSan suite passes. Optional `STRESS=1` host stress is not included.
- JNI/WAV suite passes, including 6 automation assertions for changing tempo, repeated identical exports, disabled lanes restoring manual values and automation following a reordered effect. The 66 effects assertions also pass.
- Android API 37 resources, Java and dex compile.

Logs: `/tmp/killa-automation-java.log`, `/tmp/killa-automation-native.log`, `/tmp/killa-automation-app.log` on ops. The test APK rebuilds Studio and AI libraries from this branch.

## S25 validation — 2026-10-17

Validated on the S25 Ultra (SM-S938W), using the combined Studio stack plus unfinished-song preview PR #22 and progress fix PR #30. The 600-second stress APK was `a3c2aa5`; control/export checks used `38b3f27`; the final Chamber undo retest used `0294587`. These are combined-build results, not independent installs of each PR head.

All 151 offline device JNI/export assertions passed. The combined 16-track run exercised effects, 16 wet inserts, eight groups, shared sends, 48 automation lanes, tempo changes, mastering A/B, streamed instruments, Chamber song/repeat/levels and mute/solo. It passed with 300,004 callbacks: maximum 1.68 ms, average 0.972 ms, none beyond the 2.00 ms burst deadline. There were zero device xruns and zero underruns outside the existing 300 ms seek allowance; 32 underruns occurred within that allowance. It captured 5,832 live pad hits. The reported 8.3 ms output latency is an engine/device estimate, not a measured round trip.

Evidence is backed up on ops in `~/killa-studio-device-proof-2026-10-17/`. Test fixtures were backed up and removed. SHA-256 comparison found all 52 original project/library/checkpoint files unchanged, including the completed song and the Yeah remix. The app remains a 1.4.1 test build; nothing was merged or released.

The six automation JNI/export assertions passed. Volume-lane drawing, dragging, exact entry (beat 8.5, -12 dB), point deletion/undo and lane mute worked through the UI and persisted in JSON. Reopening the project and undoing the lane mute restored both its enabled state and the matching mastering measurement. Automated volume was included in the independently analysed full-mix exports.

Remaining manual checks: deleting an entire lane, effect-parameter/bypass lane forms, tempo-map editing and physical routes/listening. Tempo conversion, effect identity and export behavior passed the device/native fixtures.

## Boundaries

- 128 lanes, 4096 stored points. The packed native copy is also limited to 4096 points, including points inserted at tempo changes. Lane validation checks that expansion before an edit is accepted.
- Points are stored in quarter-note beats. Drawing snaps to quarter beats; exact entry allows other positions. First/last values are held outside a lane. Filter mode and bypass are discrete; other curves interpolate.
- Native automation uses control blocks of at most 128 frames (about 2.67 ms at 48 kHz), split at points and tempo boundaries. Gains, continuous effect values and filter coefficients glide; bypass fades to dry. This is not advertised as sample-accurate parameter modulation.
- The callback uses preallocated configuration queues and numeric envelopes. Tempo conversion and serialization happen off the callback. Mixer updates preserve active readers/playhead.
- Drag previews are local until release, making one history transaction without flooding the engine queue. Enabled lanes override the manual values shown by the mixer faders.
- Full mixes retain all lanes. Isolated stems keep their track volume/pan/insert lanes; group/send/return/Master lanes are excluded. Tail estimates include automated sends, wet parameters and bypass enabling a wet insert.
