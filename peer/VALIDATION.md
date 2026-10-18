# HAMMER preview validation — 2026-10-18

Device: Samsung S25 Ultra SM-S938W, Android arm64. Companion: Linux x86-64 on the same LAN. These are functional preview results, not a cross-device guarantee or a claim of record-setting efficiency.

## Automated checks

- 106 real-TLS/native assertions pass on both desktop and phone: pin verification, private catalog/audio isolation, revocation, immediate subscription events, reconnect catch-up, cancellation, signed metadata, resume/checksum verification, native Opus, seeks, bounded cache and replica fallback.
- The Android harness executes subscription cancellation, disconnect and host shutdown on its UI thread. This reproduces the lifecycle that exposed Conscrypt's network-on-main-thread exception when closing a TLS socket. Cancellation now closes the underlying TCP socket; protocol traffic still goes through authenticated TLS.
- Last phone run: 2 ms loopback push; first two-second Opus segment 24,801 bytes and 69 ms including TLS. Loopback timing is not Android notification-display or WAN latency.
- Existing application regression suite: 880 numbered assertions plus PlayAlongTest pass. Android API 35 (CI) and API 37 compilation passed before these device fixes; CI reruns for each commit.
- Native WAV/FLAC/MP3 seek and Opus round trips pass. A standalone native harness exercised 10,000 malformed packets under ASan/UBSan with leak detection. The JVM sanitizer launch encountered a shadow-address conflict; the native executable was used instead.

## Actual phone UI and services

- Found and fixed Android AlertDialog messages hiding song/vote action lists. Track details and About votes are separate buttons; stream, download, follow and vote actions are visible.
- Auto, original-source and 48 kbps Data Saver playback reached the real MediaPlayer. Pause/resume and original-source seek were verified; a seek reached 9,467 ms in a 12,000 ms recording.
- With two known copies of a 90-second track, the original desktop host was stopped. Playback continued from the replica and sought to 67,333 ms. The native playback cache reported 3,000 KiB.
- Downloaded audio matched its original SHA-256. Saving to Library relocated the recording and wrote its metadata sidecar. The phone rehosted it; a desktop downloaded the verified original and read its comment and vote.
- A real high-importance release notification arrived with the correct artist/title. Tapping it opened the matching song actions after catalog refresh.
- A release published after switching the screen off generated a notification. Android subsequently reported Awake; this does not establish delivery during deep Doze.
- Re-enabling Live Follow notified about a release published while it was off. Restarting subscriptions with two peers did not crash after the TCP cancellation fix.
- With notification permission revoked for the test, Enable opened Android's app-notification settings and did not start services. Permission was restored afterward.
- Stop hosting disconnected a desktop subscriber without killing the app. Stop Live Follow released its wake lock. The final process had no crash records or running app services.
- Save desktop companion wrote a ZIP to Downloads; its hash matched the bundled asset. The extracted companion also passed two-process publish/download/rehost/comment/vote/live-event checks.

## Data and resource checks

All 53 original files matched their saved checksums after testing. Only synthetic lab music was published. Lab publications, peers, artist follow, downloaded test music and isolated fixture directories were removed; lab servers stopped. The final companion ZIP remains in the phone's Download folder.

A 30.15-second observation recorded 41 application CPU ticks with two subscriptions and a release event. The phone was plugged in, and the notification woke the display. This is not a sustained idle or battery-drain benchmark. Extended unplugged, deep-Doze, network-switching and other-device testing remain, along with community listening checks.

The installed preview is a signed test APK and includes test activities. It must not be published as the release APK. The published v1.5.0 release is unchanged.
