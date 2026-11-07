# Killa YuE 1.8.0: a network that cannot be silenced, and that protects the people on it

21 October 2026. Asked for by Alexander: "music activists need protection from censorship and this network needs to thrive". This note is the threat model and the plan. Nothing here is built yet. Each phase gets its own short spec and its own tests before it ships.

## The two promises, kept apart

1. **The network cannot be switched off or blocked.** No company, server, domain, app store or country can stop people sharing music on it.
2. **The people on it are protected.** An artist who publishes a song a government does not like should not hand that government their location.

These are different problems. A network can be impossible to block and still expose every artist's IP address. **Promise 2 comes first**, because failing it hurts a person, while failing promise 1 only hurts the network.

We say only what is true: "censorship-resistant", "no off switch", "hides your IP when Protected is on". We never say "untraceable" or "anonymous". Tor itself does not promise that against an adversary who watches both ends.

## What is true today (1.7.4), honestly

| Risk | Today |
|---|---|
| Artist's IP address | **Visible** to every peer that connects. Public hosts are listed in the DHT with their addresses. Anyone who installs the app can collect the IP of every reachable artist. |
| Finding all nodes | Reachable nodes announce on **one fixed** Mainline DHT infohash, `SHA-1("killa-yue/net/1")`. A crawler lists them all in minutes. |
| Recognising the traffic | Our own port (47291), QUIC and TCP with libp2p's multistream handshake, which is fingerprintable. A firewall with deep packet inspection (DPI) can block it by pattern. |
| Blocking first contact | BitTorrent DHT, mDNS, remembered peers, entry peers. A country that blocks BitTorrent DHT and our port cuts new installs off. |
| Hidden data in songs | "Original" downloads and streams send the artist's **file as it is**: every ID3/FLAC/WAV tag, encoder strings, comments, embedded pictures (which can carry EXIF with GPS). Covers made in the app are re-encoded, which drops EXIF; the pictures inside the audio file are not. |
| Getting the app | GitHub releases only, and the in-app updater checks GitHub. A takedown or block of GitHub stops new installs and updates. |
| Burying songs | Votes and counts come from hosts and peers. A crowd of fake peers could down-vote or flood a song out of the charts. |
| A seized phone | The library, the artist key and the list of followed artists sit on the phone, protected only by the phone's own lock. |

What is already good: no central server; every song and artist is signed; streams are end-to-end encrypted (Noise or TLS inside libp2p, plus HAMMER's pinned TLS); relays see only encrypted bytes; downloaded songs are rehosted, so a song outlives its artist's phone.

## Who we defend against

- **A platform or company** (GitHub, Google Play, a hosting provider) removing the app or a song.
- **A national firewall** (blocking IPs, ports, protocols, DNS; DPI fingerprinting; throttling).
- **A government hunting an artist**, running its own nodes and crawlers in the network, and asking ISPs who had an IP at a given time.
- **Brigading**: many fake peers trying to bury songs or flood the network (Sybil and eclipse attacks).

Out of scope: an adversary who already controls the artist's phone (malware, unlocked seizure). We reduce what a seized phone reveals but cannot fix a compromised device.

## The plan, in order

### Phase 1: Protect the people (first, and small enough to ship soon)

1. **Clean files before they leave the phone.**
   - "Original" sends a copy with every tag rebuilt from scratch: only title, artist name, album, genre, lyrics and the app's own re-encoded cover.
   - Comments, encoder and software strings, dates, private frames and embedded pictures are dropped, and EXIF is never kept.
   - Done when: a test file carrying GPS EXIF, a comment and a TXXX frame comes out of a download with none of them, for MP3, FLAC and WAV.
2. **Say plainly what is visible.**
   - The first Share and the Help-the-network switch tell the artist in one sentence: "Listeners who connect to you can see your internet address. Turn on Protected to hide it."
   - No scary walls of text.
3. **"Protected" artists (the core of 1.8.0).** A per-artist switch, so it also covers the identity used for political songs.
   - When on, the phone never accepts or makes direct connections for that artist. It publishes and serves only through a **Tor onion service**, run inside the app by **Arti**, the Tor Project's Rust implementation (onion services supported; we verify the exact Arti version and its open issues before building).
   - Listeners reach a protected artist through Tor. Their own IP is hidden from the artist too.
   - Protected artists' songs are never published under the phone's own PeerId or public addresses, so they cannot be linked to the phone's other, unprotected identity.
   - Rehosting does the heavy lifting: once listeners download a protected song, they serve it, and the artist's phone can go offline.
   - Cost to state honestly in the app: Tor is slower (fine for 48–96 kbps Opus, slower for "Original"), uses more battery, and the APK grows by an amount we measure, not guess.

### Phase 2: Impossible to block (the network keeps reaching people)

4. **Look like the web.**
   - Add a transport that is ordinary HTTPS on port 443: WebSocket or WebTransport inside TLS.
   - Volunteer computers and phones with a public address listen there too.
   - DPI then sees what looks like a normal website, and blocking it means blocking the web.
5. **Tor as the last road in.**
   - Where everything else is blocked, the node connects through Tor with **pluggable transports**: Snowflake (looks like a video call), obfs4, and WebTunnel (looks like HTTPS).
   - Arti supports external pluggable transports. On Android these have to run in-process, the way Orbot does with IPtProxy. That needs a spike to prove before we promise it.
6. **Many meeting points, not one.**
   - The Mainline infohash rotates daily (derived from the date), so one blocked or poisoned key does not stop first contact.
   - Peer exchange: every connection shares a few good peers.
   - Invite cards carry several peers (QR or text), so one friend who has the app can bring a whole community in, offline.
   - Every volunteer onion address can be a meeting point too.
   - Honest limit: anyone with the app can compute today's meeting point. This stops easy blocking, not crawling. Phase 1's Protected mode is the answer to crawling.

### Phase 3: Keep the app itself alive

7. **The network carries the app.**
   - Every phone can hand a friend the signed APK directly, phone to phone, offline via Wi-Fi or a share sheet.
   - The updater also looks for new releases on the network.
   - Android refuses an update that is not signed with our key, so a peer cannot slip in a fake.
   - We also check the release's SHA-256 against a signed release note, so a downgrade or swapped file is refused.
8. **Mirrors.**
   - Codeberg mirror of the source.
   - Our own F-Droid repository, where users add the repo once and get updates.
   - The release APK and source published by torrent magnet and IPFS CID in every release note.
   - None of these is required. Each one is another road.

### Phase 4: Hard to bury, hard to poison

9. **Sybil and eclipse resistance in our DHT.**
   - Limit how many routing-table entries may share an IP range.
   - Look up over several disjoint paths (S/Kademlia), and accept a record only when independent peers agree on it.
10. **Votes that cannot be faked cheaply.**
    - Charts count one vote per signed artist or listener key, weighted by how long the key has existed and whether people you follow trust it.
    - A song is never hidden for down-votes: it only ranks lower, and search always finds it.
11. **A seized phone reveals less.** An optional app PIN for the library and the protected identity, and a "panic" action that destroys the protected key in the Keystore. This is the crypto-shred Pocket Agent already uses.

## Testing (every phase)

- network.sh, run.sh and community.sh stay green. New adversarial tests:
  - a crawler listing the meeting point;
  - a DPI-style port block;
  - a BitTorrent-DHT block;
  - forged and replayed records;
  - a Sybil flood of the routing table;
  - a protected artist's IP never appearing in any peer's view: we capture packets on ops and grep for it.
- Real-world checks: S25 and S20 on different carriers.
  - Protected publish and stream over Tor.
  - Port 47291 firewalled on ops to simulate a block.
  - Battery measured, not assumed.

## Decisions for Alexander

- Tor inside the app (Arti): yes or no, given a bigger APK and slower Protected streams.
- Default: should Protected be off (fast) with a clear switch, or on for every new artist identity?
- Whether a jackknife-run volunteer node (the VPS) also listens on 443 and as an onion service. It stays one ordinary node, never hardcoded.
