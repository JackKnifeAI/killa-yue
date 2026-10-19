# Desktop Lab validation

This is a separate preview stacked on the community PR. The phone application,
generation engine, backend selection and running ops community host are not
replaced. Only a guide paragraph is added to Android; this milestone is a
desktop diagnostic tool and a foundation for fixed local worker jobs.

## Current evidence

| Environment | Observed result | What it establishes |
| --- | --- | --- |
| Ops, Linux x86-64, Core i5-8500 / UHD 630 | CPU smoke test passes; all eight GGML CPU cases pass; Vulkan loader returns `ERROR_INCOMPATIBLE_DRIVER` | CPU fixture correctness on this machine; no working Vulkan driver exposed to this process |
| This computer, Linux x86-64, GeForce 9400M | CPU smoke test passes; Mesa llvmpipe Vulkan F32 test passes | Supervisor and shader work on a software Vulkan implementation; **not** acceleration on the physical NVIDIA GPU |

The Vulkan test checks 16 rows × 256 inner elements × 8 columns, compares
against a double-precision reference, verifies input preservation and rejects
non-finite/unwritten output. This establishes only a small F32 operation.
The copied GGML fixture is byte-identical to the one in compatibility PR #38;
its CPU/Vulkan backends are explicit and it stops on the first unsupported or
incorrect result. No music model is loaded in either test.

Eight Rust tests cover reference rejection of NaN/missing/corrupt results,
incomplete/duplicate GGML records, actual child crashes, timeouts, active and
queued cancellation, output flooding, private file permissions, no overwrite,
and persistent result/journal creation. These pass on ops and this computer.
Clippy is run with warnings as errors. A slow-disk failure in initial testing
was fixed: raw log writes no longer fsync every 4 KiB, while the job journal and
finished results retain explicit sync. Lost log tails after power loss remain
possible; an unfinished journal entry is not a pass.

## CI and packaging

The Desktop Lab workflow builds/tests/packages Linux, Windows and macOS,
checks the default privacy boundary, runs the real baseline GGML CPU fixture
on Linux and exercises the Vulkan shader on Linux's software driver. The
existing Android/community/network CI runs alongside it. See the PR checks for
the exact revision and final results; cross-platform GPU/thermal qualification
is not inferred from hosted CI.

Archives include the platform-labelled executable, instructions, Cargo.lock,
dependency license texts, build provenance and SHA-256 checksums. A diagnostic
run records its executable and optional local engine-library hashes separately.
Neither package hashes nor a test report authenticate an untrusted worker.

## Remaining release gates

- Physical GPU GGML tests and full-model runs with measured memory and thermal
  behavior. Ops currently lacks an available Vulkan driver in this environment.
- Windows/Apple physical-device validation, accessible desktop UI and native
  CUDA/Metal implementations. Vulkan through a translation layer is not native
  Metal qualification.
- Engine/model provenance and license validation, resource admission and
  process isolation suitable for larger jobs. The existing ops service CPU/RAM
  limits remain in force; these local diagnostic subprocesses do not inherit
  that separate service's cgroup automatically.
- Fixed authenticated remote job schemas, explicit owner opt-in, per-job sender
  consent for prompts/audio, bounded storage, cancellation, result signatures,
  retries and multiple-node tests before advertising volunteer generation.

There is no remote diagnostic or generation listener, no auto-upload, no
installed driver change, no new APK, and no stable release in this milestone.
