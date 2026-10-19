# HAMMER desktop companion

The separate [Rust Desktop Lab preview](../desktop/README.md) discovers hardware
and runs local driver/engine checks beside this companion. It has no network
listener and does not enable remote music generation. See the desktop roadmap
for the planned full Rust application and volunteer generation worker.

This computer can host the same signed recordings as Killa YuE phones. It serves
the existing master file, encodes compressed streams in memory, and exchanges
catalogs, audio, comments and votes through pinned TLS connections. No account,
tracker, analytics service or company-operated server is required.

From Killa YuE: Library → Sharing settings → Save desktop companion. Transfer the ZIP to
your computer, extract it, and open a terminal in `hammer-server`.

## Start

Install a Java 17 or newer JDK. On Linux x86-64, the app's ZIP includes the native
audio library built on the test machine. Run `sh run.sh --address YOUR_LAN_IP`.
If that library is incompatible with your distribution, build it locally below.

The default listener is port 8784 on all interfaces. `--bind 127.0.0.1` restricts
it to this computer. Set `--address` to the address your listeners can reach;
this does not configure a router or firewall. A phone hotspot or the same Wi-Fi
works without internet. Remote listeners need a reachable IPv6 address, a
forwarded port, or a network you control. In direct-address mode, carrier-grade NAT requires a reachable address or a VPN.
The optional Rust/libp2p mode below adds peer discovery and relay routing; actual
reachability depends on reachable peers. There is no mandatory relay provider.

Type `help` for commands. Paths and titles containing spaces must be quoted.

```
publish "/home/me/Music/master.wav" public "My new track"
card public
card private
```

Publishing through this CLI enables downloading and rehosting. `private` limits
discovery and access to holders of the private invitation. The application uses
one private circle per host. Send private invitations through a trusted channel.
Use `revoke` to replace the invitation and disconnect active peers.

The host reads the master in place. Moving, editing or deleting that file makes
the publication unavailable until you publish again. Comments and votes are
sidecar metadata and never change the audio hash. Up to 256 songs can be hosted.

## Listen, download and rehost

```
follow "hammer://HOST:8784/#PIN" "Friend's phone"
peers
catalog 0
download 0
rehost 0 public
comments 0
comment 0 "My display name" "Loved this recording"
vote 0 up
votes 0
watch 0
```

`watch` receives live encrypted publication events while connected. The desktop
terminal reports disconnection; run it again to reconnect. The Android Live
Follow service reconnects automatically and catches up. Its initial catalog is
a baseline, not a notification for every existing song.

Downloaded audio is verified with the signed original SHA-256 before it can be
hosted. Public comment and vote metadata accompanies the download; private
circle annotations are not republished. You can stop hosting without deleting
your recording. Other people keep copies already downloaded.

Original/lossless playback preserves the source bytes. An MP3 master is still
MP3 quality. Auto and Data Saver create Opus segments from the master, using the
same C++ pipeline on phone and desktop. No permanent compressed copy is stored.
Native playback caches at most 3 MiB, while the host's shared compressed-segment
cache is capped at 4 MiB. Temporary codec and transport buffers are additional.

Votes display no account name. Signed, per-track voting keys allow replicas to
deduplicate votes; a key is not proof of one person. Peer IP addresses and traffic
timing remain visible to connected peers and network operators. TLS protects
content in transit, not against a recipient recording or redistributing it.

## Local data and identity

The data directory defaults to `~/.hammer`; override it with `--data DIRECTORY`.
Keep it private: it contains the host identity, circle capability, followed peer
cards and voting keys. POSIX permissions restrict it to its owner. On Windows,
use a directory restricted to your own account. Back it up privately to preserve
the host identity. Never publish or send its key files.

## Build the native library

The source is included. Linux/macOS need a JDK, CMake, a C/C++ compiler, Python 3,
curl and tar. Run `bash build-native.sh`; the script verifies the pinned Opus
source archive before compiling. macOS and Windows builds require validation on
those operating systems; the initial device/desktop test targets are Android
arm64 and Linux x86-64.

Windows: install a JDK, CMake and Visual Studio C++ Build Tools. Fetch Opus 1.6.1
from `https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz`; verify SHA-256
`6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1` with
`Get-FileHash`, then extract into `vendor`. Configure
`cmake -S source/peer/native -B build-native -DOPUS_DIR=FULL_PATH_TO_OPUS`, build
with `cmake --build build-native --config Release`, and place `hammer.dll` from
the build into `native/windows-x86_64/`. Start `run.bat`.

Killa YuE is Apache-2.0. miniaudio is used under its MIT option (license in the
header). Opus is BSD-3-Clause; its license is included in its source archive and
the native package. These are audio implementations, not network services.

## Public artist profiles and entry peers

`profile "Artist name" "Public biography" "genres and tags"` publishes signed text metadata. `withdraw-profile` stops offering it on this host. Profiles downloaded alongside recordings preserve their original signing identity. A name is not a verified real-world identity.

`join "hammer://ENTRY:PORT/#PIN"` verifies and remembers a public entry peer. If this host has a numeric public `--address` and published music/profile, it sends signed announcements to remembered public peers, renewed every ten minutes and expiring after thirty. The entry accepts only the address observed on the announcing connection. Directory entries contain no private invitation tokens.

Android Discover searches reachable catalogs and profiles and can follow bounded batches of those announcements. The public directory is not a complete worldwide index. Reachable entry peers must exist; these address-based announcements do not provide NAT traversal. The optional
Rust network mode below uses a separate peer-ID discovery and relay transport. LAN-only desktop hosts still need their card supplied or an operating-system mDNS announcement. Android public hosts advertise through NSD automatically.

See [Discovery and security](DISCOVERY-SECURITY.md) for the publication boundary, Android codec isolation, implemented limits and unfinished internet connectivity work. The desktop codec is not sandboxed by the Android service implementation.

## Community preview: boards, conversations and invitations

Artist profiles now open **Backstage**. Artists start board threads and fans can
reply. Track menus open **Song conversation / remixes**; posts can carry a
playback timestamp. The host stores signed records in `community/track-*.bin`
and `community/board-*.bin`, alongside its other metadata. The WAV/FLAC/MP3 and
original publication signature never change. Existing comments remain readable.

Public conversation records are fetched with verified downloads and can be
served by replicas. Private records are scoped to the original circle and are
never included in that public import. A remix creates a public link on the
original track's host and an original-track backlink on the remix's host. Both
retain the signed song metadata and a public host card. These are links, not
promises that the remote host will remain online or permission to copy a track.

Only the signed artist can start board threads, pin/hide posts or lock replies.
Local mute uses the existing host block list. Public copies cannot be recalled.
The first implementation has bounded threads (256 records, including moderation)
and follower registries (512 entries); pagination/archive is follow-up work.

Following locally remains private. **Receive circle invitations** separately
registers your name and signing identity with the artist's own host. The artist
can select registered followers to invite. The follower retrieves the capability
with a short-lived signed request over pinned TLS and explicitly accepts it.
Nothing is sent to a public directory or public thread. This reuses the existing
single circle per host: inviting someone grants access to that whole circle.
Rotating the circle invalidates outstanding grants. Members can still share a
capability or keep previously downloaded music.

Live Follow checks boards and invitation inboxes approximately once a minute
while its visible foreground service is enabled. An initial board read sets a
baseline; an available circle invitation prompts the recipient. Offline delivery
waits for both the host and recipient to reconnect. There is no promise of
background delivery after Android stops the service.

## Opt-in monthly headcount

Sharing settings → Community headcount lets a user select a pinned public node
and opt in. On app launch, a consenting installation checks in at most once per
UTC calendar month after a successful response (failed attempts back off six
hours). The token is derived from an independent random local secret, the month
and the selected node; it does not reuse an artist key or Android identifier.

A counting node keeps the current month and deduplication tokens only, with a
100,000-token bound. No IP log, profile, song, version or hardware details are
stored by the counter. Network connections still expose addresses to peers.
Old tokens are purged at rollover; the desktop daemon checks every ten minutes
and every count request. Opting out attempts removal of the current token and
stops future check-ins. If the node is unavailable, the old token expires at
rollover. Restoring a backup containing old count files can restore old tokens
until that rollover check; operators should exclude the count file from backups.

This measures **participating installations on this node this month**, not people,
worldwide users, or installs across all nodes. Reinstalls and multiple devices can
count again. A malicious client can fabricate tokens; do not use the count for
payments, access control or claims of verified audience. Do not add independent
nodes' totals together. Android identity recovery/linking is future work.

## Always-on volunteer companion

The companion can now start the existing Rust/libp2p transport as well as direct
TLS. Build `libkillanet.so` with `bash net/build.sh host`, then package with:

```
HAMMER_DESKTOP_LIB=/path/to/libhammer.so \
HAMMER_NETWORK_LIB=/path/to/libkillanet.so \
  bash peer/build-companion.sh /path/to/output
```

Run `sh run.sh --daemon --network --help-network --community-count --data DIR
--address REACHABLE_ADDRESS --bind BIND_ADDRESS`. `--network` enables peer-ID
routing; `--help-network` also volunteers relay/DHT service; `--community-count`
separately enables the optional counter. No counting is enabled by default.
`--entry MULTIADDR` supplies an initial reachable peer when needed. UPnP is off
for the desktop launcher unless `--upnp` is supplied. Direct TLS reachability and
worldwide libp2p reachability must be tested separately.

The data directory includes `public.card`, `direct.card` and a bounded
`health.txt` status snapshot. Private keys and circle capabilities stay there;
never publish that directory. Do not run two companions with the same data
directory. The interactive commands `post`, `board`, `followers`, `invite`,
`count` and `status` complement the phone's screens.

This node performs actual audio transcoding for tracks its operator has chosen
to host. It does **not** accept remote generation jobs, arbitrary uploaded files,
commands or executable tasks. Use the provided `peer/deploy/ops.service.example`
as a starting point for CPU/memory/task limits. The desktop codec runs in this
bounded service, not Android's isolated codec process.

The [community and Rust desktop roadmap](../docs/community-and-desktop-roadmap.md)
(or `ROADMAP.md` in the companion ZIP) covers the complete desktop music app,
GPU detection/correctness testing, cross-platform packaging, and separately
consented volunteer music-generation workers.
