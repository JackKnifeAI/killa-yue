# Working on Killa YuE: handoff notes

Written 13 October 2026, at the end of the session that built the app on the phone. For whoever picks this up next, on any machine.

## State

- Public: https://github.com/JackKnifeAI/killa-yue, releases v1.0.0, v1.1.0 and v1.2.0 (13 October 2026; 1.2.0 is versionCode 6, APK SHA-256 2eed44c2…185a). Everything on `main` is pushed. Bump versionCode and versionName before the next release.
- The launch video (60 s walkthrough, posted by Alexander on 13 October 2026) ends on a QR code to releases/latest, so every new release is what viewers download.
- Not yet tested on a phone: lyric videos (released as experimental) and the receiving side of model sharing (needs two phones).
- The 1.1.0 release build is installed on the phone (Galaxy S25 Ultra, SM-S938W).
- Builds now happen on ops, not the phone: `~/killa-yue` (app, `app/local.env` points at the natives in `~/killa-yue-vendor/gentle` and the key in `~/.keys/killa-yue`), natives with the Android NDK from `~/src/killa` (BUILDING.md has the commands). The phone only runs and tests.

## What exists after 1.0.0 (all tested on the phone unless noted)

Player deck with transport buttons; song titles and retitling; Lyrics tab with estimated timing, tap-to-time, `.lrc` export and ID3 tags (title, lyrics, timed lyrics, cover); slim gold secondary buttons; song queue with a battery-level setting; takes; recipes (`.killa`); sleep timer; three skins (Killa Gold, Neon District, Foundry) with a selector on the Engine tab; system now-playing (status-bar chip coloured from the artwork, media card, lock screen, audio focus); **album covers painted on the NPU in about 3 seconds** (Covers.java + engine/npu/sdnpu.cpp), embedded in the MP3.

Not tested: the cover model's in-app download and unzip (the model was copied into place by hand), the automatic cover after a new song, the progress bar animation in the two new skins.

## Not done, in the order Alexander wants them

1. GPU fallback for covers on phones without the 8 Elite NPU: stable-diffusion.cpp is compiled in the phone's Linux container (`/root/src/stable-diffusion.cpp/build`) with models in `/root/src/sd-models`; never run.
2. Albums and playlists; an on-phone lyrics assistant; hum-a-melody; lyric video export; own-voice adapter training; stems; mastering; community skins; home-screen widget.
3. Speed of rendering (the slow stage). The NPU is not worth it for song writing (see the study in the project memory); half-precision rendering is untried.
4. Screenshots and a demo for the README (Alexander is capturing them).
5. Agreed but not started: a spyware check of the phone with Amnesty's MVT. Ask before starting; never reset the phone first.

## Where things are

| What | Where |
|---|---|
| Source of truth | phone: `/root/killa-yue-release` (git). A copy is on ops at `~/killa-yue`. |
| Engine source and Android build | phone: `/root/src/yue2.cpp` (`build-android/yue-server`) |
| GPU driver build (Turnip) | phone: `/root/src/mesa/build-bionic`; packaged files in `/root/yue2-bench/vkapp` |
| NPU runner and QNN runtime | phone: `/root/src/npu` (`sdnpu`, `lib/`, `rzip.py`, `cliptok.py`) |
| Prebuilt native files for packaging | ops: `~/killa-yue-vendor` (copies of the three items above) |
| Signing key | phone, Termux home: `pocketrig-release-key-BACKUP/` (not copied anywhere) |
| Art prompts and Pixella scripts | ops: `~/showcase/killa-yue-art` (`px.sh`, `gen.sh`, `edit.sh`, `skins/`) |
| App data on the phone | `/sdcard/KillaYuE/` (models, library, saved, adapters, cover-model, logs) |

## How to build

See BUILDING.md. In short: `cd app && ./build.sh`, with paths and the signing key in `app/local.env` (not in git). Native code (engine, driver, NPU runner) is compiled with Termux's clang on the phone; ops has no Android compiler set up, so on ops only the Java side and the packaging can be rebuilt, using the prebuilt files in `~/killa-yue-vendor`. Building on ops needs `aapt2`, `javac`, `dalvik-exchange`, `zipalign`, `apksigner` and the Android 23 jar (`apt install aapt apksigner zipalign default-jdk-headless dalvik-exchange android-sdk-platform-23`), and the signing key.

The QNN libraries must never be committed: their licence allows them inside the APK only.

## How to test on the phone

Wireless debugging must be on (the port changes each time; Alexander reads it out). The paired key lives in the phone's Termux home, so from the phone's Linux container:

```
HOME=/data/data/com.termux/files/home /data/data/com.termux/files/usr/bin/adb connect 127.0.0.1:<port>
```

ops is paired too (`adb connect 100.86.126.120:<port>` over Tailscale), though that failed once tonight while localhost worked. Then `adb install -r`, `input tap`, `uiautomator dump` to read the screen cheaply, `exec-out screencap -p` for pictures, `logcat -s AndroidRuntime:E` for crashes. Put Termux back in front afterwards (`am start -n com.termux/.app.TermuxActivity`).

## Rules learned the hard way

- One heavy job at a time on the phone, and no heavy job while a song is generating. Stacking a compile, an install and inference killed the whole container once.
- A full-length song costs about 40% of a charge. Check the battery before long GPU work.
- Speed numbers mean nothing without the GPU clock beside them (`/sys/class/kgsl/kgsl-3d0/max_gpuclk`); heat cuts it to a sixth.
- Nothing is published without Alexander saying so. Commits are co-authored by Claudia Casavant.
- Image models miscount (every staff came back with four lines) and cannot letter. Measure what they return; draw text in the app.
