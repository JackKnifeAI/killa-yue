# Play Along + sampler progress — 2026-10-16

Branch: `ops/play-along-sampler`, based on `0eabcfe` from Claudia's `ops/studio-build`.

Implementation is present, with acceptance still in progress. The requested combined commit includes the in-app guide and third-party licence texts.

## Passed

- `app/test/run.sh`: all pure Java tests, including format migration/history, clock placement, mid-song count-in exclusion, loop take overflow, comp undo, automatic crossfade undo, saved pads/patterns and shared tempo-map golden values.
- `studio/native/test/run.sh`: ASan/UBSan suite, threaded TSan suite, JNI tests. Exact swung onset and tempo-change onset; 64-voice stress; count-in and delayed transport anchors; threaded recording; route discontinuity; killed-process WAV checkpoint recovery.
- Stretch proof: exact output length; 440-to-880 Hz pitch error 0.4330 cents on the test signal.
- Android arm64 native build with NDK 30 and Oboe 1.11.0; API 35 app compile/dex and signed test APK.
- Galaxy S25 Ultra short test: microphone recording plus 16 looping audio tracks and pad triggers, zero underruns/xruns. Count-in controller saved a take and waveform, retired its receipt, and passed undo/redo.
- Record screen opened on the phone in the existing leather/gold skin.

## Final phone results

S25 Ultra, 48 kHz, 96-frame burst, 192-frame buffer, exclusive Oboe stream:

| Proof | Result |
| --- | --- |
| Recording + 16 looping tracks + pad triggers | 601.318 s; 28,854,048 recorded frames; 0 underruns, 0 xruns; worst callback 0.530 ms |
| 64 pad triggers every half-second + 16 tracks + recording | 30.133 s; 0 underruns, 0 xruns; worst callback 0.596 ms |
| Actual recording controller | Count-in, foreground service, saved clip/peaks, receipt retirement, undo/redo passed |
| Sampler-only export | Exact 2-second cycle; audible sampler energy; no practice-click energy after the sample ended |
| Force-stop during take, then reopen | Recovered saved clip and waveform; undo/redo passed |
| Normal APK manifest | No exported test activity and no debuggable flag |

Raw proof reports are retained on ops under `/home/jackknife/killa-yue-test/playalong/`. The native library used by the long soak and final build is identical; later Java fixes were exercised by the controller, export and recovery proofs.

## Device proof commands

Build with `STUDIO_TEST=1 app/build.sh`. Test builds alone are debuggable and contain the exported proof activity. After each completed test, force-stop the test app before starting another test because the existing activity does not consume a replacement intent.

```sh
adb shell am start -n ai.jackknife.killayue/.studio.StudioTestActivity --es test playalong --ei seconds 600
adb shell am start -n ai.jackknife.killayue/.studio.StudioTestActivity --es test playalong --ei seconds 30 --ei voices 64
adb shell am start -n ai.jackknife.killayue/.studio.StudioTestActivity --es test playalong-ui
adb shell am start -n ai.jackknife.killayue/.studio.StudioTestActivity --es test playalong-crash
# Wait for READY, then deliberately interrupt only that test take:
adb shell am force-stop ai.jackknife.killayue
adb shell am start -n ai.jackknife.killayue/.studio.StudioTestActivity --es test playalong-recover
adb exec-out run-as ai.jackknife.killayue cat files/studio-test.txt
```

The UI proof creates a separately named test project; it does not edit an existing song. It also checks sampler-only export length and that the practice click is excluded.

## Remaining acceptance gates

- Alexander listens, records over stems, and builds a beat from chopped stems.
- USB/loopback and Bluetooth hardware routes; recorded-click placement within 1 ms through loop/punch.
- Controlled other-app 1 kHz playback-capture proof and consent/revocation on this OS.
- Measured touch-to-sound latency; 10-minute rapid clip-reload benchmark; all-skin portrait/landscape visual acceptance.
- Arrangement pattern clips and live recording of pad hits remain future sampler work. Current patterns are one repeating saved project pattern.
- Capture alignment is manual; count-in uses the project's base tempo. These are documented limitations, not passed precision gates.

No merge to main or release has been performed.
