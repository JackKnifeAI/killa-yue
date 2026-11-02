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
also offers itself under the provider key of the current hour, `ky:mixes:2:<hour>`, the way hosts offer themselves
in `ky:hosts:1` (a fixed key filled up with mixes long gone: the DHT keeps provider records for two days). The
descriptor carries a signed publish time and is renewed every five minutes; one older than a quarter of an hour is not
used. A Sphinx
node address is the 32-byte ed25519 public key; the PeerId follows from it, so a hop cannot be redirected.

**Mixes behind routers.** Most volunteers cannot open a port on their router (UPnP is off on both the Xfinity and
the Rogers gateways we tried, and so is incoming IPv6), and a mixnet of only those who can would never have many
independent mixes. So an always-on computer that helps (the desktop helper, flag `COMPUTER`; never a phone, which
moves and sleeps) serves as a mix node from behind a router too, as soon as it holds a relay spot on a reachable
helper. Others reach it through that spot and the link is hole punched to a direct one where both routers allow it,
as Hyphanet's nodes and our own phone hosts are reached. Having no public address to count by, it claims in its
descriptor (version 3, signed) the networks at least two peers saw it on (identify's observed address); at most
four. A claim is no weaker than the public addresses other mixes publish, which are their own word too. What it
costs: until a link is punched through, its packets pass the relay, which sees their timing in and out of that
mix (never their contents, every packet being the same size and layered). The relay is often itself a mix, so a
mixnet whose mixes sit behind one relay gives that relay more to watch; more reachable helpers, each with relay
spots, spread it.

**Paths.** A client picks three distinct mix nodes. The first is its **gateway**, kept for weeks like a Tor guard so
that a hostile node gets few chances to be first. The other two are chosen fresh for each packet. Mix nodes run by
the same person cannot be told apart, so "three helpers" means three that *appear* independent; the app counts
distinct /16 IPv4 networks (/32 for IPv6) and asks for three of those. A path takes its three mixes from three
different networks whenever the mixes known allow it.

**Delays.** The sender picks a delay for every hop from an exponential distribution (mean 500 ms), as in Loopix.
Each mix holds the packet for that long before passing it on, so the order of packets leaving a mix is not the order
they arrived in.

**Cover traffic.** A sender emits packets as a Poisson process. When it has nothing to send, the slot carries a
*loop*: a cover packet through three mixes back to itself. Real packets take the place of cover, so the rate looks
the same. Helpers loop all the time (one every 2 s). Phones loop only while Protection is on and the app is open (or
uploading), at one every 2 s on Wi-Fi and one every 5 s on mobile data. A slower clock made a real message wait for
its slot: a two-packet comment took over half a minute at one every 20 s. The app says cover traffic stops when it
is closed, and how much data it uses (measured on the S25).

**Replies.** A message that needs an answer carries a **SURB** (single-use reply block): a ready-made return path
that ends at the sender's gateway, which hands the reply over its direct connection. The answering side never
learns the sender. SURBs carry acknowledgements for uploads too. The answering side never sends to the block's first mix
itself (the block's maker chose that mix and may run it): its own gateway passes the packet on, after a delay of
its own, so the first mix sees only the gateway.

**Sealed deliveries.** Sphinx hides the payload from every hop but the last, and the last one hands it on: by
itself it would read every vote and comment it delivers. So a delivery is sealed for its receiver before it is
wrapped: a one-time X25519 key against the receiver's ed25519 key in Montgomery form (as age does for ssh-ed25519
recipients), HKDF-SHA256 and ChaCha20-Poly1305, 48 bytes per packet. The last mix learns where a delivery goes,
never what it says; the receiver drops anything whose seal does not open.

**Messages in parts.** A message longer than one packet (a comment with its reply block) goes as several, each by
its own path: the message's id, the part's index and the count, then the bytes. The receiver keeps the parts of at
most 64 unfinished messages for three minutes and hands the message on when its last part is in. A lost part loses
the message; its sender asks again when no answer comes.

**Exits.** The last hop reads a one-byte service from the destination address:

| Service | What the last mix does |
|---|---|
| 0 loop | drops it (cover) |
| 1 deliver | passes the payload to the peer whose key is in the destination, over libp2p |
| 2 adopt | keeps the chunk of a hidden song for itself (it is the adopting helper) |
| 3 reply | hands a reply to a client connected to it as its gateway |

**Replays.** A mix remembers the replay tag of every packet it processed under its current key and drops repeats;
the memory is cleared when the key changes.

**Transport.** Packets move over a libp2p stream protocol, `/killa-yue/mix/1`, one long-lived stream per neighbour,
frames of a fixed length. Mixes accept frames only from connections they can account for, and at most a set rate
per peer.

## Protected requests (`HammerMix.java`)

With Protection on, a vote or a comment goes to its host through the mixnet instead of a connection: the request
(magic, operation, a random nonce, a reply block, the circle token and the signed vote or comment) is delivered to the
host's PeerId, the host handles it exactly as one over a connection and answers through the reply block: taken, or
refused with its reason. The host takes at most four such requests a second in all (anonymous senders cannot be
limited by address). A request goes twice, 45 s apart, before it fails; votes and comments are idempotent, so a repeat
changes nothing.

**No fallback.** When a protected send cannot go (the network is off, the host is reached by address and not by
PeerId, fewer than three mixes, or mixes on fewer than three independent networks), nothing is sent directly
instead: the person is told why. A protected vote also skips the direct reads around it (the votes before, the count
after), which would show the host the phone's address beside the vote; its sequence comes from the clock instead.

**What it does not hide**, as the setting says: listening (streams still go directly or through a relay, and a vote
just after a play may be linked by timing), that you use Killa YuE, the profile key a comment is signed with, and
anything from someone who runs most of the helpers.

## Hidden artists

**A separate identity.** A hidden artist is a new artist key (P-256, like every artist key) made for that name only.
It never signs anything sent from the phone's own address: no presence, no hosting, no comments, no votes. The
Library keeps it apart from the person's normal profile.

**Publish and walk away.**

1. The song is cleaned as every published song is (N1: no device, location or encoder tags) and signed by the
   hidden identity, with rehosting allowed.
2. The phone uploads it through the mixnet to **three helpers**, each by its own paths, each helper being the exit
   (service 2) of its own upload. Chunks carry SURBs; the helper acknowledges what it has, the phone resends what
   is missing. An upload pauses and resumes; it does not need the screen. While it runs, the phone's clock only
   paces (one packet every 20 ms on average, under the 128 a second a gateway takes from one peer) and sends no
   cover loops: a cover clock that fast filled its empty slots with loops and flooded the path, and the provider
   sees an upload whatever the rate. A mix takes up to 4096 frames a second from another mix, so a busy one keeps
   its links.
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
  descriptors checked, proof-of-work, sealed deliveries (only the receiver opens one, a changed one opens for no
  one), messages in parts and back whole, parts that lie refused.
- `peer/test/network.sh` step 7: three helpers carry a message between two clients, the answer and cover loops come
  back; step 8: a vote and a two-packet comment reach a host behind NAT through the mixnet and are taken, a host
  reached by address is refused, a refusal comes back with its reason; step 10: three computers behind "routers"
  (no address anyone can dial, no test switch to serve) are the only mixes, reached through a relay that does not
  mix, and a message and its answer go through them.
- `peer/test/network.sh`: a mixnet of helper nodes on one machine; a phone-like node behind NAT publishes a hidden
  song; it is adopted by three helpers and a listener plays it from one, and no helper ever had a connection from the
  publisher except its gateway.
- On the S25 (2026-10-26, mobile data only, in the app): a protected vote taken in 4–13 s (the slow one just after
  leaving Wi-Fi), a two-part comment in 2–4 s, a hidden song adopted by three test helpers. Data in 10 minutes:
  3.4 MB with cover off, 3.2 MB with the cover clock at 5 s (cover loops back 119 of 120): cover traffic is lost in
  the network node's own use, about 20 MB an hour on mobile data, which is worth cutting on its own. Battery is not
  measured yet.

## Steps

1. The mix layer: packets, descriptors, paths, delays, forwarding, replays, loops (helpers and clients). Done.
2. SURB replies, sealed deliveries and messages in parts. Done; reliable transfer of a whole song is part of step 3.
3. Adoption on helpers and hidden publishing on phones, with the limits above.
4. The Protection setting (done: votes and comments, the honest text, the live numbers) and the hidden-artist flow.
5. Measurements on the phones, then an outside review before the word "protects" appears anywhere.
