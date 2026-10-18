# Library / Discover preview validation

Date: 2026-10-18 (UTC). Branch: `ops/hammer-discover-security`, based on PR #32 at `14c8c9e4a7a4623c510b60d36f1e72744b4755bf`.

## Passed on ops

- Full existing app logic regression suite (`bash app/test/run.sh`).
- Android resources, Java and DEX (`app/check.sh`).
- Linux native build and real pinned-TLS integration: **138 assertions**. Includes unpublished-file access, path traversal, private and cross-owner artwork, stale publisher cover authorization, forged profiles, withdrawn portraits, expiring directory announcements, source-address validation and admission budgets, plus the existing streaming/download/failover/push cases.
- Latest host run: first two-second Opus segment 24,801 bytes, 78 ms including loopback TLS; loopback push 2 ms. These are local measurements, not internet latency guarantees.
- Native AddressSanitizer / UndefinedBehaviorSanitizer with leak detection: duplicated descriptor survives caller close, descriptor encode/decode round trip, and **10,000 mutated, truncated or random packet inputs** passed. The harness uses non-PIE linking because the initial PIE executable collided with ASan shadow mapping on this host. This is targeted testing, not exhaustive fuzzing or an independent audit.

## Preliminary S25 Ultra checks

An earlier preview in this work session passed 135 real-device protocol assertions through the isolated codec worker (76 ms for the first two-second Opus segment including loopback TLS). The isolated worker used a different Android UID from the main app.

The Android document picker imported a synthetic three-second WAV into Library with local-only visibility. A synthetic artist profile and uploaded JPEG were explicitly published; a separate ops client discovered the phone through NSD, authenticated it with pinned TLS, read the signed profile and verified that the served JPEG omitted the source Exif marker. The public song catalog remained empty before explicit song publication.

These preliminary checks precede the final worker-session and image-pixel IPC changes. They must not be counted as a final pass for the current APK.

## Pending before review completion

The phone stopped accepting its supplied wireless-debugging connection while validation was underway. Remaining checks:

- Install final preview and retrieve a fresh on-device integration result.
- Exercise Library visibility transitions, generated-cover reuse, profile upload and rendering through the final worker IPC.
- Discover another host automatically; open its artist page, stream in each quality mode, download into Library and rehost with original identity.
- Verify cancellation/worker restart, idle service shutdown and temperature after testing.
- Remove only synthetic QA imports/profile/publications and compare the pre-test manifest of 29 original Library files. That manifest stays on ops, outside Git.

No production release or merge is implied by this record. The broader PR #32 device report in `VALIDATION.md` applies to its earlier implementation. Worldwide discovery/bootstrap, NAT traversal, relays, non-Linux portability, desktop decoder isolation, sustained hostile load and long unplugged/Doze tests remain outstanding.
