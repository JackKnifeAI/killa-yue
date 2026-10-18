# Library / Discover preview validation

Date: 2026-10-18 (UTC). Branch: `ops/hammer-discover-security`, based on PR #32 at `14c8c9e4a7a4623c510b60d36f1e72744b4755bf`.

## Passed on ops

- Full existing app logic regression suite (`bash app/test/run.sh`).
- Android resources, Java and DEX (`app/check.sh`).
- Linux native build and real pinned-TLS integration: **138 assertions**. Includes unpublished-file access, path traversal, private and cross-owner artwork, stale publisher cover authorization, forged profiles, withdrawn portraits, expiring directory announcements, source-address validation and admission budgets, plus the existing streaming/download/failover/push cases.
- Latest host run: first two-second Opus segment 24,801 bytes, 78 ms including loopback TLS; loopback push 2 ms. These are local measurements, not internet latency guarantees.
- Native AddressSanitizer / UndefinedBehaviorSanitizer with leak detection: duplicated descriptor survives caller close, descriptor encode/decode round trip, and **10,000 mutated, truncated or random packet inputs** passed. The harness uses non-PIE linking because the initial PIE executable collided with ASan shadow mapping on this host. This is targeted testing, not exhaustive fuzzing or an independent audit.

## Passed on the S25 Ultra (Android 16)

- **138 real-device protocol assertions** using permissionless isolated codec workers. Latest run: first two-second Opus segment 24,801 bytes, 92 ms including loopback TLS; push 3 ms.
- New real `MediaPlayer` regression exercises Auto, Original and Data saver while polling duration/position during asynchronous preparation, then verifies advancing playback, pause, seek, resume and stop. It caught an actual startup race: querying duration before preparation completed raised Android error -38. Player controls now wait for the prepared callback.
- A synthetic WAV entered Library through the document picker with local-only visibility. Public → private → local changes were exercised from the Library sharing badge. A separate TLS client saw only the selected test song and was denied access by its known hash after withdrawal/private selection.
- Generated-cover reuse and profile image upload both used the final isolated-worker image IPC. Public JPEG output was 384×298 with the source Exif marker removed. Artist pages without artwork no longer reserve blank image space.
- Phone publication was found by ops over NSD. The phone found a separate desktop host through a **lab mDNS advertiser**, opened its signed artist page and song menu, and streamed the remote test WAV to completion. The companion CLI does not yet advertise itself through native desktop mDNS; this fixture validates Android discovery and authenticated connection, not automatic companion setup.
- Download completed into Library, with a full audio SHA-256 match. Rehosting from Library preserved the original desktop publisher key, signature and bytes. The download metadata points at the Library file; no second permanent audio copy was retained in the private download directory.
- Artist-page Follow completed and its test artist/host were subsequently removed. Live Follow was returned to its original off state. Broader notification delivery tests remain in the parent PR's `VALIDATION.md`.
- Both synthetic Library entries, test publications/profile/follow, five isolated test directories and the lab server/advertisement were cleaned up. **All 29 original Library files matched the pre-test SHA-256 manifest.** That manifest stays on ops, outside Git.

The test APK contains exported test activities and is not a release artifact. Tests use synthetic music; they are not listening-quality judgments. No generation ran on the phone during this pass.

## Remaining work

Worldwide bootstrap, NAT traversal, end-to-end encrypted relays, a scalable public search index, automatic companion mDNS advertisement, desktop decoder isolation, other Android vendors, sustained hostile load, independent security review and long unplugged/Doze testing remain outstanding. No production release or merge is implied by this record.
