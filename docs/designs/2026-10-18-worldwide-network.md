# The Killa YuE network: worldwide, peer to peer, no server

18 October 2026. Agreed with Alexander in chat: the network must work anywhere in the world, be completely decentralised, stream, and be built on open source that stays open. This note records the design. Each phase gets its own short spec before it is built.

## What it is for

An artist anywhere publishes a song on their phone. A listener anywhere (on Wi-Fi, mobile data, or behind a carrier's NAT) finds that artist in Discover, streams the song, downloads it, follows the artist and hears about new releases. No company server sits in the middle. Nobody can switch the network off by switching one machine off.

## What HAMMER does today, and what is missing

HAMMER (PRs #32, #33) already does the hard parts above the network:
- signed artists and songs;
- pinned TLS;
- catalogues, Opus streaming, verified downloads and rehosting;
- comments, votes and Live Follow.

It reaches a peer only by dialling an address: the same Wi-Fi (Android NSD), or a peer card whose address the internet can reach. Most phones sit behind home routers or carrier-grade NAT, which drop incoming connections. So a fresh install far from other artists finds no one.

Three things are missing:
1. **Finding peers** without a central index.
2. **A first contact** for a brand-new install.
3. **Connecting two peers behind NAT.**

All three are solved problems in peer-to-peer systems (BitTorrent, IPFS, Keet). We adopt a proven implementation rather than invent one.

## Decisions

- **libp2p, the Rust implementation** (`libp2p/rust-libp2p`, MIT, actively maintained; the stack under IPFS). It ships every piece we need:
  - `kad`: the Kademlia DHT;
  - `autonat`: am I reachable?;
  - `dcutr`: hole punching;
  - `relay`: circuit relay v2;
  - `upnp`: router port mapping;
  - `mdns`: same-Wi-Fi discovery;
  - `quic` and `tcp`+`noise`+`yamux`: transports;
  - `stream`: raw two-way byte streams for our own protocol;
  - `gossipsub`: publish/subscribe.
- **Open source forever.** MIT cannot be revoked for code already released. We pin exact versions in `Cargo.lock` and commit the full vendored sources (`cargo vendor`) next to the build. The network then builds from this repository alone, even if crates.io or the upstream repository disappeared. Killa YuE stays Apache-2.0, which is compatible.
- **Our own DHT, not IPFS's.** The DHT protocol id is `/killa-yue/kad/1.0.0`, so our records never mix with the public IPFS network, and IPFS load never lands on phones.
- **No server we must run.** Every role (first contact, relay, hole-punch helper) is played by ordinary Killa YuE peers that are reachable. A phone on home Wi-Fi with UPnP, or a desktop companion, will do. Any extra community peer we run (ops, say) is optional and replaceable.
- **A network key of its own** (corrected 18 October, while building phase 1). HAMMER's identity key lives in Android's hardware keystore and cannot be exported, so it cannot become the libp2p key. Each node instead makes an Ed25519 key, kept in the app's private files (`files/network/node.key`). Its PeerId is only the address. HAMMER's pinned TLS still runs inside every stream, so a peer that lies about its PeerId fails the handshake. For phase 2, DHT records will carry a binding: the HAMMER key's signature over the PeerId.
- **HAMMER's protocol is unchanged at first.** Phase 1 carries HAMMER's existing TLS connections over libp2p streams. Catalogues, streaming, downloads, votes and Live Follow keep their tested code. Once libp2p's own authentication is trusted for pinning, a later phase can drop the inner TLS layer.
- **A native library, like the engine and the Studio.** `libkillanet.so`, Rust, built on ops with the Android NDK for `aarch64-linux-android`, and for `x86_64-linux` for the desktop companion. It is called from Java through a small JNI surface.

## How it fits together

### The bridge to HAMMER

The library runs the libp2p node in its own thread. To reach a peer, Java asks `KillaNet.open(peerId, protocol)`. The library opens a libp2p stream (direct, hole-punched or relayed, whichever works) and hands back a socket on `127.0.0.1`. HAMMER's existing `SSLSocket` code runs over it untouched.

Each loopback port accepts exactly one connection and requires a one-time token, so no other app on the phone can use it. Inbound streams arrive the same way, to HAMMER's existing listener. The protocol id is `/killa-yue/hammer/1.0.0`.

### Peer cards

A card no longer needs an address: `hammer://p2p/<PeerId>#<pin>` (the private-circle token as today). Old address cards keep working; the address becomes a hint.

### Transports

- QUIC over UDP comes first, because hole punching works far better on UDP.
- TCP with Noise and yamux is the fallback.
- Both run on IPv4 and IPv6. Many carriers give phones public IPv6 addresses, which often makes direct connections possible.

### Finding peers and music (the DHT)

The keys below are SHA-256 hashes; values are signed and checked on read.

- **Artist key** `ky:artist:<artistId>` → provider records: the PeerIds of hosts serving that artist.
- **Song key** `ky:song:<songId>` → provider records: every replica. Streaming fails over across them, as HAMMER already does.
- **Tag key** `ky:tag:<normalised tag>` → provider records for the hosts with public songs under that genre. This is what Discover browses.
- **Profile key** `ky:profile:<artistId>` → a value record holding the signed profile summary (name, tags, portrait hash, revision). Only a higher, correctly signed revision replaces it.
- **New releases:** gossipsub topic `ky/new/1` for signed "new song" events, and `ky/artist/<artistId>` per artist. Live Follow subscribes to its artists' topics, so a release reaches followers through whichever peers they are connected to.
- **Private circles never go into the DHT under public keys.** A private host is found under `HMAC(circleToken, "ky:circle")`, which only invitees can compute.

Records expire and are republished: provider records every 12 hours (expiring after 48), profiles on change and daily.

### First contact, with no server of ours

A new install tries all of these in parallel; any one is enough:
1. **Peers it already knows.** After the first run, the app keeps up to 200 peers it has met, ranked by how reliably they answered.
2. **The BitTorrent DHT as a meeting point.** Reachable Killa YuE nodes announce themselves on the Mainline DHT (BEP 5) under the infohash `SHA-1("killa-yue/net/1")`. A new install asks the Mainline DHT for that infohash and gets Killa YuE peers back. Mainline has millions of nodes and no owner. The Rust crate `pubky/mainline` (MIT) implements it.
3. **Same Wi-Fi:** libp2p mDNS, and HAMMER's existing NSD.
4. **A built-in community list.** Volunteer desktop companions, opt-in. It ships with each release and can be edited in Settings.
5. **A peer card**, scanned or pasted, as today.

### Getting through NAT

- **AutoNAT:** other peers tell each node whether it can be reached from outside.
- **UPnP:** home routers that allow it open a port, and the node becomes reachable.
- **Hole punching (DCUtR):**
  - Two unreachable peers first meet through a relay, then dial each other at the same moment.
  - Over UDP this usually upgrades to a direct connection, and the relay drops out.
  - It fails mostly when both sides are behind NATs that change the port for every destination ("symmetric"), which some carriers use.
- **Relays (circuit relay v2):**
  - When hole punching fails, the stream stays on the relay. The relay is any reachable Killa YuE peer that has chosen to help.
  - The stream is end-to-end encrypted, so the relay sees bytes, not music.
  - libp2p's default relay limits (about 2 minutes, 128 KiB) only cover hole punching. Our relays raise them so a stream fits: up to 8 circuits, 30 minutes and 64 MiB each. Settings shows the numbers.
  - A relayed stream starts at Data saver quality, 48 kbps Opus, and Auto climbs if the path allows.

**The honest limit:** if no reachable peer exists anywhere, two phones both behind symmetric carrier NAT cannot connect. The cure is more peers, not a server: every desktop companion and every home-Wi-Fi phone with UPnP becomes a relay. The first public build should ship with a few volunteer community peers so the network has a backbone on day one.

### Phones are good citizens

- **On mobile data** the phone is a DHT *client*: it asks the DHT and announces its own records, but does not answer other peers' DHT queries. It never relays.
- **On Wi-Fi and charging** it may serve the DHT and relay. The switch is "Help the network" in Settings, on by default.
- **Relaying and DHT serving pause while a song is being made or a Studio job runs**, the same rule hosting already follows.
- **Connections are capped**, about 48 on Wi-Fi and 16 on mobile data. Idle connections close.
- **Live Follow** keeps one connection alive in the background. Gossipsub delivers release events through it.

### Security

Same boundaries as HAMMER today (see `peer/DISCOVERY-SECURITY.md`), plus:
- **DHT records:** every DHT value is signed and checked; a record that fails verification is dropped.
- **Lookups:** DHT lookups take answers from several unrelated peers, to resist being cut off by attacker-controlled peers (eclipse attacks).
- **Rate limits:** per-peer limits on DHT queries and relay reservations.
- **Hosts:** HAMMER's admission limits stay in front of every inbound stream.
- **What stays visible:** IP addresses stay visible to the peers you connect to, as today; relays see the IPs of both ends.

## Phases

1. **The node and the bridge.**
   - `libkillanet.so` with identity, QUIC, TCP and mDNS; HAMMER over libp2p streams; PeerId cards.
   - Test: two phones on the same Wi-Fi stream, download and Live Follow over libp2p; all existing HAMMER tests pass over the bridge.
2. **The DHT and first contact.**
   - Kademlia with our protocol id, peer cache, Mainline meeting point, community list.
   - Test: a phone with an empty cache, on another network, finds the S25 through the Mainline DHT alone.
3. **Through NAT.**
   - AutoNAT, UPnP, relay v2 with our limits, DCUtR.
   - Test: S25 on mobile data and S20 Ultra on a different carrier, Wi-Fi off on both, stream a full song. Record direct vs relayed, time to first sound, battery used.
4. **Worldwide Discover.**
   - Artist, tag and profile lookups; `ky/new/1` "New releases" shelf; Live Follow over gossipsub.
   - Test: an artist's page found by search from a phone that has never seen the artist.
5. **Hardening.**
   - Adversarial tests (forged records, query floods, relay abuse), overnight unplugged battery, other Android vendors, desktop companion on Linux, macOS, Windows.

## Open

- **Who builds which phase:** Claude on the phone and ops, or Astra (who wrote HAMMER).
- **APK size budget for `libkillanet.so`:** measure in phase 1. I expect it to be well under the engine's size, but have not measured it.
- **Whether ops runs as one of the launch community peers.** It is optional; the network does not depend on it.
