# Studio effects — 2026-10-17

Adds seven original native inserts to the mixer racks: three-band EQ with low/high cuts, linked stereo compressor, sample-peak limiter, reverb, delay with adjustable ping-pong feedback, saturation and multimode filter. Each has editable parameters, presets and bypass. Parameter forms apply as one undo step. Track inserts are included in aligned stem exports; full mixes include every rack and the estimated tails.

## Validation on ops

- Pure-Java project, mixer, migration and history tests pass. EffectsTest covers every preset's parameter vector, JSON/native packing, exact large slot IDs, undo/redo, limits and v4 utility-rack migration.
- Native ASan/UBSan suite passes. The effects fixtures contain 61 assertions: measured filter response, compressor ratio, limiter sample ceiling, stereo delay, reverb decay, deterministic reset, bypass, invalid configuration, wet-pool limits, mute handling, and tails draining after Stop without moving the clips or transport.
- Threaded tests pass under TSan. The optional `STRESS=1` host stress job is not part of this run.
- Java → JNI → WAV tests pass, including 66 effects assertions covering every preset, transparent bypass, repeated identical exports and delayed audio after a clip ends.
- Android resources, Java and dex compile against API 37.

The first native run overlapped header edits and is not used as validation. A clean rebuild and the subsequent run after the transport-tail fix passed, including loop playback under both sanitizers. Final logs are on ops at `/tmp/killa-effects-java.log`, `/tmp/killa-effects-app.log`, and `/tmp/killa-effects-final-native.log`.

## Device validation

Pending. The S25 ADB endpoint supplied for this work is `10.0.0.71:45515`; engine work is also using the phone. Do not treat host results as phone performance measurements or ask for review before the device checks.

The test APK has an `effects` fixture. It first runs the JNI/export tests, then plays sixteen tracks through all seven new effects, sixteen wet inserts, eight groups, shared sends and repeated send/EQ edits. Once the phone is free:

```sh
adb -s 10.0.0.71:45515 shell am start -n ai.jackknife.killayue/.studio.StudioTestActivity --es test effects --ei seconds 600 --ef master -40
```

Also inspect the parameter/preset form, bypass, rack order, saved undo and export in a throwaway project, using small JPEG previews for screenshots. Keep user projects and active generation work intact. Bluetooth/USB route testing, subjective listening and screenshots remain untested.

## Implementation limits and provenance

- Eight slots per rack; sixteen reverb/delay inserts in total, counting bypassed slots. Shared sends use separate buffers. Wet storage is allocated when the engine is created; callback configuration and processing do not allocate it. Stable slot IDs retain wet buffers when rack positions change.
- Insert limiter measures sample peaks without lookahead; it is not a true-peak limiter. The existing final master safety limiter remains separate. Saturation is not oversampled.
- Delay/reverb exports conservatively include decaying tails. Serial long delays can add several minutes; there is no arbitrary one-minute truncation.
- Original DSP implementation under the repository's licence. Biquad equations follow Robert Bristow-Johnson's [W3C Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/). No GPL implementation was used.
- Project format 5 preserves older utility-rack values and reads older history snapshots. Existing projects receive no effects automatically.
