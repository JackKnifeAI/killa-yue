# Killa YuE roadmap

Killa YuE is free and open source. Everything here runs on the phone, with no account and no server.

## 1.1.0 (releasing now)

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

## 1.2.0 (next release)

### Smooth on a busy phone
- **Phone check**: a few-second GPU test on first launch. It reports chip, driver and memory, and switches to processor mode automatically if the GPU test fails. Results can be pasted into the device-report form.
- **Engine inside the app**: run the engine as a library in its own app process instead of a child process, so Android's child-process limits never apply.

### More phones
- **GPU covers**: Stable Diffusion on the GPU for phones without the 8 Elite NPU.
- **Other Snapdragon phones** first, then other chips through their own drivers or processor mode.
- **Emulator test pass** on Android 9 to 16 and several screen sizes before each release.

### Planned features
- **Albums and playlists.**
- **Lyrics assistant on the phone**: ideally on the NPU, which sits idle while the GPU makes the song.
- **Hum a melody**: record a tune and get a song built on it.
- **Lyric video export**: cover, animated lyrics and the song, as an MP4 ready to post.
- **Your own voice**: train a voice adapter from your recordings.
- **Stems**: separate vocals and backing. The engine renders one mix, so this needs a separation model.
- **Mastering**: loudness, EQ and limiting presets.
- **Community skins**: install skins from a file or a link.
- **Home-screen widget.**
- **Faster rendering**: half-precision rendering and more.

## Ideas being considered

- **Song in a QR code**: a recipe is small enough for a QR code. Scan it on another phone to make the song.
- **Live sheet music**: show the score being written as real notation while the model works.
- **Sheet music and MIDI export**: the score already keeps vocal and instrument parts separate, so producers can take them into a DAW.
- **Ringtones and alarms**: pick the chorus and set it as a ringtone. Or wake up to a brand-new song the queue made overnight.
- **Sing along**: record your voice over a song in the Lyrics tab and save the mix.
- **Make songs from your computer**: an opt-in mode that lets a laptop on the same Wi-Fi use the phone's engine through a browser, protected by a PIN.
- **Remix chains**: continue a friend's song from their recipe, then pass it on.
- **Honest labels**: an "AI-generated on this phone, offline" note and the recipe in each file's tags, for platforms that ask for AI disclosure.
- **App translations** and lyrics help for the many languages YuE2 sings.
- **Full-screen visualiser** with the cover.
- **Accessibility**: screen-reader labels and large-text layouts.
