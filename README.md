# Killa YuE

<img src="art/icon.png" width="160" alt="Killa YuE icon">

Full songs, with vocals, written and rendered entirely on an Android phone.

Killa YuE is a native Android app around [YuE2](https://github.com/multimodal-art-projection/YuE), the open lyrics-to-song model from M-A-P. You type a style and lyrics, the phone's GPU does the rest, and nothing you write or generate leaves the device.

As far as we could find when we built it (October 2026), this is the first app that runs YuE2 on a phone's GPU.

## Status

Version 1.0.0. It has been run on exactly one phone: a Samsung Galaxy S25 Ultra (Snapdragon 8 Elite, Adreno 830, 12 GB). Treat every other device as untested. Reports from other Adreno phones are the most useful contribution right now.

## Requirements

- A phone with a **Qualcomm Adreno GPU**. The app ships its own GPU driver, which is Adreno-only.
- Android 9 or later, arm64.
- About 4 GB of storage for the model, and about 6 GB of free memory for a full-length song.
- A charger. On the test phone one full-length song used about 40% of the battery.

## Install

Download `killa-yue.apk` from [Releases](../../releases) and open it. Android will ask you to allow installs from your file manager, and may warn that the app targets an older Android version; that is expected (see "Why it is built this way").

On first launch, allow storage access, open the **Engine** tab and press **Get** on the song model and the sound decoder. Downloads resume if interrupted and are checked against their SHA-256.

## What it does

- **Create**: style with genre chips, lyrics with section tags, a length dial and slider (10 seconds to 6 minutes), score mode, an editable ABC score, and advanced settings (render steps, variations, seeds, sampling, MP3 or WAV).
- **Library**: play, re-render a song with a new sound, edit its score and render again, start a new song from part of an old one, share, rename, delete.
- **Recordings**: cover a recording (melody, or melody and chords), continue from it, or re-render it. Needs the cover and recording packs from the Engine tab.
- **Style adapters (LoRA)**: install from a Hugging Face repository or a file, then switch them on per song.
- **Writing aids**: save and recall styles and lyrics, clear both at once, undo and redo.

## How fast is it

Measured on the test phone, Q6 model, GPU at full clock:

| | Time |
|---|---|
| Writing the score and melody | about 40 to 50 ms per token |
| 20-second song, 32 render steps | about 7 minutes |
| One render step of a 3-minute song | about 2 minutes |

A full-length song at the default 32 render steps takes around an hour. Eight steps is a reasonable first listen. Speed drops when the phone is hot, because Android slows the GPU; the app shows a line when that happens.

## How it works

- The engine is [yue2.cpp](https://github.com/ServeurpersoCom/yue2.cpp), a C++/GGML port of YuE2, built for Android and run as a child process of a foreground service. The app talks to it over `127.0.0.1`.
- The stock Qualcomm Vulkan driver crashed on the engine's first GPU computation on the test phone, so the APK carries its own driver: the Khronos Vulkan loader and Mesa's open-source **Turnip** driver, built for Android.
- Android's kernel kills GPU jobs that run for many seconds, which is exactly what rendering a long song is. A one-line patch to Turnip (in `engine/`) opts the engine out of that check.
- The user interface is plain Java with no Gradle and no support libraries, so the whole app can be built on a phone.

## Building

See [BUILDING.md](BUILDING.md).

## Licences

- App source: Apache License 2.0, see [LICENSE](LICENSE).
- Bundled third-party software: see [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
- **YuE2 weights are not included and are not ours.** They are downloaded on request from Hugging Face and are licensed CC BY-NC 4.0 with the authors' creator permission. Individuals may use and monetise what they generate; companies need a licence from the authors for commercial use.
- The artwork in `art/` and `app/assets/materials/` was generated for this project with an image model and edited by hand.

Killa YuE is an independent project. It is not affiliated with or endorsed by M-A-P or the YuE2 authors.

## Contact

Bugs, device reports and ideas: please [open an issue](../../issues/new/choose).

For anything that should not be public, including security reports: **jackknifeai@proton.me**

## Who made this

Alexander, founder of JackKnife AI, and Claudia, his AI partner, who wrote the code. It went from an idea to a song in one day, on the phone it runs on.
