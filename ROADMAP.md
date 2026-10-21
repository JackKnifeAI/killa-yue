# Killa YuE roadmap

Killa YuE is free and open source. Everything here runs on the phone, with no account and no server.

## 1.1.0 (released)

- **Player deck**: previous, back 10 s, play or pause, forward 10 s, next; a spinning record, level bars and a gold scrubber.
- **Song titles**: a title box on the Create tab, plus retitling from the menu or with a long press.
- **Lyrics tab**: lyrics that light up as they're sung, with timing estimated from the score. Tap along once to set exact timing.
- **Lyrics in the files**: an `.lrc` file beside every song, and MP3 tags with the title, lyrics, timed lyrics and cover.
- **Song queue**: line up songs and leave the phone on the charger. Each song waits for a battery level you set and for a cool GPU.
- **Takes**: songs with the same lyrics and score are grouped as takes of one song.
- **Song recipes**: `.killa` files that carry everything needed to make the same song on another phone.
- **Sleep timer.**
- **Skins**: Killa Gold, Neon District (cyberpunk) and Foundry (industrial).
- **System now-playing**: status-bar chip coloured to the skin, media card, lock screen, headset buttons, and polite audio focus.
- **Album covers painted on the NPU**: about 3 seconds on a Snapdragon 8 Elite, with the title lettered by the app.
- **Gentle mode**, on by default: low CPU and GPU priority, smaller GPU jobs and short pauses, so the phone stays smooth while a song is made. The "isn't responding" message is gone.
- **Time left**: an estimate next to the elapsed time that learns from this phone and follows heat throttling.
- **Background**: battery-optimisation request before the first song, and a status line with links to the settings that can stop a song.
- **Albums**: build, reorder, queue new songs into an album, paint the album cover, play in album order, export a tagged album folder with a playlist, and share it as a zip.
- **Built on a PC**: the engine and GPU driver are now built with the Android NDK (see BUILDING.md).
- **Issue forms**: device report, bug report and feature request.

## 1.2.0 (released)

- **Share phone to phone with a QR code**: the app itself, a song, an album or the models. The other phone scans with its camera and gets a page in the app's own style, with players, downloads and install steps. Works with iPhones for songs and albums. No internet needed: both phones on the same Wi-Fi or a hotspot.
- **Copy the models from a friend's phone**: no mobile data; every file is checked against its published checksum and interrupted copies resume.
- **Dialogs and drop-downs in the app's own dress**: leather panel, gold rims, Killa lettering, in every skin.
- **Phone check**: phones without a supported Adreno GPU switch to processor mode with an explanation; low-memory phones are warned; a GPU driver crash before a song starts switches to the processor. "Device report" on the Engine tab copies what the project needs to support more phones.
- **Lyric videos** (new, experimental): a vertical 1080x1920 video of a song with its cover and lyrics lighting up, for TikTok, Reels and Shorts.
- **Fixes**: the Lyrics tab keeps moving when playback starts with it open; time left sits on its own line.

## 1.3.0 (released)

- **Automatic lyric sync**: a speech recogniser on the phone listens to the finished song and times every line to when it is actually sung, with a progress bar and the time left.
- **Sheet music**: engraved full scores as a vector PDF, or a part for any of 38 instruments in its own key and clef, checked note for note. Tabs for guitar, bass, ukulele, mandolin and banjo; chords to sing along to with chord diagrams and the lyrics under the lines; MusicXML for MuseScore, Sibelius, Dorico and Finale; MIDI for a DAW. Read, print and share them in the app; every sheet is saved with its song.
- **Write with AI**: a songwriter on the phone (Gemma 4 E4B, abliterated) writes and rewrites lyrics and style prompts, and can look things up on the web first.
- **Resume a song** that stopped part way (the GPU lost, or the app or engine stopped): it carries on from its last finished render step and sounds the same as an uninterrupted song.
- **GPU covers**: Stable Diffusion on the GPU for phones without the 8 Elite NPU.
- **Engine in its own app process**, so Android's child-process limits never apply.
- **Home-screen widget**: now playing, and the song being made.
- **Ringtones and a wake-up song**: set the chorus as a ringtone, or wake up to a brand-new song the queue made overnight.
- **Community skins**: install from a file, remove, make your own. The first is Akai '84 (`skins/akai-84.zip`).
- **Killa Gold** now wears its leather across the whole screen.
- **Fixes**: karaoke keeps its place through rests.

## 1.7.3: Discover, worldwide
- **Phones find each other anywhere**, with no setup: relays for phones on mobile data, volunteer helpers that switch on by themselves (Wi-Fi and charging, or a computer), and fixes so every nearby artist is listed however crowded the network.
- **The Discover player**: cover on a spinning record, the sung line, one-tap votes with counts, download, previous and next, and radio-style play-through.
- **Lyrics, albums and playlists, charts** (Top voted, Most played, Most downloaded) and **play and download counts**, all counted by the hosts themselves.
- **Host music from anywhere**: import a folder, share from any app, covers and lyrics from the files; downloaded songs keep playing when the artist is offline.
- **Follow bell**, artist links, and a **one-line helper** for computers (tools/install-helper.sh).

## 1.7.0: Keys, MIDI effects, recorded instruments and AI

- **Keys**: a playable 88-key piano in ivory, ebony and gold, with an overview map, touch-position velocity, a sustain pedal, one or two rows, and keys that light where notes play.
- **MIDI effects**, the headline: sixteen chainable effects on every instrument track, live and on playback: arpeggiator, chord, scale lock, harmonizer, note echo, strum, humanize, velocity, transpose, key range, note length, chance, note repeat, random pitch, swing and mono/legato, with ten presets and Print into the notes.
- **Recording notes** from the screen or a MIDI keyboard, overdub or replace, with input snap and count-in; a new piano roll with draw, resize, velocity and erase.
- **MIDI files** in and out.
- **Sounds**: Killa Keys, 808, Pad, Bass, Lead and Drums designed on the phone; fifteen recorded instruments downloaded on request (the Salamander grand with three velocity layers, and VSCO 2 CE upright pianos, organ, strings, harp, woodwinds, brass and mallets).
- **AI**: Groove (our own drum model, trained on 13.6 hours of real drummers in 18 styles, instant and on the phone), Beat Lab (beats as audio by Stable Audio Open Small, Powered by Stability AI, a few seconds a beat), Speak (Kokoro, 54 voices, eight languages) and Sing (the song engine's voice into a project).
- **Portable projects**: one .killa file to move a project to another phone or back it up.

## 1.5.0: Studio expansion

- **Mixer and effects**: groups, sends, editable effect chains, EQ, compressor, limiter, delay, reverb, saturation and filter.
- **Automation**: editable curves for levels, pan, sends and effect parameters, with undo and saved projects.
- **Mastering**: Dynamic, Balanced and Loud presets, measured loudness and sample peaks, and Before/Mastered comparison.
- **Instruments**: piano-roll editing, a two-row keyboard, and original Killa Pluck and Killa Tines sounds streamed from storage.
- **The Chamber**: four pattern banks, song order, note repeat, sixteen velocity levels, mute/solo, and one Undo step for a pad-recording pass.
- **Killa Gold pads**: gold frames, dark rubber faces and amber pressed artwork.
- **Unfinished songs**: listen to a checkpoint without changing it, save the preview separately, and resume the original; preview progress reports elapsed time.
- **Updates**: check GitHub for a new version, review its notes, download and verify the APK, and confirm installation through Android.

## 1.4.1 (released): GPU driver recovery

A fix release. The engine sorts a crash by its log (GPU driver fault or out of memory) instead of by its exit code; a driver fault restarts the engine on the GPU, up to twice, more carefully each time, and the job carries on from its checkpoint. Nothing switches to the processor by itself any more; phones an earlier version moved there are asked once whether to go back to the GPU.

## 1.4.0 (released): the Studio

Shipped further than planned: Remix (stems, mixer, export), and also Play Along recording, clip editing with stretch and pitch, and the Chamber pads with pad recording and bounce to a track. The plan as it was written:


Open any song in the new Studio tab, split it into stems, then mute, solo, level and pan them, with an undo and redo tree that keeps every branch, so you can go back, try something else and still return to what you had. Export the mix and the stems. The Studio's engine and project files are already in the code and tested on the S25.

## Next, top priority: the worldwide Killa YuE network

People will not be on the same Wi-Fi, so world reach comes first. Design: `docs/designs/2026-10-18-worldwide-network.md`.

- **Done (phase 1)**: the network node `libkillanet.so` (rust-libp2p, MIT, 3.9 MB) carries HAMMER over encrypted QUIC/TCP streams. Peers are found by their network identity instead of an address; network cards (`hammer://<PeerId>.p2p…`) need no IP address.
- **Next (phases 2 and 3, together)**:
  - a decentralised DHT for artists, songs and genres;
  - the BitTorrent DHT as the meeting point for first contact;
  - remembered peers;
  - reachability checks;
  - router port mapping;
  - hole punching;
  - Killa YuE peers relaying for each other when hole punching fails.

  The test: two phones on different carriers, Wi-Fi off on both, streaming a full song.
- **Then**:
  - worldwide Discover: search, genres, a "New releases" shelf, Live Follow across the network;
  - the desktop companion on the network;
  - hardening.

## Languages

The whole app, in-app guide and messages translated, picked from the phone's language with a choice in Settings:
- Spanish
- German
- Italian
- French
- Chinese (Simplified)
- Hindi (one of India's main languages; Bengali, Tamil or Telugu could follow)
- Russian
- Ukrainian

Plan:
- move every on-screen string into `res/values/strings.xml`;
- ship a `values-<lang>` folder per language;
- check the Killa script fonts have the glyphs, with a fallback face for Chinese, Devanagari and Cyrillic;
- have a native speaker review each language before release.

## Also planned

- **Showpiece sheet music**: the same notes drawn in your skin's look.
- **Other Snapdragon phones** first, then other chips through their own drivers or processor mode.
- **Emulator test pass** on Android 9 to 16 and several screen sizes before each release.
- **Hum a melody**: record a tune and get a song built on it.
- **Your own voice**: train a voice adapter from your recordings.
- **Faster rendering**: half-precision rendering and more.

## Studio tab: delivered and remaining

The Studio now includes stems, arrangement, Play Along recording, sampler pads, mixer effects, automation, mastering, the Keys piano with MIDI effects, recorded instruments, MIDI recording and MIDI files, portable projects, and AI voices. Remaining: velocity layers for more of the recorded instruments, and pitch bend and modulation from MIDI keyboards.

- **Stems**: split any song into vocals, drums, bass and the rest with a separation model on the phone's GPU. Every stem becomes an ordinary track you can edit.
- **Arrange**: audio and instrument tracks; move, trim, split, duplicate and loop clips; crossfades, markers, snap to the beat, and undo.
- **Record**: sing or play over a song with count-in, click, monitoring, punch in and out, loop takes and latency calibration. Record from the microphone, a USB audio interface, a file, or another app's sound where Android allows it.
- **Sampler and beats**: sample pads, chop by selection or transient, reverse, pitch and time stretch, envelope and filter; piano roll, drum step sequencer, velocity and swing.
- **Mixer and effects**: gain, pan, mute, solo, groups, sends, buses and meters; EQ, compression, limiter, delay, reverb, saturation and filter in editable chains, with automation curves. Mastering presets for loudness, EQ and limiting.
- **Instruments**: a playable two-row keyboard, sampled acoustic instruments streamed from storage, and MIDI keyboards over USB or Bluetooth.
- **AI voices and beats**: a voice model for spoken and sung lines and a beat model for backing tracks. Each loads only while it is in use.
- **Export**: the stereo mix, every stem, MIDI and a portable project with its media.
- **House rules**: recording and playback always come before AI work; one heavy model in memory at a time; projects save themselves and interrupted recordings are recovered; source files are never changed.

Built in this order, each part released when it passes its checks on the phone and sounds right: **Remix** (1.4.0); **Play along** (record from the mic, a USB interface or another app, latency calibration, loop takes, editing); **MIDI and instruments**; **sampler, pads and beats**; **effects, automation and mastering**; **AI voices and beats**.

## One home for every model (planned)

Today the app downloads models from several different Hugging Face accounts. We will host every model the app uses in JackKnifeAI's own Hugging Face account, each pinned by size and SHA-256 checksum, so no model can change under you or disappear, and the app gets everything from one place. Each model's licence is checked before we host a copy. Anything we may not redistribute stays at its original source, and the app says where it comes from.

## Ideas being considered

- **Live sheet music**: show the score being written as real notation while the model works.
- **Sing along**: record your voice over a song in the Lyrics tab and save the mix.
- **Make songs from your computer**: an opt-in mode that lets a laptop on the same Wi-Fi use the phone's engine through a browser, protected by a PIN.
- **Remix chains**: continue a friend's song from their recipe, then pass it on.
- **Honest labels**: an "AI-generated on this phone, offline" note and the recipe in each file's tags, for platforms that ask for AI disclosure.
- **App translations** and lyrics help for the many languages YuE2 sings.
- **Full-screen visualiser** with the cover.
- **Accessibility**: screen-reader labels and large-text layouts.

## HAMMER peer music: implementation preview
Implemented on the HAMMER branch: opt-in public/private catalogs; pinned encrypted streams; C++ original/Opus audio and bounded buffering; verified download, resume and rehost; signed comments and anonymous-display votes in sidecars; local host selection/failover; an Android artist-follow service with live peer push; and a downloadable desktop server.

The S25 Ultra device pass now covers menus, notification permission handling and taps, screen-off delivery, playback in all three modes, seeking, replica failover, downloading/rehosting, metadata and desktop export. See peer/VALIDATION.md. Longer unplugged battery/Doze testing, other devices/networks and broader listening tests remain before a wide rollout.

Future work: optional self-hosted reachability/relay discovery, broader public search, authenticated incremental metadata synchronization, larger host-capacity benchmarking, WAV-to-FLAC streaming, and validated Windows/macOS companion packages. No centralized provider is required by the current protocol.

### Library / Discover and media isolation preview

Implemented: Library visibility controls and audio import; signed searchable artist profiles and song artwork; reuse of generated covers and image upload; nearby NSD discovery; bounded signed public routing announcements; pre-TLS admission budgets; permissionless Android codec/image workers using read-only descriptors. Downloads retain the original publisher and enter Library directly.

Validation and remaining device checks are tracked in [peer/DISCOVERY-VALIDATION.md](peer/DISCOVERY-VALIDATION.md). Worldwide bootstrap, NAT traversal and end-to-end encrypted relays remain required for automatic internet-wide discovery; this preview does not complete them. Desktop decoder isolation, long-duration battery tests and independent security review also remain.
