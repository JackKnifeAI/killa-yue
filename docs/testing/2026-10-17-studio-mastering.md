# Studio mastering — 2026-10-17

Stacked on the automation PR; this does not change the release version.

## Behaviour

Mixer → Mastering offers Dynamic (-18 LUFS), Balanced (-14), and Loud (-10). A detached project is rendered and measured, then three owned Master inserts (EQ, trim, sample-peak limiter) are applied in one undoable transaction. Existing manual effects stay in place. Three free Master slots are required. A/B bypasses only the owned mastering processing; playback and export follow the selected side. Preset replacement and removal preserve unrelated effects. Cancellation and a changed project discard the pending result.

The meter uses the stereo/mono K-weighting, 400 ms windows, 100 ms hops, absolute -70 LUFS gate and relative -10 LU gate described by [ITU-R BS.1770](https://www.itu.int/dms_pubrec/itu-r/rec/bs/r-rec-bs.1770-2-201103-s!!pdf-e.pdf). Other sample rates are resampled to 48 kHz for measurement; sample peak is measured at the original render rate. Formula implementation and test signals are original. There is no third-party DSP code, no true-peak or LRA measurement, and no certification claim.

Mastering performs up to four bounded adjustment passes with ±24 dB of trim. A target may be unreachable on highly dynamic material; the dialog reports the measured result and any target difference. A saved fingerprint invalidates readings when the mix changes. At least 0.4 seconds of audible audio and temporary free storage are needed. EQ/filter export tails now follow their pole decay, including automated parameter ranges, rather than truncating resonant output at clip end.

## Validation

- Pure-Java tests: LoudnessTest has 8 assertions, including synthesized cases 1–5 from [EBU Tech 3341](https://tech.ebu.ch/docs/tech/tech3341.pdf), the ±0.1 LU tolerances, mono weighting, peak and silence. MasteringTest has 23 assertions for presets, serialization, A/B, owned automation, replacement/removal, saved undo, stale measurements, migration and filter-tail estimates.
- NativeMasteringTest has 25 JNI/WAV assertions: all three targets, independent measured A/B exports, 44.1 kHz resampling, high-crest limiting, cancellation, temporary cleanup and audible resonant decay without a truncated tail.
- The existing native effects, automation, mixer, streaming, recording and export suites are rerun under ASan/UBSan; threaded fixtures also run under TSan. Optional STRESS=1 is separate.
- API 37 resources, Java and dex are checked. Both Studio and AI native libraries are rebuilt from this branch for the test APK.

Logs on ops: `/tmp/killa-mastering-java.log`, `/tmp/killa-mastering-native.log`, `/tmp/killa-mastering-app.log`, `/tmp/killa-mastering-build.log`.

## Phone validation pending

The S25 is connected at `10.0.0.71:45515`; Killa YuE and its engine were active while this work was built. No install, touch, listening, screenshot, route or device-performance result is claimed. The `mastering` fixture runs mixer/effects/automation/mastering JNI checks, then a 16-track effects/automation stress session with repeated mastering A/B changes:

```
adb -s 10.0.0.71:45515 shell am start -n ai.jackknife.killayue/.studio.StudioTestActivity --es test mastering --ei seconds 600 --ef master -40
```

Before review, run that fixture while generation is idle, exercise preset application/cancel/undo/A/B/export in a throwaway project, inspect a small JPEG screenshot, and listen for discontinuities. Preserve user projects. USB/Bluetooth route changes and real-song listening remain explicit device checks.
