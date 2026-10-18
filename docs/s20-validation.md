# S20 compatibility validation

Local build: `1.5.0-s20.1`, version code 18, branch `fix/s20-adreno650`.
Tests performed on 17–18 October 2026. This is an experimental device-specific
build, not a published release.

## Device and scope

- Samsung Galaxy S20 Ultra SM-G988W, Snapdragon 865, Adreno 650v3, Android 13,
  approximately 12 GB installed RAM; Samsung DeX on display 2.
- Bundled Mesa Turnip driver: 128 MiB `maxStorageBufferRange`, 32 KiB shared
  memory, no matrix cores. The system driver was inspected but not replaced.
- Q5_K_M YuE2 3B model and F32 VAE. Other model quantizations, Exynos S20
  variants, long songs, adapters and multiple variations have not been validated.
- The separate cover-generation, lyric-recognition and Studio AI engines are
  unchanged. This patch series addresses the song engine only.

## Reproduced failures and fixes

| Failure | Local evidence | Change |
| --- | --- | --- |
| Startup abort on `model.embed_tokens.weight`, operation `NONE` | A roughly 296 MiB Q6_K tensor exceeds the 128 MiB descriptor range; five metadata-only operations were incorrectly rejected | Accept metadata-only operations before compute-size checks; oversized ADD/RMS_NORM remain rejected |
| Vulkan device lost on small multi-column products | Direct F32 matrix tests failed at 2–8 columns; single-column and general matrix paths worked | Use the general matrix shader for those batches on Turnip Adreno 650 |
| Incorrect Q6_K GPU results | Nonconstant matrix and row-dequantization tests disagreed with CPU references | Reject raw Q6_K GPU computation; dequantize supported matrix weights on CPU to F16 when loading this GPU |
| Short/4096 context abort | Requested 4096 output tokens plus prompt exceeded the context | Reserve the longest prompt and an end token, then clamp the output budget |
| Slow GPU score/token generation | Resident CPU fallback weights avoided repeated large transfers, but this stage remained slower than CPU | Run LM and KV stages on CPU on this device, with separate backend lifetimes; render on Vulkan |

The closest upstream report is [whisper.cpp #3777 by Zuzutus](https://github.com/ggml-org/whisper.cpp/issues/3777),
opened 26 April 2026. It describes the shared GGML metadata-size guard and
reproduces it on AMD/Dozen under WSL2. It is not a Killa YuE user report or an
S20-specific report. The issue was closed as stale, not as a verified fix.
The additional shader failures above were reproduced locally on this phone.

## Weight conversion and memory

The on-disk GGUF is unchanged. The loader expands supported quantized matrices
to F16 once, using approximately 6 MiB of temporary conversion buffers. Matrices
whose expanded size is unsupported stay on CPU. NAR weights in the tested Q5
model occupy about 2698 MiB after expansion. This increases resident memory;
leave **Keep everything loaded** off and use **Short songs (4096)**.

Reference tests compare uploaded F16 data exactly with CPU dequantization,
including partial/fused weight copies. Buffer accounting includes both CPU and
GPU allocations, and three load/free cycles check cleanup.

### Why Q5 was expanded too

Q5_K did **not** show the wrong-result failure seen with Q6_K. Its direct
nonconstant reference checks passed. Expanding all supported quantized matrices
was a performance experiment and a uniform loader policy, not proof that Q5
needed a correctness workaround.

The recorded 2048-by-6144 matrix with 250 input columns took 384/206/2293 ms for
Q5_K and 2448/77/74 ms for F16 across three runs; both matched the reference.
These were constant-input microbenchmarks on a thermally variable phone, so
shader warm-up and throttling prevent a reliable general speedup claim. The
compressed song render's first two steps took 145.3 and 142.3 seconds; the
F16-expanded eight-step render averaged about 126 seconds per step. The compressed
run was stopped, so there is no complete matched song A/B measurement.

The cost is real: expanded NAR weights occupied about 2698 MiB. Retaining Q5 and
expanding only Q6_K is a sensible memory-saving follow-up, requiring a complete
render and listened comparison before claiming it solves long-song GPU support.
No such memory estimate should be presented as measured; Q5_K_M also contains
mixed tensor types. CPU mode does not perform this Adreno-only F16 expansion.
The current 1.6.1 candidate defaults new Adreno 650 installs to CPU because the
retained static GPU engine does not include the prototype fixes.

## Verification completed

- Original metadata regression reproduced, then all five metadata cases passed.
  Oversized compute operations still rejected.
- Direct Vulkan F32, F16 and Q5_K products at 1, 2, 4, 8, 16 and 64 columns
  compared with a scalar CPU reference; model-sized matrix checks also passed.
  These tests cannot silently run through CPU fallback.
- Raw Q6_K rejection checked separately from F16 conversion/readback.
- KV row writes with both I32 and I64 indices checked for correct written rows
  and unchanged surrounding rows.
- Stage routing, simultaneous CPU/GPU backend caches, reference lifetimes and
  explicit all-CPU mode checked across three cycles.
- Context budget boundary and overflow tests passed with AddressSanitizer and
  UndefinedBehaviorSanitizer.
- Full patch series applies to the recorded engine base. Both the executable
  and JNI library build; the APK is signed with the same certificate as 1.5.0.
  Only the two song-engine native payloads differ from the release APK.

## Performance observations

These are individual runs on a phone reporting GPU throttling, not a controlled
thermal benchmark. Gentle mode was enabled with two CPU threads.

| Path | Observation |
| --- | --- |
| Stock 1.5.0 CPU, Q5, 8192 context, 10 seconds, 8 steps | Full pipeline 717.9 seconds, plus about 1.6 seconds MP3 encoding; NAR 473.0 seconds and VAE 42.2 seconds |
| Patched CPU score/token stage, 4096 context | Score 84.9 seconds; semantic tokens 103.9 seconds; 497 score tokens and 250 semantic frames |
| GPU with compressed weights | First render steps 145.3 and 142.3 seconds; stopped for optimization |
| GPU with F16-expanded weights, same saved composition, 4096 context | Eight steps completed: NAR 1007.2 seconds, VAE 17.3 seconds; replay pipeline 1112.3 seconds plus 0.8 seconds MP3 encoding. This replay timing excludes score/token generation but includes rebuilding its KV cache. |

The eight-step GPU result decodes as 48 kHz stereo MP3, duration 10.008 seconds.
All 16000 saved latent values and all decoded audio samples are finite. Decoded
peak is 0.9996 and RMS is 0.1527. This verifies usable, non-silent audio data;
musical quality and physical speaker/headphone routing require listening.

Disabling flash attention with `--no-fa` reached the render graph, then the
standalone server exited before reporting a completed step. Its log did not
identify the cause, and the captured Android log contained no matching kill or
GPU-fault explanation. The installed build retains flash attention, which
completed all eight steps.

Application installation succeeded with `adb install -r`; version and signing
certificate were verified, and recorded permission grants were unchanged.
The installed DeX app completed a one-step re-render through its normal UI and
saved it in the Library: 218.2 seconds plus 0.7 seconds MP3 encoding. Its log
confirmed Vulkan0 rendering and the 4096-token preset. The saved 10.008-second,
48 kHz stereo MP3 decoded without errors, with finite samples, peak 0.9992 and
RMS 0.1280. This is a workflow smoke test, not an eight-step quality comparison.
The eight-step native result was also added to the Library; its playback timer
advanced in DeX. Physical audio routing and listening quality were not assessed.

The engine was stopped after validation. CPU is selected for ordinary use
because it was faster in these tests; experimental GPU mode remains available.
Q5, Short/4096, Gentle mode and unloading between stages remain selected.
The Create form's render-step setting was restored to 8 after the smoke test.

GPU mode currently remains slower than CPU for this short-song workload. The UI
uses the neutral label **CPU** and displays an Adreno 650 compatibility note.

## Reproduction and rollback

App base: `2e569e8ca7694a5b2186024efa149729ca66a138` (v1.5.0).
Local engine base: `9599fbb8244186a3226f24fd4b34d73c605732dc`, with the preview
patch followed by the four compatibility patches described in [BUILDING.md](../BUILDING.md).
The engine base already contains the gentle, JNI and resume changes.

Install over the existing app with `adb -s DEVICE install -r APK`. Version code
18 is retained so the original signed 1.5.0 APK can also be reinstalled with `-r`
without removing app data. Use **Engine → CPU** to bypass GPU mode immediately.
No Android firmware, kernel or system GPU-driver update is involved.
