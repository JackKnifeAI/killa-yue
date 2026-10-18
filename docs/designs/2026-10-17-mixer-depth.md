# Studio phase 2: mixer depth

First PR from main at c80f5b3 (v1.4.0 is merged). No release/version bump.

## Signal flow

Tracks retain their existing level, pan, mute and solo controls. Up to eight optional groups collect tracks before the master. Routing is one level only (track -> group -> master), preventing feedback by construction. Group mute silences its members; group solo includes its members, while soloing a member keeps its group audible.

Each track/group has two post-fader sends, initially zero: a shared stereo reverb and a shared delay. Return strips have levels, meters and chains, and cannot feed the sends. The master retains its final safety limiter. Playback and exports use the same native graph; exports include a bounded effect tail when sends are active.

Every strip and master has an ordered, bypassable chain with up to eight stable-ID slots. This PR supplies mixing utilities (trim, stereo width, polarity); the following Effects PR adds the complete EQ/compressor/limiter/reverb/delay/saturation/filter suite and presets. Shared send processors are original native implementations, with no new third-party DSP source.

## Project and editing

Project format 4 adds mixer state with old projects loading dry/ungrouped/empty chains. Groups and slots have stable IDs. Routing, chains and parameters survive save/reopen, undo/redo and history branching. One knob/fader gesture is one undo step. Removed track references are pruned/restored with undo.

The audio callback uses bounded preallocated buffers, preallocated send delays, and a bounded configuration queue (ordered with transport edits; initial routing travels in the session swap): no allocation, locks or file I/O. Track sources keep playing while mixer parameters change. Export resets effect state for deterministic renders. Invalid/unbounded configs are rejected before reaching the callback.

## UI and proof

Keep LuxFrame strips and the existing gold knob art. Knob faces and fader caps capture drags; strip backgrounds continue to pan. A strip rack control opens slot order/bypass; tapping a track name opens routing; Add group lives in the mixer toolbar. Update guide defaults and README tour.

Tests cover dry backwards compatibility, routing/solo/mute, graph bounds, sends/tails, chain order and bypass, parameter updates, save/migration/undo, and identical playback/export processing. Run pure Java, native ASan/UBSan, app/check.sh, then build native + AI libraries and APK from this branch with API 37. Test on S25 in a throwaway project; preserve and back up the prepared Yeah. (remix) state. Report untested items and measured results in PR; Claude reviews, Alexander merges.
