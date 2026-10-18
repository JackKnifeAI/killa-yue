# Portable Android song engine: local development record

Date: 18 October 2026. The measurements below belong to the earlier `1.5.0-android.1` prototype (version code 19). The PR is rebased onto upstream 1.6.0 as `1.6.1` (version code 20); the old APK hash is not the integrated artifact. See [integration review](android-integration-review.md) for current CI and release gates. Neither build is a certified device-support list.

## Behavior

| Selection | Executable / JNI library | CPU selection | GPU loader |
|---|---|---|---|
| CPU, either driver setting | `libyueserver_portable.so` / `libyueengine_portable.so` | Runtime CPU features; ARMv8-A baseline available | None |
| GPU + system driver | Portable engine | Same runtime selection | Android system Vulkan; experimental |
| GPU + bundled driver | Existing `libyueserver.so` / `libyueengine.so` | Existing optimized static build | Existing bundled Turnip |

The seven CPU variants are `android_armv8.0_1`, `armv8.2_1`, `armv8.2_2`, `armv8.6_1`, `armv9.0_1`, `armv9.2_1` and `armv9.2_2`. GGML scores the extensions actually reported by the device; the score functions and core code compile for baseline ARMv8-A. Unsupported variants are not selected. Private packaged names prevent the old static engine's backend scan from finding the portable modules.

The Settings tab exposes the GPU driver choice and marks the system path experimental. New Adreno 650 installs and phones outside the bundled Adreno path start in CPU mode. Adreno 650 is also excluded from the GPU-restore prompt. Its fixes remain in source, but the retained static Turnip engine does not contain them; existing Adreno 650 users with a saved GPU setting must select CPU in Settings. A first install reporting less than 8 GiB (rounded) selects Q5, 4096 context, Gentle mode and stage unloading. Device defaults now run before UI controls are populated. Existing preferences are retained on upgrade. These defaults reduce memory demand; they do not guarantee that the model fits.

## Checks performed

- NDK r30 Android 28 ARM64 build completed for the server, JNI engine, all seven CPU modules, shared GGML cores and system Vulkan module. The app's Java compilation, DEX conversion and APK signing completed.
- The packaged APK passed dependency checks and 16 KB load-segment alignment inspection for all **31 ARM64 ELF files**. The separate Hexagon DSP payload is not an ARM64 library. APK size is 137,654,209 bytes (about 131.3 MiB), SHA-256 `9eca440548b570f49cbe256a25d3376d936bd9fc2d76ca7b710296827ebac1c9`.
- The signing certificate matches the S20 build, requested permissions are unchanged, and all 20 pre-existing native files are byte-identical. The packaged C++ runtime has the same NDK build ID and identical allocated sections as the portable build's NDK runtime; stripping/file layout differ.
- Compiler command inspection confirmed baseline ARMv8-A in the backend registry, baseline CPU math and all seven feature-scoring objects; feature scoring also has `-fno-lto` to keep it separate from optimized kernels.
- On the S20 SM-G988W / Android 13, `portable-runtime-test.cpp` passed **30 matrix checks**: F32, F16, Q5_K, Q6_K and Q8_0, each with one/eight columns, in three backend-directory configurations. Results compare with a scalar reference after dequantization. Maximum observed absolute error was about 0.000219.
- With only the baseline module available, selection was `libkycpu-android_armv8.0_1.so`. With all variants available, selection was `libkycpu-android_armv8.2_2.so`, matching this phone's dot-product and FP16 capability. Higher-ISA modules did not cause a startup fault while being scored.
- Each run loaded the JNI library with `RTLD_NOW` and found the JNI entry point, validating native dependencies and symbol export. This does **not** test the Java service/JNI call under an installed app's linker namespace.
- CPU mode loaded no Vulkan or Turnip library according to `/proc/self/maps`, including a run where the real Vulkan module was present in the backend directory. Merely keeping a GPU module installed cannot force GPU initialization.
- The three native checks took under 0.1 seconds each including their ADB round trip, at low process priority, without loading models. They ran during the separate S20 60-second-song experiment; the running engine, installed APK and experiment configuration were not changed.

Selected logs and package inspection are committed under [testing/android-portable](testing/android-portable/); full local artifacts are retained in the maintainer backup. The S20 length ladder continues to use the earlier validated static CPU engine, so its song timings are **not** portable-engine song validation.

## Repeatable checks

`engine/build-portable-android.sh` also compiles `portable-runtime-test`. On a test device, place it, the portable libraries and the matching `libc++_shared.so` together. Create a backend directory with `libggml-cpu-<variant>.so` symlinks to the matching `libkycpu-<variant>.so` files. Run:

```sh
LD_LIBRARY_PATH=/path/to/libs /path/to/libs/portable-runtime-test \
  /path/to/backends /path/to/libs/libyueengine_portable.so
```

Repeat with only the baseline CPU module, all CPU modules, and all CPU modules plus `libggml-vulkan.so` linked to `libkyvulkan.so`. The test explicitly disables Vulkan in every case; **the third case is GPU isolation, not system-GPU computation**.

Inspect the actual packaged APK, including dependencies and every ARM64 load segment:

```sh
python3 tools/check-android-runtime.py app/out/killa-yue.apk > apk-runtime-audit.json
```

## Required before broader support claims

1. Install and run both child-process and JNI engine modes on actual older ARMv8-A hardware, then generate and listen to a short song. Running the baseline module on a newer CPU does not prove absence of every unsupported instruction on an older CPU.
2. Test the Android UI defaults, switch CPU/system/Turnip modes, upgrade/relaunch, and verify app linker namespaces and background-service survival. This APK has deliberately not replaced the S20 APK while its long test is running.
3. On each Pixel, Motorola, Exynos/Xclipse, Mali or Imagination candidate, test system-Vulkan math, quantized weights, attention, stage timing and a real song before treating GPU support as verified. Enumeration alone is insufficient.
4. Run on a real or emulated 16 KB-page Android environment. ELF alignment inspection is necessary but cannot substitute for launch and model execution on that environment.
5. Measure free RAM and sustained temperatures on 3/4/6/8 GB devices. The current multi-billion-parameter model may be impractical on entry-tier phones. Automatic memory planning and app-integrated thermal pacing remain future work; the existing external experiment guard is not an app feature.
6. Port/audit the optional AI writer, whisper transcription, cover and stem engines separately. This change does not make those features portable merely because the song engine now has a baseline path.

No new app permission, analytics, automatic device-report upload or remote song generation is introduced.
