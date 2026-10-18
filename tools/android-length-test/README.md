# S20 duration experiment

These are development tools for the specifically authorized S20 on your ADB host, not an unattended benchmark for arbitrary phones. The device serial and output paths in `run-ladder.py` are intentionally explicit. All music inference happens on the phone; ops submits requests, collects results and backs them up.

`make-setlist.py` creates five original songs at 30, 60, 120, 180 and 210 seconds. `setlist.json` contains all prompts, lyrics, seeds and settings. Requests use Q5, eight render steps, one variation and up to 750 frames (30 seconds) per acoustic chunk. Contexts increase as recorded in the setlist. The semantic minimum approaches the requested frame count so an early model ending does not turn a 210-second capacity test into a short clip. This is an experimental duration constraint, not a musical-quality guarantee.

`thermal-guard.sh` runs on the phone as the ADB shell user. It owns one CPU-only engine process and checks Android thermal status, battery, skin and AP readings every 15 seconds. It uses STOP/CONT to preserve an in-progress computation while cooling. Missing readings pause the child. Critical thermal status or the higher test thresholds stop the run. Samsung's thermal controls are left intact. It starts paused and requires three cool samples before resuming. The thresholds are conservative test policy, not specifications for the battery or SoC. A killed watchdog, reboot or killed engine is still a possible interruption.

`run-ladder.py` runs on your ADB host, sequentially starts and collects each song, verifies the MP3 and latent samples are finite, measures actual duration, copies songs to the Killa Library, and backs up to the existing S20 directory on the mounted Buffalo NAS. It stops on an error for investigation, retaining completed tracks and checkpoints. A lock and existing-state check prevent duplicate runs. It does not automatically restart a failed experiment.

The native-helper method does **not** verify Android foreground-service survival. The main app's engine should remain off while the helper runs, to avoid two engines competing for memory. DeX disconnection does not by itself stop the native process.

## Live experiment locations

- Host evidence: `$KILLA_TEST_ROOT`
- Phone checkpoints: `/data/local/tmp/killa-s20-length-20261018/<song>/checkpoint`
- Phone library: `/sdcard/KillaYuE/library/20261018-open-source-*`
- Backup: `$KILLA_TEST_BACKUP`
- Ops localhost report: port 18320; this computer has a localhost SSH tunnel to it.

`status.json`, `index.html`, per-song `engine.log`, `thermal.csv`, `events.log` and final `audio-check.json` show what actually happened. Initial cool-start samples count as paused in the raw sample count; use `events.log` to distinguish those from heat-triggered pauses. Elapsed time includes cooling and collection overhead. Listening and chunk-boundary quality review are separate from finite-sample checks.

To stop, create `STOP` in the ops evidence directory; the runner checks it between polls. For a direct stop, use the specifically selected S20 to create `STOP` in its current song folder. Never use an unqualified `adb` command when multiple phones are connected. Do not send signals by a global process-name match.

## Capability probe

`../android-device-probe.cpp` is separate, read-only discovery code. Example build:

```sh
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang++" \
  -std=c++17 -O2 -march=armv8-a -static-libstdc++ \
  -Wl,-z,max-page-size=16384 -I /path/to/Vulkan-Headers/include \
  tools/android-device-probe.cpp -ldl -o android-device-probe
```

Run with no argument to query Android's system Vulkan loader, or provide the absolute path of the app's separate loader and its existing ICD environment. It creates no compute device and executes no shader. A missing loader is represented as a JSON error while preserving CPU/RAM information. It collects no serial, account, installed-app list or UUID, and sends nothing over the network.

## Explicit local configuration

This is an S20-specific experiment harness, not a generic production service.
The model check intentionally remains `SM-G988W`; thermal sensor names and the
on-phone binary/model paths need separate validation before using other phones.
The script requires these environment variables, with no default device serial:

```sh
export KILLA_TEST_SERIAL='YOUR_ADB_SERIAL'
export KILLA_TEST_ROOT='/path/to/new/run'
export KILLA_TEST_BACKUP='/path/to/mounted/backup/new-run'
export KILLA_TEST_BACKUP_MOUNT='/path/to/mounted/backup'
python3 tools/android-length-test/run-ladder.py
```

Keep the current running experiment's original scripts in place; these public
configuration changes are for subsequent runs.
