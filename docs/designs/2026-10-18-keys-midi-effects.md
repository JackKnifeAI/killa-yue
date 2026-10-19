# Keys, MIDI effects, recorded instruments and AI (1.7.0)

## Goals

The Studio's remaining roadmap items, with a playable piano and onboard MIDI effects as the headline:
MIDI recording and MIDI files, portable projects, recorded (sampled acoustic) instruments, and AI voices and beats.

## Keyboard engine

`Keyboard` (studio/native) plays the 88 keys of a piano, MIDI 21 to 108, from 1 to 4 velocity layers per key and a
release time per sound. Each key and layer is one streamed sample file, so live playing and playback (notes become
clips of those files) sound identical. Big banks use smaller read-ahead rings (8192 frames) and cached attacks (4096),
keeping a four-layer piano near 25 MB. Within a layer, velocity still shapes level (down to -6 dB); `Sounds.gainDb`
mirrors `Keyboard::layerGain` for playback. Old projects (49 keys, Pluck and Tines) keep their files and paths.

## Sounds

`Sounds` numbers every sound once and forever. Designed sounds (`InstrumentSamples`, `Synth`) are synthesised per key
and layer the first time: Pluck and Tines (unchanged), Keys (two-operator FM with a tine), 808, Pad (seven detuned
polyBLEP saws through a state-variable filter), Bass, Lead and Drums (a General MIDI kit from sines, filtered noise
and metallic square clusters). Acoustic sounds come from packs (`SoundPacks`): zips of FLAC roots, pinned by size and
SHA-256, downloaded once into `.killa-sounds` beside the projects and shared; each key is pitched from its nearest root
(a tie goes to the root above, since pitching down sounds more natural) by a Kaiser-windowed sinc resampler with
16 zero crossings, low-passed when pitching up. `tools/sounds/build.py` makes the packs; it measures every root's pitch
(YIN), because the libraries disagree about octave numbering (Salamander and the VSCO harp use C4 = 60, most VSCO
instruments C3 = 60), trims, loops sustained notes with equal-power crossfades, and levels each pack as a whole.

## MIDI effects

`MidiFx` is one engine for live playing and playback. Notes enter as starts and ends in beat time; each effect may
answer at once or later, never earlier, through a time-ordered queue to the next effect, and effects with their own
clock (arpeggiator, strum, note repeat) say when they next want to act. Offline, the chain steps from one due moment
to the next; live, a thread does the same against the transport (less the output latency) or a free clock. Every
effect's randomness is seeded and reset per run, so a track sounds the same on every play. A track's chain is saved
with its notes (`"fx"`), undoable with them, and applied in `MidiNotes.played`; Print writes the result into the
notes. Live, the keyboard has one voice per key, so only the latest note on a key may end it.

Sixteen effects: arpeggiator (twelve patterns, rates to 1/32 triplets, octaves, gate, swing, latch, accents, grid
start), chord (nineteen shapes and in-key triads, sevenths and ninths, inversions, voicings), scale lock (eighteen
scales; nearest, up, down or drop; white keys play the scale), harmonizer, note echo, strum, humanize, velocity,
transpose, key range, note length (fixed, longer, legato), chance (probability and every-Nth), note repeat (held or
fixed rolls), random pitch, swing and mono/legato. A latched arpeggio on playback stops at the end of the bar after
the last written note.

## Keys screen

`KeysScreen` replaces the piano-roll dialog: track and sound, record, play, the effects rack (pedals: tap to edit with
`FxDialog`, hold for bypass, order, duplicate, remove; presets), the roll (draw, resize, velocity, erase; two-finger
scroll; ruler seeks; shadows show the effects' result), and `GrandKeysView`: 88 keys, an overview map, one or two rows,
velocity from where a key is touched, sustain (hold or double-tap to latch), scale highlighting from a Scale lock.
Recording keeps what was played before the effects (overdub or replace, optional snap, count-in); a take is one undo
step.

## MIDI files, portable projects

`Smf` reads formats 0 and 1 (running status, zero-velocity offs, overlapping notes, positions kept in beats) and writes
format 1 at 480 ticks a beat with tempo changes, names and General MIDI programs; parts map to the nearest sound, channel
10 to Killa Drums. `Bundle` writes a project, its history and its recordings (not rebuilt samples, not packs, which are
named) as a `.killa` zip with a SHA-256 for each file, and opens one into a new folder with path checks, verification and
clean-up on failure.

## AI

All three run in the `:ai` process (`AiHost`), one at a time, never while recording or while a song is made; models
download on first use (`StudioModels`, unpacked read-only).

- **Speak**: Kokoro-82M v1.0 (Apache-2.0) through sherpa-onnx v1.13.8's C API and a small JNI bridge
  (`studio/voice`). 54 voices: American and British English, Spanish, French, Hindi, Italian, Japanese, Brazilian
  Portuguese and Mandarin. On the S25 it speaks about twice as fast as real time and loads in under a second.
- **Sing**: the song engine (YuE) is the best singing voice the app has, so Sing hands the words and voice to Create;
  the finished song comes into the project and the Splitter keeps its vocals.
- **Beat Lab**: Stable Audio Open Small (341M, made for Arm phones; Stability AI Community License) through Arm's
  LiteRT `audiogen` (Apache-2.0) as its own program: a prompt with the project's tempo, 1 to 4 bars up to 11 seconds,
  seeds for another take, 8 or 16 steps, and restyling a recording. The model is gated on Hugging Face; once the
  licence is accepted for JackKnifeAI, it is converted (`export_sao.py`), published with its NOTICE ("Powered by
  Stability AI") and pinned in `StudioModels`. Until then the card says it is coming.
