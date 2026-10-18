# HAMMER desktop companion

This computer can host the same signed recordings as Killa YuE phones. It serves
the existing master file, encodes compressed streams in memory, and exchanges
catalogs, audio, comments and votes through pinned TLS connections. No account,
tracker, analytics service or company-operated server is required.

From Killa YuE: Library → HAMMER → Save desktop companion. Transfer the ZIP to
your computer, extract it, and open a terminal in `hammer-server`.

## Start

Install a Java 17 or newer JDK. On Linux x86-64, the app's ZIP includes the native
audio library built on the test machine. Run `sh run.sh --address YOUR_LAN_IP`.
If that library is incompatible with your distribution, build it locally below.

The default listener is port 8784 on all interfaces. `--bind 127.0.0.1` restricts
it to this computer. Set `--address` to the address your listeners can reach;
this does not configure a router or firewall. A phone hotspot or the same Wi-Fi
works without internet. Remote listeners need a reachable IPv6 address, a
forwarded port, or a network you control. Carrier-grade NAT is not bypassed by
this build. There is no mandatory rendezvous or relay provider.

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
