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

## S25 validation — 2026-10-17

Validated on the S25 Ultra (SM-S938W), using the combined Studio stack plus unfinished-song preview PR #22 and progress fix PR #30. The 600-second stress APK was `a3c2aa5`; control/export checks used `38b3f27`; the final Chamber undo retest used `0294587`. These are combined-build results, not independent installs of each PR head.

All 151 offline device JNI/export assertions passed. The combined 16-track run exercised effects, 16 wet inserts, eight groups, shared sends, 48 automation lanes, tempo changes, mastering A/B, streamed instruments, Chamber song/repeat/levels and mute/solo. It passed with 300,004 callbacks: maximum 1.68 ms, average 0.972 ms, none beyond the 2.00 ms burst deadline. There were zero device xruns and zero underruns outside the existing 300 ms seek allowance; 32 underruns occurred within that allowance. It captured 5,832 live pad hits. The reported 8.3 ms output latency is an engine/device estimate, not a measured round trip.

Evidence is backed up on ops in `~/killa-studio-device-proof-2026-10-17/`. Test fixtures were backed up and removed. SHA-256 comparison found all 52 original project/library/checkpoint files unchanged, including the completed song and the Yeah remix. The app remains a 1.4.1 test build; nothing was merged or released.

All 20 Chamber JNI/export assertions and the controller's banks/chain/controls/undo/reopened-history checks passed. UI testing covered bank changes, A-C-B-D song order, step edits, repeat/16-level recording, mute/solo, panic, gate selection, bounce/undo/redo and export. Saved capture contained 1/16 and 7/16 velocities and adjacent repeat steps. Bounce retained patterns, created one track, disabled duplicate pattern playback and exported an 8-second stereo 48 kHz WAV.

The saved-history audit exposed a bug: one recording had four undo entries. Commit `1752f73` makes contiguous snapshots of the same history gesture merge, preserving the initial state and final capture. The regression test failed before the fix and passes afterward, including saved undo/redo, separate passes and branches. The complete Java suite passes with 880 counted assertions; API 37 compilation passes. On final APK `0294587`, a fresh real pad recording produced exactly one saved undo entry; one UI Undo restored all starting patterns and Redo restored the entire pass. Native sources did not change, so the already passing native/stress tests were not repeated.

Remaining: physical MIDI, MIDI flash delivery, route changes and subjective listening. The virtual MIDI sender did not complete, so it is not counted as a MIDI pass.
