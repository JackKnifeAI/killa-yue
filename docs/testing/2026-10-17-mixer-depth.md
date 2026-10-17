# Mixer depth validation

Branch: `ops/mixer-depth`, based on main `c80f5b3`. App version remains 1.4.0; the development APK is labelled `1.4.0-studiotest`. Project format becomes 4.

## Completed on ops

- `app/test/run.sh`: pass, including 27 mixer assertions for migration, persistence, routing, group solo/mute, stable slot IDs/order/bypass, track removal, stems and gesture undo.
- `studio/native/test/run.sh`: pass. All regular native tests under ASan/UBSan, threaded tests under TSan, and JNI tests passed. The opt-in native stress test was skipped by the existing runner.
- New native mixer test: 45 assertions, including mono metering, routing/solo/mute, shared delay timing/feedback, decaying reverb, deterministic reset and invalid/overfull configuration handling.
- New JNI mixer test: 15 assertions, including track/group/master processing, summed sends, return meters, effect bypass, sample-identical repeated wet exports, and live routing changes without reloading files or resetting the playhead.
- `app/check.sh` with API 37 / build-tools 37: pass.
- `libstudio.so` and `libkillastem.so` rebuilt from this branch with NDK 30; Studio uses Oboe 1.11.0.
- `STUDIO_TEST=1 app/build.sh`: pass; APK signature verified by the build. Both packaged Studio libraries match their branch build outputs byte for byte.

## Still required on the S25

No install, mixer touch test, listening evaluation, device timing result or mixer screenshot has been completed for this change. The phone was generating a song, so the generation was left running as directed by the Studio phase 2 brief. Keep this PR in draft until device checks are completed.

After generation finishes:

1. Take a fresh backup of the phone's project metadata, history and app preferences. Preserve the current “Yeah. (remix)” project; do not restore an older backup over newer work.
2. Install the branch's test APK using the currently connected ADB endpoint. Do not uninstall the app.
3. Run the offline JNI/export fixture and 10-minute device test:
   ```sh
   adb shell am start -n ai.jackknife.killayue/.studio.StudioTestActivity --es test mixer --ei seconds 600 --ef master -40
   ```
   This drives 16 tracks, eight groups, both shared sends and eight slots on every active strip. Sends update every 25 ms. The report includes callback timing, disk underruns and device xruns, with the existing startup/seek allowances.
4. Save `files/studio-test.txt` and logcat before starting the next test:
   ```sh
   adb shell am start -n ai.jackknife.killayue/.studio.StudioTestActivity --es test mixer-ui
   ```
   This creates “Mixer device proof” and exercises the real controller, autosave and persisted undo.
5. In Mixer, check portrait and landscape layout, horizontal/vertical scrolling away from controls, cap-only faders, all three knob gestures and double-tap resets, group add/rename/remove/routing, mute/solo, every rack action and undo/redo. Verify playback, full-mix export, isolated stems and Chamber bounce. Listen to the wet sound and bypass transitions.
6. Capture small JPEG previews for inspection; keep the full-resolution screenshot on disk. Update the mixer tour screenshot and this report with measured results.
7. Back up the disposable test project to ops before removing it. Leave only “Yeah. (remix)” in Studio, with its original metadata/history preserved.

The utility racks and shared send processors are original code. The complete EQ/compressor/limiter/reverb/delay/saturation/filter preset suite is the next phase. Stems intentionally keep track inserts and omit groups, sends and master processing; full mixes include them and add four seconds when sends are active. Projects saved in format 4 require the updated app.
