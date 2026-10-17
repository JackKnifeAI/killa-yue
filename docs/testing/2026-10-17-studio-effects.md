# Studio effects — 2026-10-17

Adds seven original native inserts to the mixer racks: three-band EQ with low/high cuts, linked stereo compressor, sample-peak limiter, reverb, delay with adjustable ping-pong feedback, saturation and multimode filter. Each has editable parameters, presets and bypass. Parameter forms apply as one undo step. Track inserts are included in aligned stem exports; full mixes include every rack and the estimated tails.

## Validation on ops

- Pure-Java project, mixer, migration and history tests pass. EffectsTest covers every preset's parameter vector, JSON/native packing, exact large slot IDs, undo/redo, limits and v4 utility-rack migration.
- Native ASan/UBSan suite passes. The effects fixtures contain 61 assertions: measured filter response, compressor ratio, limiter sample ceiling, stereo delay, reverb decay, deterministic reset, bypass, invalid configuration, wet-pool limits, mute handling, and tails draining after Stop without moving the clips or transport.
- Threaded tests pass under TSan. The optional `STRESS=1` host stress job is not part of this run.
- Java → JNI → WAV tests pass, including 66 effects assertions covering every preset, transparent bypass, repeated identical exports and delayed audio after a clip ends.
- Android resources, Java and dex compile against API 37.

The first native run overlapped header edits and is not used as validation. A clean rebuild and the subsequent run after the transport-tail fix passed, including loop playback under both sanitizers. Final logs are on ops at `/tmp/killa-effects-java.log`, `/tmp/killa-effects-app.log`, and `/tmp/killa-effects-final-native.log`.

## S25 validation — 2026-10-17

Validated on the S25 Ultra (SM-S938W), using the combined Studio stack plus unfinished-song preview PR #22 and progress fix PR #30. The 600-second stress APK was `a3c2aa5`; control/export checks used `38b3f27`; the final Chamber undo retest used `0294587`. These are combined-build results, not independent installs of each PR head.

All 151 offline device JNI/export assertions passed. The combined 16-track run exercised effects, 16 wet inserts, eight groups, shared sends, 48 automation lanes, tempo changes, mastering A/B, streamed instruments, Chamber song/repeat/levels and mute/solo. It passed with 300,004 callbacks: maximum 1.68 ms, average 0.972 ms, none beyond the 2.00 ms burst deadline. There were zero device xruns and zero underruns outside the existing 300 ms seek allowance; 32 underruns occurred within that allowance. It captured 5,832 live pad hits. The reported 8.3 ms output latency is an engine/device estimate, not a measured round trip.

Evidence is backed up on ops in `~/killa-studio-device-proof-2026-10-17/`. Test fixtures were backed up and removed. SHA-256 comparison found all 52 original project/library/checkpoint files unchanged, including the completed song and the Yeah remix. The app remains a 1.4.1 test build; nothing was merged or released.

The 66 effects JNI/export assertions passed on the phone. In the actual UI, EQ Vocal clarity populated the expected values, Apply saved them, cancelling Flat preserved them, bypass/enable worked, and moving EQ before Trim retained its values. Saved JSON confirms the reordered enabled EQ and its low-cut/preset parameters. Full-mix and stem export passed.

Remaining: subjective listening, physical route changes, and manually opening every effect/preset combination. All seven effects were exercised by the device fixtures; only EQ received the detailed form walkthrough.

## Implementation limits and provenance

- Eight slots per rack; sixteen reverb/delay inserts in total, counting bypassed slots. Shared sends use separate buffers. Wet storage is allocated when the engine is created; callback configuration and processing do not allocate it. Stable slot IDs retain wet buffers when rack positions change.
- Insert limiter measures sample peaks without lookahead; it is not a true-peak limiter. The existing final master safety limiter remains separate. Saturation is not oversampled.
- Delay/reverb exports conservatively include decaying tails. Serial long delays can add several minutes; there is no arbitrary one-minute truncation.
- Original DSP implementation under the repository's licence. Biquad equations follow Robert Bristow-Johnson's [W3C Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/). No GPL implementation was used.
- Project format 5 preserves older utility-rack values and reads older history snapshots. Existing projects receive no effects automatically.
