# Mixer depth validation

The initial mixer work introduced project format 4. The current combined device validation uses the updated 1.4.1 Studio stack described below.

## Completed on ops

- `app/test/run.sh`: pass, including 27 mixer assertions for migration, persistence, routing, group solo/mute, stable slot IDs/order/bypass, track removal, stems and gesture undo.
- `studio/native/test/run.sh`: pass. All regular native tests under ASan/UBSan, threaded tests under TSan, and JNI tests passed. The opt-in native stress test was skipped by the existing runner.
- New native mixer test: 45 assertions, including mono metering, routing/solo/mute, shared delay timing/feedback, decaying reverb, deterministic reset and invalid/overfull configuration handling.
- New JNI mixer test: 15 assertions, including track/group/master processing, summed sends, return meters, effect bypass, sample-identical repeated wet exports, and live routing changes without reloading files or resetting the playhead.
- `app/check.sh` with API 37 / build-tools 37: pass.
- `libstudio.so` and `libkillastem.so` rebuilt from this branch with NDK 30; Studio uses Oboe 1.11.0.
- `STUDIO_TEST=1 app/build.sh`: pass; APK signature verified by the build. Both packaged Studio libraries match their branch build outputs byte for byte.

## S25 validation — 2026-10-17

Validated on the S25 Ultra (SM-S938W), using the combined Studio stack plus unfinished-song preview PR #22 and progress fix PR #30. The 600-second stress APK was `a3c2aa5`; control/export checks used `38b3f27`; the final Chamber undo retest used `0294587`. These are combined-build results, not independent installs of each PR head.

All 151 offline device JNI/export assertions passed. The combined 16-track run exercised effects, 16 wet inserts, eight groups, shared sends, 48 automation lanes, tempo changes, mastering A/B, streamed instruments, Chamber song/repeat/levels and mute/solo. It passed with 300,004 callbacks: maximum 1.68 ms, average 0.972 ms, none beyond the 2.00 ms burst deadline. There were zero device xruns and zero underruns outside the existing 300 ms seek allowance; 32 underruns occurred within that allowance. It captured 5,832 live pad hits. The reported 8.3 ms output latency is an engine/device estimate, not a measured round trip.

Evidence is backed up on ops in `~/killa-studio-device-proof-2026-10-17/`. Test fixtures were backed up and removed. SHA-256 comparison found all 52 original project/library/checkpoint files unchanged, including the completed song and the Yeah remix. The app remains a 1.4.1 test build; nothing was merged or released.

The real mixer controller passed setup undo/redo, one history step per send gesture, autosave and reopened history. Portrait mixer inspection, horizontal navigation, EQ rack operations, full-mix A/B export and aligned stems passed. Full mixes measured 8.227792 s; both isolated stems measured 4.076875 s, all stereo 48 kHz. Existing group/send routing remained in the saved fixture.

Remaining: subjective wet/bypass listening, physical route changes, landscape layout, and a complete manual sweep of every legacy knob/reset/group gesture. These are not inferred from the automated tests.
