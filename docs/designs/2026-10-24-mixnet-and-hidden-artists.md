# The mixnet and hidden artists (1.8)

Two features built together, because each needs the other:

- **The mixnet** hides *who talks to whom* for small messages: follows, votes, requests, chat, searches, and the
  upload of a hidden artist's song.
- **Hidden artists** publish a song and walk away: the song travels through the mixnet to helpers, the helpers
  host it, and no helper and no listener learns which phone it came from.

This replaces step 1 of "N3. The mixnet study" in the 1.8 roadmap. The rules from there stand: a reviewed packet
format (Nym's `sphinx-packet` crate, Apache-2.0), our own mix layer on our own helpers, and **no claim to anyone that
it protects them until an outside security review**. Until then the setting is labelled *experimental*.

## What it hides, from whom

| Who is watching | Without the mixnet | With it |
|---|---|---|
| A helper relaying or hosting for you | your IP address and who you reach | the first hop sees you but not where it goes; the last sees where it goes but not you |
| Your internet provider, a café's Wi-Fi | that you use Killa, when and how much | that you use Killa; real messages hide among fake ones while cover traffic runs |
| Someone watching both ends (a national network) | matches traffic in and out by timing | delays and cover traffic break simple timing matches |
| A listener or a host | the IP of a phone that hosts a song | a hidden artist's songs come from helpers, never the artist's phone |

What it does **not** hide, and the app says so:

- **Audio streams.** Too big and too time-sensitive to delay. A listener streaming from a phone host is seen by the
  relay; hidden artists avoid that by letting helpers host.
- **A small crowd.** The mixnet hides you among the people using it at the same time. With a handful of helpers,
  anyone who runs most of them sees through it. The app shows how many independent helpers it can use, and a
  protected send needs at least three.
- **A watcher of the whole internet over months** can still learn patterns from long-term behaviour (statistical
  disclosure). No deployed mixnet fully defeats that.
- **A compromised phone.** Spyware sees everything before it is wrapped.
- **That you are uploading.** A hidden upload is many packets; your provider sees that you sent a lot through the
  mixnet, not to whom.

## The mix layer (`net/src/mix.rs`)

**Packets.** Sphinx packets from `sphinx-packet` 0.8 with a 2048-byte payload. Every packet on the wire is the same
size, whatever it carries: a chat line, a song chunk, an acknowledgement or nothing (cover).

**Mix nodes.** A helper that is reachable from the internet serves as a mix node unless its owner turns that off.
Phones are never mix nodes; they are clients. A mix node has an X25519 mix key, separate from its network key and
replaced every day (the previous day's key is kept for an hour of overlap). It publishes a **mix descriptor** in the
DHT under `ky:mix:<peer>`: the mix key, the day it is for, and an ed25519 signature by its network identity. It
also offers itself under the provider key `ky:mixes:1`, the way hosts offer themselves in `ky:hosts:1`. A Sphinx
node address is the 32-byte ed25519 public key; the PeerId follows from it, so a hop cannot be redirected.

**Paths.** A client picks three distinct mix nodes. The first is its **gateway**, kept for weeks like a Tor guard so
that a hostile node gets few chances to be first. The other two are chosen fresh for each packet. Mix nodes run by
the same person cannot be told apart, so "three helpers" means three that *appear* independent; the app counts
distinct /16 IPv4 networks (/32 for IPv6) and asks for three of those.

**Delays.** The sender picks a delay for every hop from an exponential distribution (mean 500 ms), as in Loopix.
Each mix holds the packet for that long before passing it on, so the order of packets leaving a mix is not the order
they arrived in.

**Cover traffic.** A sender emits packets as a Poisson process. When it has nothing to send, the slot carries a
*loop*: a cover packet through three mixes back to itself. Real packets take the place of cover, so the rate looks
the same. Helpers loop all the time (one every 2 s). Phones loop only while Protection is on and the app is in use or
uploading, at one every 5 s on Wi-Fi and one every 20 s on mobile data, about 1 MB an hour on mobile. The app says
cover traffic stops when it is closed.

**Replies.** A message that needs an answer carries a **SURB** (single-use reply block): a ready-made return path
that ends at the sender's gateway, which hands the reply over its direct connection. The answering side never
learns the sender. SURBs carry acknowledgements for uploads too.

**Exits.** The last hop reads a one-byte service from the destination address:

| Service | What the last mix does |
|---|---|
| 0 loop | drops it (cover) |
| 1 deliver | passes the payload to the peer whose key is in the destination, over libp2p |
| 2 adopt | keeps the chunk of a hidden song for itself (it is the adopting helper) |
| 3 reply | hands a reply to a client connected to it as its gateway |

**Replays.** A mix remembers the replay tag of every packet it processed under its current key and drops repeats;
the memory is cleared when the key changes.

**Transport.** Packets move over a libp2p stream protocol, `/killa/mix/1`, one long-lived stream per neighbour,
frames of a fixed length. Mixes accept frames only from connections they can account for, and at most a set rate
per peer.

## Hidden artists

**A separate identity.** A hidden artist is a new artist key (P-256, like every artist key) made for that name only.
It never signs anything sent from the phone's own address: no presence, no hosting, no comments, no votes. The
Library keeps it apart from the person's normal profile.

**Publish and walk away.**

1. The song is cleaned as every published song is (N1: no device, location or encoder tags) and signed by the
   hidden identity, with rehosting allowed.
2. The phone uploads it through the mixnet to **three helpers**, each by its own paths, each helper being the exit
   (service 2) of its own upload. Chunks carry SURBs; the helper acknowledges what it has, the phone resends what
   is missing. An upload pauses and resumes; it does not need the screen.
3. Each helper verifies the song like any rehosted song (signature, hash, size, audio header) and hosts it in its
   **adopted** catalogue. Listeners find it in Discover under the hidden artist's name.
4. The phone keeps the song in the Library as "Hidden, hosted by 3 helpers" and **never serves it itself**.

**Limits for helpers**, because anyone can send anonymously:

- one song at most 32 MB (most MP3s; a long WAV is refused with a clear message);
- a proof-of-work stamp bound to the song (a few seconds of phone CPU), so flooding a helper costs real time;
- per helper, by default 2 GB and 25 songs per hidden identity, both settable;
- the owner can turn adoption off, and remove any adopted song; a removed song is never taken again by that helper.

The app tells hidden artists plainly: helpers choose what they keep, so publish to several; if every helper drops a
song, it is gone from the network, and only the artist's own copy remains.

## How it is tested

- `cargo test` in `net/`: packets through three hops, replies through SURBs, replays refused, wrong keys refused,
  descriptors checked, proof-of-work, chunking and reassembly with loss.
- `peer/test/network.sh`: a mixnet of helper nodes on one machine; a phone-like node behind NAT publishes a hidden
  song; it is adopted by three helpers and a listener plays it from one, and no helper ever had a connection from the
  publisher except its gateway.
- On the S25: battery and data per hour with Protection on, on Wi-Fi and mobile data.

## Steps

1. The mix layer: packets, descriptors, paths, delays, forwarding, replays, loops (helpers and clients).
2. SURB replies and reliable chunked transfer.
3. Adoption on helpers and hidden publishing on phones, with the limits above.
4. The Protection setting and the hidden-artist flow in the app, with the honest text.
5. Measurements on the phones, then an outside review before the word "protects" appears anywhere.
