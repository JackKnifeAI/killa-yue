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

## S25 validation — 2026-10-17

Validated on the S25 Ultra (SM-S938W), using the combined Studio stack plus unfinished-song preview PR #22 and progress fix PR #30. The 600-second stress APK was `a3c2aa5`; control/export checks used `38b3f27`; the final Chamber undo retest used `0294587`. These are combined-build results, not independent installs of each PR head.

All 151 offline device JNI/export assertions passed. The combined 16-track run exercised effects, 16 wet inserts, eight groups, shared sends, 48 automation lanes, tempo changes, mastering A/B, streamed instruments, Chamber song/repeat/levels and mute/solo. It passed with 300,004 callbacks: maximum 1.68 ms, average 0.972 ms, none beyond the 2.00 ms burst deadline. There were zero device xruns and zero underruns outside the existing 300 ms seek allowance; 32 underruns occurred within that allowance. It captured 5,832 live pad hits. The reported 8.3 ms output latency is an engine/device estimate, not a measured round trip.

Evidence is backed up on ops in `~/killa-studio-device-proof-2026-10-17/`. Test fixtures were backed up and removed. SHA-256 comparison found all 52 original project/library/checkpoint files unchanged, including the completed song and the Yeah remix. The app remains a 1.4.1 test build; nothing was merged or released.

All 25 mastering JNI/export assertions passed. Balanced preset measurement, A/B switching, stale-measurement detection and restoration after reopened undo passed through the UI. Independent FFmpeg ebur128 analysis of phone exports agreed with the displayed -47.4 LUFS before, -26.1 LUFS after and -25.0 dBFS mastered sample peak. The intentionally quiet fixture exceeded the available gain range for a -14 LUFS target; the UI correctly disclosed the shortfall. Both exports have identical 8.227792-second length at stereo 48 kHz.

Remaining: subjective A/B listening, physical output routes and manual cancellation during measurement. Cancellation and all three presets passed the JNI fixtures.
