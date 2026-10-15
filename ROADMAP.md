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

## 1.3.0 (next)

### Built, being tested on phones
- **Automatic lyric sync**: a speech recogniser on the phone listens to the finished song and times every line to when it is actually sung.
- **GPU covers**: Stable Diffusion on the GPU for phones without the 8 Elite NPU.
- **Engine in its own app process**, so Android's child-process limits never apply.
- **Home-screen widget**: now playing, and the song being made.
- **Ringtones and a wake-up song**: set the chorus as a ringtone, or wake up to a brand-new song the queue made overnight.
- **Sheet music and MIDI**: engraved sheet music as a vector PDF, plus MIDI for a DAW. Full scores, or a part for any of 38 instruments in its own key and clef, checked note for note.
- **Community skins**: install from a file, remove, make your own.

### Still to do
- **Guitar tabs and chord diagrams** for guitar, bass, ukulele, mandolin and banjo.
- **MusicXML export** for MuseScore, Sibelius, Dorico and Finale.
- **Showpiece sheet music**: the same notes drawn in your skin's look.
- **Other Snapdragon phones** first, then other chips through their own drivers or processor mode.
- **Emulator test pass** on Android 9 to 16 and several screen sizes before each release.

### Planned features
- **Lyrics and style assistant on the phone**: write lyrics and build style prompts from a song's sound, ideally on the NPU, which sits idle while the GPU makes the song.
- **Hum a melody**: record a tune and get a song built on it.
- **Your own voice**: train a voice adapter from your recordings.
- **Faster rendering**: half-precision rendering and more.

## Studio tab (planned)

Everything an artist does after the song is made, in the same app, offline and free. It is rebuilt for Killa YuE from our earlier Killa Studio work.

- **Stems**: split any song into vocals, drums, bass and the rest with a separation model on the phone's GPU. Every stem becomes an ordinary track you can edit.
- **Arrange**: audio and instrument tracks; move, trim, split, duplicate and loop clips; crossfades, markers, snap to the beat, and undo.
- **Record**: sing or play over a song with count-in, click, monitoring, punch in and out, loop takes and latency calibration. Record from the microphone, a USB audio interface, a file, or another app's sound where Android allows it.
- **Sampler and beats**: sample pads, chop by selection or transient, reverse, pitch and time stretch, envelope and filter; piano roll, drum step sequencer, velocity and swing.
- **Mixer and effects**: gain, pan, mute, solo, groups, sends, buses and meters; EQ, compression, limiter, delay, reverb, saturation and filter in editable chains, with automation curves. Mastering presets for loudness, EQ and limiting.
- **Instruments**: a playable two-row keyboard, sampled acoustic instruments streamed from storage, and MIDI keyboards over USB or Bluetooth.
- **AI voices and beats**: a voice model for spoken and sung lines and a beat model for backing tracks. Each loads only while it is in use.
- **Export**: the stereo mix, every stem, MIDI and a portable project with its media.
- **House rules**: recording and playback always come before AI work; one heavy model in memory at a time; projects save themselves and interrupted recordings are recovered; source files are never changed.

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
