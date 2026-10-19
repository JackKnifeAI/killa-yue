# Community and desktop implementation plan

This branch builds on 1.6.1 without changing generation engines or GPU selection.
The compatibility lab and Studio work remain separate pull requests.

## First community milestone

- Host-side, signed conversation sidecars: track replies, optional timestamps,
  and remix references to signed tracks and public host cards. Audio bytes and
  existing publication signatures remain unchanged.
- Artist-owned boards under public artist profiles. Artists start threads;
  listeners reply. Hosts can block authors, and artists can hide, pin and lock.
- Explicit follower registration for circle invitations. Reuse the host's
  existing private-circle capability. Deliver invitations only after a signed
  recipient request over pinned TLS; never put capabilities in public metadata.
  Existing local-only following remains available without registering.
- Optional monthly headcount using an independent, rotating counting token.
  Count participating installations, not verified humans. No public identity,
  song activity, hardware identifiers or retained IP logs in the count store.
- Companion daemon on ops: persistent identity, Rust/libp2p connectivity,
  hosting, community metadata and bounded native audio transcoding. This is
  useful volunteer compute; it is not remote music generation.
- Preserve old protocol operations. New features use additional operations and
  fail with an explicit unavailable message on older hosts.

Validation must cover signatures, private/public boundaries, revocation,
spoofed invitations, replay/conflicting records, moderation, persistence,
monthly count rollover, old clients, Android compilation, and live ops probes.
No stable release or phone replacement is part of this branch's deployment.

## Desktop roadmap

1. **Operable companion:** daemon lifecycle, health reporting, disk/CPU/memory
   budgets, portable packaging, backup/recovery, clean shutdown and upgrade
   rollback. Verify the same community protocol on phones and computers.
2. **Rust desktop foundation:** retain the existing pinned `net` crate, define
   shared protocol fixtures, and build a Rust application core plus accessible
   desktop UI. Port storage/identity incrementally; Java compatibility remains
   until migration and cross-version tests pass. Choose the UI framework after
   measuring Linux, Windows and macOS packaging and accessibility.
3. **Hardware detection and measured support:** enumerate CPU/architecture,
   RAM, GPU and driver versions. Probe Vulkan, then platform-specific CUDA,
   Metal and other backends only where implemented. Run bounded correctness
   tests before enabling acceleration; retain CPU fallback. A GPU name alone
   never establishes compatibility. Publish tested configurations and unknowns.
4. **The complete music app:** generation, Library, playback, editing/Studio,
   artist pages and project interchange. Reuse engine interfaces and formats;
   do not promise feature parity until each workflow passes desktop tests.
5. **Volunteer generation worker:** fixed versioned task schemas, explicit
   owner opt-in, user-visible queue/cancel, model/license checks, GPU/VRAM and
   thermal limits, deadlines, scoped job directories and authenticated result
   hashes. No arbitrary shell commands or downloaded executable jobs. Private
   prompts/audio go to another person's machine only with specific consent;
   transport encryption cannot hide a job from its worker.
6. **Scheduling and availability:** capability advertisements, admission and
   abuse controls, retry/idempotency, unreachable peers, cancellation and
   resumable results. Test multiple volunteer nodes before advertising a
   reliable worldwide service. Replication needs explicit storage consent.
7. **Platform release matrix:** Linux x86-64/arm64, Windows x86-64/arm64 and
   macOS Intel/Apple Silicon as build and hardware evidence becomes available.
   Signed packages, reproducible dependency manifests, upgrades/rollback and
   per-platform CI. “All hardware” is a direction, not a support claim.

## Deliberate boundaries

Private messaging needs its own end-to-end encryption and message-request
design. Private-circle access is a shared capability, not per-person DRM:
invited members can share it and retain downloaded copies. Revoking the circle
invalidates all old invitations. Public deletion cannot recall other copies.

Monthly installation tokens cannot prove unique people or prevent fabricated
identities. Counts cover the selected counting node and consenting clients;
counts from different nodes must never be added without deduplication.
Identity recovery and linked devices are future work; Android's existing host
identity is non-exportable in Keystore and must not be presented as recoverable.
