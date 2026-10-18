# Android device support: integration and release review

Prepared for Claudia and maintainers, 18 October 2026. This PR targets `main` after
1.6.0, at `cae47631be0c1f7b24b5dafeaa6cbe0ba7fac270` (release PR #36).
It prepares release candidate `1.6.1`, version code 20, for review and device testing.
It does not publish a release or claim all Android devices can generate music.

## What was integrated from upstream

- Discover, artist pages, imported audio, HAMMER hosting/rehosting, signed
  recordings, private circles, streaming, downloads and Live Follow remain present.
- The libp2p node, DHT discovery, relay/hole-punching implementation, JNI network
  library packaging and desktop-companion packaging remain upstream code.
- The Settings tab name, six-tab navigation, and new three-render-step default
  are retained. The S20 experiment's explicit eight-step requests are benchmark
  settings and do not change the app's default.
- Existing manifest components and permissions are retained. This PR adds no
  permission or automatic report upload relative to 1.6.0.

The older `1.5.0-android.1` prototype is **not** an integrated 1.6.0 release APK.
Its hashes and native-library comparisons remain useful evidence, but must not
be represented as validation of the new network-enabled package.

## How existing GPU support is protected

| Risk | Protection in this PR | What still needs verification |
|---|---|---|
| Existing Adreno users silently switch engines | An absent driver preference retains Turnip for the same Adreno heuristic as upstream. New Adreno 650 installs select CPU, and the GPU-restore prompt excludes them; other Adreno defaults and existing preferences are unchanged. `EngineChoice` directs both child and JNI launches to the existing static engine for GPU + Turnip. | On-device upgrade and full songs on the supported S25/Snapdragon configuration. The heuristic is not a certification of every Adreno model. |
| New backends are accidentally loaded by the legacy engine | Portable cores/modules use private names (`libkyggml*`, `libkycpu-*`, `libkyvulkan.so`). Only explicit private backend directories expose GGML's expected module names. APK inspection rejects leaked generic GGML filenames. | Audit the final integrated APK and test both launch modes. |
| CPU launch crashes inside an unsupported GPU driver | Portable core and CPU modules do not link Vulkan. CPU sets `GGML_DISABLE_VULKAN`; dynamic backend discovery respects it. Turnip environment and loader paths are installed only for bundled GPU mode. | Actual older ARM64 phones and app linker namespaces, beyond the standalone native test. |
| An S20 workaround changes every GPU's math or stage choice | Batched-matvec/Q6_K workarounds check Qualcomm + Mesa Turnip + Adreno 650. CPU AR/KV and F16 expansion check the exact `Turnip Adreno (TM) 650` backend description. Other GPUs retain their existing stage selection. | Numerical tests and complete songs on Adreno 830 and another supported GPU; do not infer those from S20 results. |
| Generic fixes affect other devices | The metadata-buffer exception is gated to Turnip Adreno 650 and non-computing metadata operations; other GPUs retain the original checks and real oversized computation is still rejected. Context clipping reserves prompt/end-token space. | Model/shape coverage outside the tested examples. Context edge cases run under sanitizers in CI. |
| A new driver is enabled automatically without evidence | Non-bundled phones default to CPU. System Vulkan is an explicit experimental choice, with a visible CPU option. There is no new whole-brand GPU whitelist. | Validate system-GPU math, quantization, attention and songs per device/driver before promoting it. |

The prototype APK preserved all 20 pre-existing native files byte for byte when
adding the portable libraries. That comparison was against the S20 compatibility
build, **not** every public 1.6.0 native artifact. It is not a zero-regression
guarantee. The maintainer's final APK build must repeat the audit and compare
the intended legacy artifacts before release.

## Review follow-up: S20 stopgap and release scope

Claudia's routing finding was correct: GPU + Turnip uses the retained static
engine, while the portable engine runs CPU or system Vulkan. The S20 prototype's
successful GPU runs do not validate that retained static engine. This revision
chooses review option **(b)**: new Adreno 650 installs use CPU, and the existing
GPU-restore prompt cannot recommend changing them back. Other Adreno defaults,
including the S25's, are unchanged. Already-saved preferences are preserved;
an Adreno 650 upgrade with GPU selected must switch to CPU in Settings.
The UI explicitly identifies the known GPU failures in this build.

The metadata exception now checks Qualcomm + Mesa Turnip + Adreno 650 before
accepting oversized metadata operations. This narrows the source patch even
though the retained static engine is not rebuilt here. Integrating those fixes
into the static engine remains a separately tested follow-up.

The release version is **1.6.1 / code 20**. Unverified capabilities must stay
explicit in release notes: full-length portable-engine songs; other Adreno 6xx,
Exynos, Mali, Tensor and older/budget devices; and adapters. The S20 length ladder
has completed 30/60/120/180-second songs on the earlier static CPU engine; its
210-second run is still in progress at this review update. These results do not
close the portable-engine or integrated-APK gates.

For Alexander's Q5/F16 question, see the [measured tradeoff](s20-validation.md#why-q5-was-expanded-too).
Q5 passed the tested math cases; expanding all supported quantized matrices was
a performance experiment, not a measured Q5 correctness repair. Q6-only unpacking
is a reasonable next experiment but has not completed an end-to-end comparison.

## How device coverage expands

The previous song CPU build required ARM dot-product and FP16 extensions.
The portable engine compiles its glue and baseline kernels for ARMv8-A and
packages seven GGML CPU variants. Runtime feature scoring selects a compatible
variant; feature-scoring objects remain baseline code without LTO. The S20
selected the expected dot-product/FP16 variant, while explicitly restricting it
to the baseline also passed. This does not substitute for an actual older CPU.

A separate system-Vulkan module can exercise the manufacturer's driver on
Tensor, Mali, Xclipse, Imagination and other candidates. Enumeration is not
proof of correct music generation. [The reach plan](android-reach-plan.md)
prioritizes high-volume budget and mainstream phones alongside flagships and
records regional/SKU differences instead of treating each brand as one device.

First-install memory defaults select Q5, 4096 context, Gentle mode and stage
unloading below 8 GiB of reported rounded total memory. Defaults run before UI
controls read them; upgrades preserve existing preferences. This is a first
reduction in memory demand, not an available-RAM planner. The current model may
still be impractical on 3/4 GB devices. Optional AI writing, transcription,
cover generation and stem engines need their own ISA/driver audits.

## Participation without generation

This already exists in 1.6.0. People can listen in Discover, import recordings,
host their Library, or rehost permitted verified downloads without the YuE model
or a generation-capable GPU. The first-launch explanation and README now point
users toward those paths. There is no new model-readiness gate on HAMMER or
Discover. [HAMMER documentation](../peer/README.md) and the
[network design](designs/2026-10-18-worldwide-network.md) describe the upstream
behavior and privacy boundaries.

The relay-helper eligibility check is unchanged: it uses unmetered connectivity,
charging and `!MainActivity.jobBusy`. Its comment describes Wi-Fi, but the actual
check is metering, not transport type. It is evaluated when starting the node;
this PR does not claim continuous re-evaluation after connectivity, charging or
generation-state changes. That lifecycle behavior deserves separate upstream
review before promising an always-enforced power/network policy.

P2P hosting transports selected audio and metadata to other peers; it is not
remote generation and does not remove physical-device/network validation needs.
The new portable song libraries are not a reason to require model downloads for
listening or hosting. Keep that distinction in onboarding and release notes.

## Source and native-upstream status

Public native sources are pinned to yue2.cpp
`83b65f5d6bf4806557ef17fe763021ff4eead928` and its GGML submodule
`2eddaf94a8cdbbf1c30017d28313cd661a9da543`. Run
`engine/prepare-mobile-source.sh` on a clean checkout with that submodule
initialized; it applies all nine patches in a checked order. The earlier local
snapshot `9599fbb` already contained gentle/JNI/resume changes and is not needed
to reproduce the public source tree.

Native upstream was also checked: yue2.cpp master
`8af01740686824ffd4a1839a457954f42e2a8ce7` is three commits ahead of this pin,
changing its GGML submodule. This PR integrates the latest **app** release; it
does not silently replace the tested GGML revision with those newer kernels.
Review that upstream delta and repeat numerical/device tests before moving the
native pin. In particular, new GPU support in a GGML update must not be assumed
to validate our bundled Turnip stack or system-driver path automatically.

## Evidence and CI

- [S20 GPU record](s20-validation.md): reproduced driver/math failures, scoped
  fixes, short-song and installed-app smoke tests, limitations and rollback.
- [Portable runtime record](android-runtime.md): 30 matrix checks, JNI native
  dependency loading, baseline/automatic CPU selection and GPU isolation.
- [Committed prototype evidence](testing/android-portable/): actual CPU test
  output, durations, APK dependencies and ELF alignment inspection. Those
  package results belong to the pre-integration prototype.
- App CI runs the existing plain-Java regression suite, including 46 routing/default-policy
  assertions, then compiles resources, all Android Java and DEX.
- HAMMER CI runs encrypted-peer/native-audio tests, malformed-media/descriptor
  sanitizer checks and desktop-companion packaging. This PR adds the upstream
  libp2p network build and its same-network, DHT and relay scenarios to CI.
- Mobile CI applies the complete patch stack to clean pinned public source,
  runs context-limit tests under ASan/UBSan, and checks helper-script syntax.
- The repository's no-native-binaries/keys/local-settings check remains enabled.

CI results live on the PR checks for the actual pushed commit. Passing host CI
does not mean Android GPU correctness, full native Android packaging or phone
thermals passed. No production signing keys, native binaries or APKs are added
to the repository by this PR.

## Release gates for review

- [ ] Claudia reviews routing, S20-specific guards, public patch reproducibility
      and the retained P2P paths.
- [ ] Required CI passes for the final PR head after any upstream merge.
- [ ] Build the complete 1.6-based APK with portable engine, existing supported
      Turnip engine, `libhammer`, `libkillanet` and desktop companion. Audit all
      ARM64 ELF segments/dependencies, signature, permissions and native hashes.
- [ ] **Claudia owns the S25 device gate** (confirmed by the maintainer): upgrade
      the signed 1.6.0 APK to this exact 1.6.1 candidate without clearing data,
      verify preferences carry over, and make a full song on GPU + Turnip.
      Listen by ear and record the APK hash, settings, elapsed time and audio
      assessment. Also compare CPU/GPU launches and both launch modes. This
      remains open until the device results are recorded; CI cannot close it.
- [ ] Finish the S20 30/60/120/180/210-second ladder, then test the integrated
      APK. Native-harness results are not app-service survival results.
- [ ] On at least one non-Adreno phone and an older/budget ARM64 device, test
      launch, baseline CPU math and a short song if memory permits. Record
      physical RAM, free RAM, OS, driver, actual duration and listened quality.
- [ ] With no generation model installed, test Discover, import, playback,
      hosting/rehosting, private circles, Live Follow and companion transfer.
      Retest across two real networks/carriers; local relay CI is not that test.
- [ ] Test real 16 KB Android runtime behavior; aligned ELF files alone are
      insufficient.
- [ ] Publish only the device/backend capabilities actually verified. System
      Vulkan and untested hardware stay experimental; native-upstream kernel
      updates need their own regression evidence before changing the pin.

Merge and release remain separate decisions. The PR is a review and test
handoff; no release tag, publication or automatic merge is requested here.
