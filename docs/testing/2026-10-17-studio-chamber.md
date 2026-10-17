# Chamber depth — 2026-10-17

Stacked on instruments. Project format 9 keeps the existing step grid as pattern A, adds empty B/C/D banks, and defaults song mode, note repeat, 16 levels, mute and solo to off. The release version is unchanged.

The Chamber now has four editable 16/32-step patterns, a saved A/B/C/D order of up to 128 entries, and single-pattern or song playback. Song mode plays its order once from project frame zero; the project loop can repeat it. Each pattern uses the same pad sounds, length, swing and tempo map. Mute wins over solo. Settings and patterns use saved undo.

Note repeat runs in the native audio callback at quarter-, eighth- or sixteenth-note intervals. Holds work while stopped and follow the project tempo map while playing. Sixteen levels maps all pads to the chosen sound with velocities 1/16 through 16/16; pressure does not override those levels. This implements velocity levels, not pitch/filter/envelope levels.

Live hits, including repeats and MIDI, carry native frame, source-pad, velocity and pattern-bank values back to the recorder. A recording pass remains one undo step; hits quantise to the existing swung sixteenth grid. A bounded queue reports dropped capture instead of silently claiming a complete take. Panic uses a separate atomic flag, so a full event queue cannot prevent all-pads-off.

MIDI notes 36–51 map to pads 1–16 on any channel. USB/Bluetooth selection uses the previous PR's input adapter. Closing the Chamber or disconnecting input releases held pads. Gate/loop pattern hits release at the next step; one-shots retain their natural tails. Full-mix export, stems, pad bounce and mastering account for the final pad decay, and whole-file legacy pad lengths are resolved in detached export snapshots. Export resets sampler voices so repeated renders are deterministic.

## Host evidence

- Complete Java suite passes, including 15 ChamberTest assertions for four banks, chain termination, pad audibility, tails, native packing, undo and migration.
- Full native ASan/UBSan and threaded TSan suites pass, including 13 Chamber assertions for audio-frame repeat timing, captured bank/velocity, muted capture, concurrent snapshots/triggering, and panic under queue saturation.
- Complete JNI/WAV suite passes, including 20 NativeChamberTest assertions for A/B song playback, silence between hits, deterministic repeat exports, pad mute/solo, tempo changes, captured repeats, 16 levels, panic and gated loops after song end. Existing effects, automation, mastering and instruments fixtures pass.
- The first compile stopped on a misleading-indentation warning; formatting corrected it before the passing full run. Optional STRESS=1 was not run.

Logs on ops: `/tmp/killa-chamber-java.log`, `/tmp/killa-chamber-native.log`, `/tmp/killa-chamber-app.log`, `/tmp/killa-chamber-build.log`. API37 resources/Java/dex and the test APK build pass; both Studio and AI native libraries were rebuilt from this branch. A final targeted panic refinement uses a 2 ms release even on a looping pad configured for a ten-second release: 14 Chamber assertions pass under each sanitizer, and the complete JNI suite was rebuilt and rerun. Its log is `/tmp/killa-chamber-panic-final.log`; public native layouts were unchanged for that object rebuild.

## Phone evidence pending

No device install, screenshot, touch, listening, route, MIDI hardware or performance result is claimed. Keep this draft until the S25 engine is idle and the checks below have run:

```
adb -s 10.0.0.71:45515 shell am start -n ai.jackknife.killayue/.studio.StudioTestActivity --es test chamber --ei seconds 600 --ef master -40
adb -s 10.0.0.71:45515 shell am start -n ai.jackknife.killayue/.studio.StudioTestActivity --es test chamber-ui
```

The stress fixture combines 16 streamed instrument tracks, effects, automation, mastering A/B, pad songs, native repeat, levels and mute/solo changes; it drains and counts captured hits. The UI fixture creates only `Chamber device proof` and checks controller undo/redo plus reopened banks/song history. Manually exercise recording repeats and levels, switching/editing banks, song order, mute/solo, MIDI pad input, gate/release, bounce, export/stems, reload and route changes. Use small JPEG previews; preserve existing user projects and remove only the identified fixture after testing.
