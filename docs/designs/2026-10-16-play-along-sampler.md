# Play Along and sampler

Requested together by Alexander, 16 October 2026. Based on Claudia's `ops/studio-build` at `0eabcfe`, including the mixer controls and in-app guide.

This increment combines the Play Along recording/editing work with the sampler. MIDI, SFZ instruments and the keyboard remain phase 3. The project format advances from v2 to v3 once; performance data belongs to that migration rather than skipping to the planned MIDI schema.

## Behavior

- Record mic or selected USB input over the session. Android 10+ playback capture uses system consent. A silent initial five seconds stops capture with an explanation that the source may be paused or forbid capture.
- Recording uses a bounded native input ring, a disk worker, one-second WAV checkpoints and a durable placement receipt. Count-in/pre-roll is excluded, loops produce takes, and punch limits the resulting clips. Interrupted takes can be recovered and undone.
- Device-clock anchors plus per-route calibration place microphone takes. Playback capture uses a separate clock and requires manual clip alignment; it is not claimed sample-accurate.
- Set tempo, metre, count-in, click, tempo changes and markers. Move/trim/split/duplicate clips, add fades, automatically crossfade partial overlaps, and swipe ranges into a comp track.
- Play 16 sample pads with one-shot/gate/held-loop modes, reverse, pitch, ADSR, low-pass and choke groups. The native sampler has 64 voices, Hermite interpolation and a 128 MB sample budget. A synthesized kit is available without external recordings.
- Assign project clips or chop detected transients into pads. Save a 16/32-step velocity pattern with swing. The pattern repeats over the project; independent arrangement pattern clips and live pad-hit recording are not part of this increment.
- Render independent duration and pitch changes offline with pinned MIT-licensed Signalsmith Stretch/Linear, retaining originals through undo.
- Mix export includes the enabled sampler pattern, excludes the practice click, and stems include a separate sampler file. A pattern-only project exports one cycle.

## Lifetime and bounds

Audio callbacks do not allocate, lock or access disk. Sample data is retired on the control thread. Recording finalization and receipt checkpoints share the save worker. Editing/undo/project changes are blocked during recording; model jobs and recording are mutually excluded. Project close releases its input/capture resources.

The engine supports 64 playback tracks. Long loop recordings keep additional passes as sequential clips on a muted final “More takes” track, preserving source offsets and audio. Loop recording requires a free track.

## Acceptance

Automated evidence and remaining device/listening gates are recorded in `.superpowers/sdd/play-along-sampler/progress.md`. This is a development increment, not a declaration that every phase-2/4 acceptance gate has passed. No version bump, release publication or merge to main is included.
