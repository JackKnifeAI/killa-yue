# Killa Desktop Lab — preview

A Rust foundation for the desktop companion: local hardware discovery, bounded
driver tests and durable diagnostic jobs. It works alongside the existing Java
HAMMER companion. It does not replace that host, run music generation, accept
remote jobs, or change Android/desktop engine selection.

## Run

Extract the archive for your operating system and open a terminal there:

```
./killa-desktop diagnose --output first-report
./killa-desktop diagnose --output gpu-report --vulkan-compute
./killa-desktop capabilities
```

On Windows use `killa-desktop.exe`. Choose a **new directory** for each run.
The first command collects CPU model/features, OS/kernel version, host RAM and
Vulkan device/driver details, and runs a tiny CPU matrix calculation. Linux also
reads DRM vendor/device IDs and the kernel driver name, even if Vulkan fails.
Other operating systems currently discover GPUs through Vulkan only. Missing
Vulkan is **unavailable**, not evidence that the computer has no GPU.

`--vulkan-compute` is off by default. It explicitly runs one small F32 matrix
shader on each enumerated adapter, up to eight. It uses 25,088 bytes of buffer
data, with a 4 MiB allocation ceiling, a 10-second Vulkan fence wait and a
15-second child-process deadline. A software Vulkan adapter is labelled as
software and never described as GPU acceleration. Results identify the adapter
used by that test; enumeration indices are not persistent device identities.

There is no network client, listener, telemetry or automatic upload in this
tool. It never downloads models or drivers. No serial number, UUID, hostname,
account, process list or private HAMMER identity is read. Diagnostic files do
contain hardware models and versions; raw driver logs can include local paths.
Review files before sharing them. POSIX output directories/files are 0700/0600;
on Windows use a directory restricted to your account by its inherited ACL.

## Read results and stop tests

- `report.json`: job outcomes and structured results. `completed` means the
  queue finished, not that all tests passed. `generation_compatibility` remains
  `not_tested`, including when every small test passes.
- `provenance.json`: SHA-256 of this diagnostic executable and any supplied
  engine fixture/libraries, without absolute paths. Hashes are identification,
  not signatures or proof that local files are trustworthy.
- `events.jsonl`: flushed journal of job starts/completions. An unmatched start
  after a power loss or parent crash means interrupted/unknown, never passed.
- One result JSON and stdout/stderr log per test. Each stream retains at most
  64 KiB and continues draining after that limit. Partial logs are flushed while
  a child runs. Truncated stdout cannot produce a pass.
  Journal/results are synced to disk; raw logs use the OS cache, so a power loss
  can lose their tail. A failed capture is recorded and never becomes a pass.

Press Ctrl+C once, or from another terminal:

```
./killa-desktop cancel gpu-report
```

The active process is terminated and queued jobs stop. Finished evidence stays
on disk. Rerun into a new directory for a retest. Closing a window or losing
power is not a clean cancellation; inspect the journal if the final report is
missing. Linux additionally kills a child when its parent exits. Unix process
groups include child helpers; Windows currently terminates the direct child
only. No remote diagnostic jobs are exposed with these limitations.

These are fixed small workloads, **not a security sandbox or a whole-process
memory/thermal limit**. A defective driver can affect the OS, and a kernel task
stuck in uninterruptible sleep may not obey a userspace deadline. Driver memory
and shader compilation may exceed the test buffer allocation. Keep service
CPU/RAM controls in place on shared machines. Larger generation jobs need their
own resource admission, isolation and thermal policy before implementation.

## Check the real engine math

The built-in F32 kernel checks a simple Vulkan path. It cannot establish that
GGML's quantized kernels or a complete song will work. The optional engine
fixture uses exactly `engine/compatibility-test.cpp` from the Android
compatibility Lab PR: F32, F16, Q5_K, Q6_K, at one and eight columns, with
upload/readback and a double-precision reference. It selects CPU or Vulkan0
explicitly, without scheduler fallback. CPU uses one thread. All eight started
and passing cases are required; a crash or incomplete output cannot pass.

On Linux, prepare the pinned engine using `engine/prepare-mobile-source.sh`,
then build a local bundle (CMake, g++ and Python 3 required):

```
bash desktop/build-engine-probe.sh /path/to/prepared/yue /path/to/build /path/to/new-bundle cpu
./killa-desktop diagnose --output engine-report --engine-dir /path/to/new-bundle
```

Use `vulkan` instead of `cpu` when building to include GGML Vulkan; it also
requires Vulkan development headers/libraries and the shader compiler expected
by the pinned GGML build. Add `--vulkan-compute` to diagnose to run Vulkan0.
The engine fixture gets a 30-second deadline. GGML's backend numbering is its
own; do not equate Vulkan0 with an inventory adapter without engine evidence.
This preview packages neither pretrained weights nor a music generation engine.

`--engine-dir` executes trusted local native code. Never point it at a bundle
sent by an unknown peer. The directory must contain `compatibility-test` and
`lib/`; the builder includes upstream licenses and a source/hash manifest.

## Build and test

```
cargo test --locked --manifest-path desktop/Cargo.toml
cargo clippy --locked --manifest-path desktop/Cargo.toml --all-targets -- -D warnings
python3 desktop/package.py out
```

Dependencies are pinned in Cargo.toml/Cargo.lock. Packaging includes their
license texts and hashes. Rust 1.85 or newer is required; CI tests the current
stable toolchain. The Vulkan loader is dynamically loaded; no Vulkan SDK is
needed to compile the Rust program. The embedded Vulkan 1.0 SPIR-V is generated
from `shaders/matrix.comp`; regenerate with `python3 desktop/shaders/compile.py`
(requires glslc) then `cargo fmt --manifest-path desktop/Cargo.toml`.

CI builds/tests/packages Linux, Windows and macOS. These are CLI/build tests,
not certification of physical GPUs on those platforms. Linux tests also cover
crash, timeout, cancellation, noisy output, file permissions and the real GGML
CPU fixture. The roadmap tracks UI/accessibility, native CUDA/Metal backends,
mobile parity and remote generation separately.

The Linux engine fixture builder disables optional x86 instruction sets instead
of assuming every computer has AVX2. This portable CPU fixture is for correctness,
not a benchmark of the optimized generation engine.

API references: [ash loader](https://docs.rs/ash/latest/ash/struct.Entry.html),
[Vulkan compute](https://docs.vulkan.org/tutorial/latest/11_Compute_Shader.html),
[selective CPU/memory refresh](https://docs.rs/sysinfo/0.33.1/sysinfo/struct.System.html).
