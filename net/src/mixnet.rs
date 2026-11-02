//! The mixnet on the network: finding mix nodes, moving fixed-size frames between them, holding packets for their
//! delays, the sender's steady clock with cover traffic, and the inbox the app reads. The packets themselves are in
//! mix.rs. See docs/designs/2026-10-24-mixnet-and-hidden-artists.md.

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use futures::StreamExt;
use libp2p::{identity, kad, multiaddr::Protocol, Multiaddr, PeerId, StreamProtocol};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{mpsc, oneshot};
use tokio_util::compat::FuturesAsyncReadCompatExt;

use crate::mix::{self, MixKeys, MixNode, Mixer, Out, Service, FRAME, MESSAGE, SEAL};
use crate::{debug, now_secs, Cmd};

pub const MIX: StreamProtocol = StreamProtocol::new("/killa-yue/mix/1");
/// How long one listing of mix nodes lasts. Mix nodes offer themselves under the key of the current hour, and
/// nodes look in this hour's and the last hour's: the DHT keeps provider records for two days, so under one fixed
/// key the list filled up with mixes long gone, until the answer no longer fit in a message.
pub const MIXES_HOUR: u64 = 3600;

/// The DHT key under which mix nodes offer themselves during `hour` (seconds since 1970 / MIXES_HOUR).
pub fn mixes_key(hour: u64) -> kad::RecordKey {
    kad::RecordKey::new(&format!("ky:mixes:2:{hour}"))
}

/// The keys to look in now: this hour's, and the last hour's, where mixes that renewed late still are.
pub fn mixes_keys(now: u64) -> [kad::RecordKey; 2] {
    let hour = now / MIXES_HOUR;
    [mixes_key(hour), mixes_key(hour.saturating_sub(1))]
}

/// How often a serving mix signs and publishes its descriptor again.
const RENEW: Duration = Duration::from_secs(5 * 60);
/// How long a mix descriptor lives in the DHT; its mix renews it every RENEW.
pub const RECORD_LIFE: Duration = Duration::from_secs(15 * 60);
/// How often a node asks for each mix's descriptor again.
pub const REFETCH: Duration = Duration::from_secs(5 * 60);
/// A mix whose newest descriptor was signed this long ago (seconds) is taken to be gone.
const STALE: u64 = 20 * 60;
/// How soon a node with too few mixes asks the DHT for them again.
const ASK_AGAIN: Duration = Duration::from_secs(30);
/// A mix that refused a link TRIES times is left out of paths this long.
const DOWN: Duration = Duration::from_secs(10 * 60);

pub fn mix_key(p: &PeerId) -> kad::RecordKey {
    kad::RecordKey::new(&[b"ky:mix:".as_slice(), &p.to_bytes()].concat())
}

/// On the wire every frame is one kind byte, a 32-byte hop and exactly FRAME bytes, so a link shows only how many
/// frames pass.
const WIRE: usize = 1 + 32 + FRAME;
const PACKET: u8 = 0;   // a Sphinx packet for the next hop
const DELIVERY: u8 = 1; // the last hop hands a message to its receiver
const REPLY: u8 = 2;    // a gateway hands its client a reply
const PASS: u8 = 3;     // a client asks its gateway to pass a packet on to the hop named in the frame
const ACK: u8 = 4;      // a receiver says a delivery arrived, on the stream it came by
const HELLO: u8 = 5;    // the first frame on every stream, from the side that accepted it: the path is alive
/// How long a new link waits for the other side's hello before it counts as dead.
const HELLO_WAIT: Duration = Duration::from_secs(10);

/// The hello every accepted stream begins with.
fn hello() -> Vec<u8> {
    hand(HELLO, &[0; 32], &[0; ID], b"").expect("an empty frame fits")
}
/// The id a delivery or reply carries so the app can tell messages apart: a Sphinx SURB identifier.
const ID: usize = 16;
/// A helper loops one cover packet this often; a phone's rate is the app's choice while Protection is on.
const HELPER_LOOP: Duration = Duration::from_secs(2);
/// The gateway is only given up once its fresh descriptor has been missing this long.
const GONE: Duration = Duration::from_secs(5 * 60);
/// A mix that would not take a connection is left alone this long before another try.
const WAIT: Duration = Duration::from_secs(10);
/// Frames held for one mix whose link has not opened yet; beyond it they are dropped.
const HELD_OUT: usize = 256;
/// Reply blocks whose recovery material is kept at once; the oldest is let go.
const BLOCKS: usize = 4096;
/// Messages waiting in the inbox; beyond it the oldest is dropped.
const INBOX: usize = 10_000;
/// Frames a mix takes from one peer in one second: relaying a crowd needs room, a flood does not get more.
const RATE: u32 = 128;
/// Frames a mix takes in one second from another mix (one with a fresh descriptor): it forwards for everyone it
/// mixes for, and at the client rate a busy mix lost its link to the next one.
const MIX_RATE: u32 = 4096;
/// Streams one peer may have open to us at once.
const STREAMS: u32 = 16;
/// A sleep far in the future, for the select branches whose real time is not known yet.
const FAR: Duration = Duration::from_secs(3600);
/// Packets a mix holds for their delays at once, about 19 MB; past it new ones are dropped, as a busy mix must.
const HELD: usize = 8192;
/// Tries at a link to a mix before the frames waiting for it are let go.
const TRIES: u32 = 3;
/// The waits before each try at handing a delivery to its receiver: at once, then twice more after a moved phone's
/// old connections have died.
const DELIVERY_WAITS: [Duration; 4] =
    [Duration::ZERO, Duration::from_secs(15), Duration::from_secs(30), Duration::from_secs(45)];
/// How long a delivery waits for the receiver's acknowledgement before it counts as lost.
const ACK_WAIT: Duration = Duration::from_secs(5);
/// Deliveries remembered by id, so one sent again after a lost acknowledgement lands only once.
const DELIVERED: usize = 1024;
/// Every delivery is a part of a message: the message's id, the part's index and how many parts there are.
const PART_HEAD: usize = ID + 2 + 2;
/// What one part carries of its message.
const PART: usize = MESSAGE - SEAL - PART_HEAD;
/// The longest message `send` takes: a comment with its reply block fits in two parts, a request in one.
pub const LONGEST: usize = 32 * PART;
/// Messages whose parts are still coming in, at once; beyond it the oldest is let go.
const PARTIAL: usize = 64;
/// A message whose parts have not all come in this long is let go: one of them was lost, and its sender asks again.
const PARTIAL_LIFE: Duration = Duration::from_secs(180);

// ---------- the frames ----------

/// One frame off the wire, decoded.
enum Frame {
    /// a Sphinx packet for the next hop (a mix to the next mix, or a client to its gateway)
    Packet(Vec<u8>),
    /// a Sphinx packet the gateway passes on to `next` for its client: an answer through someone's reply block,
    /// whose first hop the client did not choose and must not see the client
    Pass { next: [u8; 32], packet: Vec<u8> },
    /// the last hop hands something to a peer: a delivery in the clear, a reply still sealed for the client
    Hand { kind: u8, to: [u8; 32], id: [u8; ID], body: Vec<u8> },
}

/// A packet frame: the kind byte, no hop, and the Sphinx packet.
fn wrap(sphinx: &[u8]) -> Vec<u8> {
    pass(PACKET, &[0; 32], sphinx)
}

/// A packet frame with the hop the gateway passes it on to.
fn pass(kind: u8, next: &[u8; 32], sphinx: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(WIRE);
    frame.push(kind);
    frame.extend_from_slice(next);
    frame.extend_from_slice(sphinx);
    frame
}

/// A delivery or reply frame: who it is for, the id it travels under, the body, and padding to the fixed length.
fn hand(kind: u8, to: &[u8; 32], id: &[u8; ID], body: &[u8]) -> Option<Vec<u8>> {
    if 51 + body.len() > WIRE {
        return None;
    }
    let mut frame = Vec::with_capacity(WIRE);
    frame.push(kind);
    frame.extend_from_slice(to);
    frame.extend_from_slice(id);
    frame.extend_from_slice(&(body.len() as u16).to_be_bytes());
    frame.extend_from_slice(body);
    frame.resize(WIRE, 0);
    Some(frame)
}

/// Reads a frame. The body of a delivery or reply is only as long as its length word says.
fn unwrap(bytes: &[u8]) -> Option<Frame> {
    if bytes.len() != WIRE {
        return None;
    }
    match bytes[0] {
        PACKET => Some(Frame::Packet(bytes[33..].to_vec())),
        PASS => Some(Frame::Pass { next: bytes[1..33].try_into().ok()?, packet: bytes[33..].to_vec() }),
        kind @ (DELIVERY | REPLY | ACK | HELLO) => {
            let to: [u8; 32] = bytes[1..33].try_into().ok()?;
            let id: [u8; ID] = bytes[33..49].try_into().ok()?;
            let end = 51 + u16::from_be_bytes([bytes[49], bytes[50]]) as usize;
            if end > WIRE {
                return None;
            }
            Some(Frame::Hand { kind, to, id, body: bytes[51..end].to_vec() })
        }
        _ => None,
    }
}

// ---------- small pieces the tests can reach ----------

/// The frames a mix takes from one peer: a window of one second with room for a burst in it.
#[derive(Default)]
struct Take {
    second: Option<Instant>,
    frames: u32,
}

impl Take {
    fn take(&mut self, now: Instant, rate: u32) -> bool {
        if self.second.map_or(true, |s| now.duration_since(s) >= Duration::from_secs(1)) {
            self.second = Some(now);
            self.frames = 0;
        }
        self.frames += 1;
        self.frames <= rate
    }
}

/// What a peer is using of us right now: its streams and its frame rate.
#[derive(Default)]
struct Use {
    streams: u32,
    take: Take,
    mix: bool, // a mix node: it gets MIX_RATE
}

/// Reads whole frames off a stream at the peer's rate and hands them to the engine, until the stream ends, the
/// engine is gone or the peer sends faster than RATE. Inbound streams and our own links to mixes both use it: a
/// gateway answers its client on the stream the client opened.
async fn read_frames<R: tokio::io::AsyncRead + Unpin>(
    mut read: R,
    peer: PeerId,
    stream: u64,
    msg: &mpsc::UnboundedSender<Msg>,
    stats: &Mutex<HashMap<PeerId, Use>>,
) {
    let mut buf: Vec<u8> = Vec::with_capacity(WIRE);
    let mut chunk = [0u8; 4096];
    loop {
        while buf.len() < WIRE {
            // never past the frame's end, so the next frame starts where it should
            let want = (WIRE - buf.len()).min(chunk.len());
            match read.read(&mut chunk[..want]).await {
                Ok(0) | Err(_) => return,
                Ok(n) => buf.extend_from_slice(&chunk[..n]),
            }
        }
        // the frame rate is per peer, not per stream, and over it the stream is closed
        let ok = {
            let mut stats = stats.lock().unwrap();
            let used = stats.entry(peer).or_default();
            let rate = if used.mix { MIX_RATE } else { RATE };
            used.take.take(Instant::now(), rate)
        };
        if !ok || msg.send(Msg::In(peer, stream, std::mem::take(&mut buf))).is_err() {
            return;
        }
    }
}

/// A Poisson process's wait: an exponential draw around `mean`.
fn exp(mean: Duration) -> Duration {
    let u = 1.0 - rand::random::<f64>(); // in (0, 1], so the logarithm always exists
    let secs = -mean.as_secs_f64() * u.ln();
    Duration::from_secs_f64(secs.clamp(0.001, mean.as_secs_f64() * 10.0))
}

/// Two things picked at random from `v`, never the same one twice.
fn pick_two<T>(mut v: Vec<T>) -> Option<(T, T)> {
    if v.len() < 2 {
        return None;
    }
    let i = (rand::random::<u64>() as usize) % v.len();
    let j = 1 + (rand::random::<u64>() as usize) % (v.len() - 1);
    v.swap(0, i);
    v.swap(1, j);
    Some((v.swap_remove(0), v.swap_remove(0)))
}

/// The network a mix stands on, for counting independent ones: a /16 in IPv4, a /32 in IPv6.
pub(crate) fn net_of(a: &Multiaddr) -> Option<[u8; 5]> {
    for p in a.iter() {
        match p {
            Protocol::Ip4(ip) => return Some([4, 0, 0, ip.octets()[0], ip.octets()[1]]),
            Protocol::Ip6(ip) => { let o = ip.octets(); return Some([6, o[0], o[1], o[2], o[3]]) }
            _ => {}
        }
    }
    None
}

/// How many independent networks the mixes stand on, counted by mix, not by address: each mix by its public
/// internet addresses only (home, VPN, loopback and relay addresses say nothing about who runs it), and mixes that
/// share a network count once, as one operator may run them all. A mix behind a router counts by the networks its
/// descriptor claims (Known::nets). A mix with neither does not count: it cannot be claimed as someone independent.
fn networks(mixes: impl Iterator<Item = HashSet<[u8; 5]>>) -> usize {
    let mut groups: Vec<HashSet<[u8; 5]>> = Vec::new();
    for mut mine in mixes {
        if mine.is_empty() { continue }
        let mut i = 0;
        while i < groups.len() {
            if groups[i].is_disjoint(&mine) { i += 1 } else { mine.extend(groups.swap_remove(i)) }
        }
        groups.push(mine);
    }
    groups.len()
}

/// A part of a message: its message's id, its index, how many parts there are, and what it carries.
fn read_part(part: &[u8]) -> Option<([u8; ID], usize, usize, &[u8])> {
    if part.len() < PART_HEAD {
        return None;
    }
    let id: [u8; ID] = part[..ID].try_into().ok()?;
    let index = u16::from_be_bytes([part[ID], part[ID + 1]]) as usize;
    let count = u16::from_be_bytes([part[ID + 2], part[ID + 3]]) as usize;
    let body = &part[PART_HEAD..];
    (count >= 1 && count <= LONGEST / PART && index < count && body.len() <= PART).then_some((id, index, count, body))
}

type Partial = HashMap<[u8; ID], (Instant, Vec<Option<Vec<u8>>>)>;

/// Takes one part of a message; gives back the whole message once its last part is in.
fn take_part(partial: &mut Partial, part: &[u8]) -> Option<([u8; ID], Vec<u8>)> {
    let Some((id, index, count, body)) = read_part(part) else {
        debug!("mix: a part we cannot read");
        return None;
    };
    if count == 1 {
        return Some((id, body.to_vec()));
    }
    if !partial.contains_key(&id) && partial.len() >= PARTIAL {
        let oldest = partial.iter().min_by_key(|(_, (at, _))| *at).map(|(k, _)| *k);
        if let Some(k) = oldest { partial.remove(&k); }
    }
    let (_, parts) = partial.entry(id).or_insert_with(|| (Instant::now(), vec![None; count]));
    if parts.len() != count {
        return None; // parts that disagree on their count: not from one honest sender
    }
    parts[index] = Some(body.to_vec());
    if !parts.iter().all(Option::is_some) {
        return None;
    }
    let (_, parts) = partial.remove(&id)?;
    Some((id, parts.into_iter().flatten().flatten().collect()))
}

/// The parts `send` makes of a message.
fn parts(id: &[u8; ID], message: &[u8]) -> Vec<Vec<u8>> {
    let chunks: Vec<&[u8]> = if message.is_empty() { vec![message] } else { message.chunks(PART).collect() };
    chunks.iter().enumerate()
        .map(|(i, chunk)| [&id[..], &(i as u16).to_be_bytes(), &(chunks.len() as u16).to_be_bytes(), chunk].concat())
        .collect()
}

/// The public networks one mix stands on.
fn nets(addrs: &[Multiaddr]) -> HashSet<[u8; 5]> {
    addrs.iter().filter(|a| crate::worldwide(a)).filter_map(net_of).collect()
}

pub(crate) fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn unhex(text: &str) -> Option<Vec<u8>> {
    let text = text.trim();
    if text.len() % 2 != 0 {
        return None;
    }
    (0..text.len() / 2).map(|i| u8::from_str_radix(&text[2 * i..2 * i + 2], 16).ok()).collect()
}

/// The mixnet's own state on disk, two lines: the seed its daily keys come from, and the gateway it keeps.
/// Returns whether the file was missing, so a first run writes the seed it just made.
fn load_state(file: &str) -> ([u8; 32], Option<PeerId>, bool) {
    let text = std::fs::read_to_string(file).unwrap_or_default();
    let mut lines = text.lines();
    let seed: [u8; 32] = lines
        .next()
        .and_then(|l| unhex(l))
        .and_then(|b| b.try_into().ok())
        .unwrap_or_else(|| rand::random());
    let gateway = lines.next().and_then(|l| l.trim().parse().ok());
    (seed, gateway, text.is_empty())
}

fn save_state(file: &str, seed: &[u8; 32], gateway: Option<&PeerId>) {
    let text = format!(
        "{}\n{}\n",
        hex(seed),
        gateway.map(|p| p.to_string()).unwrap_or_else(|| "-".into())
    );
    let tmp = format!("{file}.tmp");
    if std::fs::write(&tmp, text).is_ok() {
        let _ = std::fs::rename(&tmp, file);
    }
}

// ---------- the engine's channels ----------

/// What the node's own event loop tells the mixnet.
pub enum Event {
    /// this node is (or is not) a reachable helper, so it may serve as a mix node
    Reach(bool),
    /// a mix node's record from the DHT: its publisher, its bytes, and the addresses seen for it so far
    Descriptor(PeerId, Vec<u8>, Vec<Multiaddr>),
    /// the networks this node stands on as its peers see it, when it has no public address of its own: a mix
    /// behind a router claims them in its descriptor
    Nets(Vec<[u8; 5]>),
    /// the DHT took (or refused) our mix descriptor
    Stored(bool),
    /// the phone moved to another network: every link it had is dead, though writes into one still succeed for a
    /// while (the gateway never saw a message sent half a minute after Wi-Fi went off)
    Moved,
}

/// One message that came out of the mixnet, waiting in the inbox for the app.
#[derive(Clone, Debug)]
pub enum In {
    /// a message addressed to this node
    Delivered { id: [u8; ID], message: Vec<u8> },
    /// a reply to one of our reply blocks, opened with the recovery material we kept for it
    Replied { id: [u8; ID], message: Vec<u8> },
}

/// What the app can see of the mixnet: the honest numbers for the Protection setting.
pub struct Look {
    pub mixes: usize,
    pub networks: usize,
    pub serving: bool,
    pub cover: bool,
    /// cover loops sent, and how many came back: loops that never return are packets the mixnet dropped
    pub loops_sent: u64,
    pub loops_back: u64,
    /// the gateway kept for weeks went away and is not replaced yet: nothing can be sent until it is (GONE)
    pub gateway_gone: bool,
}

/// What the app asks of the engine.
enum Ask {
    Send { to: PeerId, message: Vec<u8>, done: oneshot::Sender<Result<(), String>> },
    Reply { block: Vec<u8>, message: Vec<u8>, done: oneshot::Sender<Result<(), String>> },
    Surb(oneshot::Sender<Result<Vec<u8>, String>>),
    Cover(Option<Duration>, bool),
    Serve(bool),
    Look(oneshot::Sender<Look>),
    Nodes(oneshot::Sender<Vec<PeerId>>),
}

/// What the engine's own tasks report back.
enum Msg {
    /// a frame arrived on a stream: an inbound one by its number, or 0 for one of our own links
    In(PeerId, u64, Vec<u8>),
    /// an inbound stream ended
    Ended(PeerId, u64),
    /// a stream to a mix is ready to carry frames, opened while the network had this generation
    Open(PeerId, mpsc::UnboundedSender<Vec<u8>>, u64),
    /// opening a stream to a mix failed
    Shut(PeerId),
}

// ---------- the engine ----------

/// An inbound stream, kept for writing back on. One peer may have several at once: a client's long-lived link to us
/// as its gateway, and short streams on which it, as an exit, hands us deliveries. A reply goes on the client link,
/// an acknowledgement on the stream its delivery came by; ending one stream must not lose the others.
struct Session {
    stream: u64,
    back: mpsc::UnboundedSender<Vec<u8>>,
    client: bool, // packets came on it: it is the peer's link to us as its gateway
}

/// A mix node as this node knows it: its verified, fresh descriptor and the addresses it was reached on.
struct Known {
    node: MixNode,
    addrs: Vec<Multiaddr>,
}

impl Known {
    /// The networks it stands on: its public addresses, and those it claims from behind a router.
    fn nets(&self) -> HashSet<[u8; 5]> {
        let mut n = nets(&self.addrs);
        n.extend(self.node.nets.iter().copied());
        n
    }
}

/// Everything the mixnet does, in one task that owns it all: the streams to the mixes, the packets held for their
/// delays, the cover clock, the replay-checked processing, and the inbox.
struct Engine {
    address: [u8; 32],         // this node as a Sphinx destination
    key: identity::Keypair,    // signs our mix descriptor, opens deliveries sealed for us
    seed: [u8; 32],
    mixer: Mixer,              // our daily keys and the replays we have seen
    cmd: mpsc::UnboundedSender<Cmd>,  // the node's loop: DHT work and publishing
    msg: mpsc::UnboundedSender<Msg>,  // our own tasks report back here
    control: libp2p_stream::Control,
    file: String,              // the seed and the gateway live here
    inbox: Arc<Mutex<VecDeque<In>>>,
    stats: Arc<Mutex<HashMap<PeerId, Use>>>,
    mixes: HashMap<PeerId, Known>,
    gateway: Option<PeerId>,  // kept for weeks, like a Tor guard
    lost: Option<Instant>,     // the gateway's fresh descriptor went missing at this time
    serve_allowed: bool,       // the loop says we are a reachable helper
    serve_on: bool,            // the owner has not turned serving off
    published: Option<(u64, Instant)>, // the day and time of our last descriptor the DHT took
    own_nets: Vec<[u8; 5]>,    // the networks we claim from behind a router (Event::Nets)
    tried: Option<Instant>,    // our last attempt, whatever came of it
    app_cover: Option<Duration>,
    app_loops: bool,           // the app's clock fills empty slots with cover loops (false: it only paces)
    cover_at: Instant,         // the next cover slot
    queue: VecDeque<Vec<u8>>,  // real packets waiting for a cover slot
    held: Vec<(Instant, PeerId, Vec<u8>)>, // frames held for their delays
    links: HashMap<PeerId, mpsc::UnboundedSender<Vec<u8>>>,
    pending: HashMap<PeerId, Vec<Vec<u8>>>,
    opening: HashSet<PeerId>,
    wait: HashMap<PeerId, Instant>,
    fails: HashMap<PeerId, u32>,  // failed tries at a link, since the last one that opened
    down: HashMap<PeerId, Instant>, // mixes left out of paths until then: they refused links
    loops: HashSet<[u8; ID]>,      // our cover loops still out, by identifier
    loop_order: VecDeque<[u8; ID]>,
    loops_sent: u64,
    loops_back: u64,
    asked: Option<Instant>,        // when we last asked the DHT for mix nodes out of turn
    delivered: VecDeque<[u8; ID]>, // ids of the deliveries that landed here lately
    partial: Partial,              // messages whose parts are still coming in
    moved_at: Option<Instant>,     // when the phone last changed networks
    generation: u64,               // how many times it has; a link opened before the last move is not used
    sessions: HashMap<PeerId, Vec<Session>>, // inbound streams, for replies to clients and acknowledgements
    streams: u64,                  // inbound streams numbered so far
    recoveries: HashMap<[u8; ID], Vec<u8>>, // reply blocks we handed out, by their identifier
    order: VecDeque<[u8; ID]>,
}

impl Engine {
    async fn run(
        mut self,
        mut ask: mpsc::UnboundedReceiver<Ask>,
        mut events: mpsc::UnboundedReceiver<Event>,
        mut msg: mpsc::UnboundedReceiver<Msg>,
        mut incoming: libp2p_stream::IncomingStreams,
    ) {
        let mut slow = tokio::time::interval(Duration::from_secs(60));
        let mut flap = tokio::time::interval(Duration::from_secs(1));
        loop {
            let cover = self.cover_mean();
            let cover_at = cover.map(|_| self.cover_at);
            let held_at = self.held.iter().map(|(at, _, _)| *at).min();
            tokio::select! {
                _ = slow.tick() => {
                    self.prune();
                    if self.serving() { self.publish(); }
                }
                _ = flap.tick() => {
                    self.retry();
                    // until the DHT takes our descriptor, keep trying: it may not have been ready at start-up
                    if self.serving() && self.published.is_none() { self.publish(); }
                }
                _ = tokio::time::sleep_until(tokio::time::Instant::from_std(
                    cover_at.unwrap_or_else(|| Instant::now() + FAR))), if cover.is_some() => {
                    if let Some(mean) = cover { self.cover_at = Instant::now() + exp(mean); }
                    self.emit();
                }
                _ = tokio::time::sleep_until(tokio::time::Instant::from_std(
                    held_at.unwrap_or_else(|| Instant::now() + FAR))), if held_at.is_some() => {
                    self.drain();
                }
                Some((peer, stream)) = incoming.next() => self.arrive(peer, stream),
                Some(a) = ask.recv() => self.ask(a),
                Some(e) = events.recv() => self.event(e),
                Some(m) = msg.recv() => self.note(m),
            }
        }
    }

    fn ask(&mut self, a: Ask) {
        match a {
            Ask::Send { to, message, done } => { let _ = done.send(self.send(to, &message)); }
            Ask::Reply { block, message, done } => { let _ = done.send(self.answer(&block, &message)); }
            Ask::Surb(done) => { let _ = done.send(self.surb()); }
            Ask::Cover(mean, loops) => { self.app_cover = mean; self.app_loops = loops; self.refresh(); }
            Ask::Serve(on) => { self.serve_on = on; self.refresh(); }
            Ask::Look(done) => {
                self.prune();
                self.want_mixes();
                let _ = done.send(Look {
                    mixes: self.usable().count(),
                    networks: networks(self.usable().map(|(_, k)| k.nets())),
                    serving: self.serving(),
                    cover: self.cover_mean().is_some(),
                    loops_sent: self.loops_sent,
                    loops_back: self.loops_back,
                    gateway_gone: self.gateway.is_some_and(|g| !self.mixes.contains_key(&g) && !self.down.contains_key(&g)),
                });
            }
            Ask::Nodes(done) => {
                self.prune();
                let _ = done.send(self.usable().map(|(p, _)| *p).collect());
            }
        }
    }

    fn event(&mut self, e: Event) {
        match e {
            Event::Reach(on) => {
                if self.serve_allowed != on {
                    self.serve_allowed = on;
                    self.refresh();
                }
            }
            Event::Moved => {
                // drop every link and stream, and the connections under them, so the next frame opens a fresh
                // link on the new network; the gateway is kept, and frames still waiting go out on the new link
                let peers: Vec<PeerId> = self.links.drain().map(|(p, _)| p)
                    .chain(self.sessions.drain().map(|(p, _)| p)).collect::<HashSet<_>>().into_iter().collect();
                self.wait.clear();
                self.fails.clear();
                self.down.clear(); // mixes unreachable from the old network may answer on this one
                debug!("mix: moved, {} links dropped", peers.len());
                if !peers.is_empty() { let _ = self.cmd.send(Cmd::Disconnect(peers)); }
                // the link to the gateway comes back now, not when the next packet waits for it; links still being
                // opened belong to the old network and are opened again when they report (the generation)
                self.moved_at = Some(Instant::now());
                self.generation += 1;
                self.opening.clear();
                if let Some(g) = self.gateway { self.spawn_open(g); }
                self.retry();
            }
            Event::Nets(n) => {
                let n: Vec<[u8; 5]> = n.into_iter().take(mix::NETS).collect();
                if n != self.own_nets {
                    debug!("mix: our networks as peers see us: {} known", n.len());
                    self.own_nets = n;
                    // a descriptor with the new claim goes out now, not at the next renewal
                    if self.serving() { self.published = None; self.tried = None; self.publish(); }
                }
            }
            Event::Stored(ok) => {
                if !ok {
                    self.published = None; // try again: the DHT was not ready for it
                }
            }
            Event::Descriptor(peer, bytes, addrs) => {
                // a stale copy from a slow DHT node, or junk anyone may store under the key, never takes a mix
                // away: the mix stays with the freshest descriptor seen until its key expires (prune)
                let Some(node) = mix::read_descriptor(&peer, &bytes) else { return };
                if !node.fresh(now_secs()) { return } // signed too long ago: its mix may be long gone
                let known = self.mixes.entry(peer).or_insert_with(|| Known { node: node.clone(), addrs: Vec::new() });
                for a in addrs {
                    if !known.addrs.contains(&a) { known.addrs.push(a); }
                }
                if node.published >= known.node.published { known.node = node; }
                if self.gateway == Some(peer) { self.lost = None; }
                self.stats.lock().unwrap().entry(peer).or_default().mix = true;
            }
        }
    }

    fn note(&mut self, m: Msg) {
        match m {
            Msg::In(peer, stream, frame) => self.frame(peer, stream, frame),
            Msg::Ended(peer, stream) => {
                if let Some(all) = self.sessions.get_mut(&peer) {
                    all.retain(|s| s.stream != stream && !s.back.is_closed());
                    if all.is_empty() { self.sessions.remove(&peer); }
                }
            }
            Msg::Open(peer, link, generation) => {
                self.opening.remove(&peer);
                if generation != self.generation {
                    // opened on the network the phone has left: frames sent into it would be lost
                    debug!("mix: a link to {peer} opened on the old network, opening it again");
                    drop(link);
                    if self.pending.contains_key(&peer) || self.gateway == Some(peer) { self.spawn_open(peer); }
                    return;
                }
                self.wait.remove(&peer);
                self.fails.remove(&peer);
                self.down.remove(&peer);
                let waiting = self.pending.remove(&peer).unwrap_or_default();
                debug!("mix: link to {peer} open, {} frames waiting go out", waiting.len());
                for frame in waiting { let _ = link.send(frame); }
                self.links.insert(peer, link);
            }
            Msg::Shut(peer) => {
                self.opening.remove(&peer);
                debug!("mix: no link to {peer}, {} frames waiting", self.pending.get(&peer).map_or(0, |p| p.len()));
                self.wait.insert(peer, Instant::now() + WAIT);
                // a mix that will not take a link after a few tries loses what waited for it, so a dead hop
                // named in a packet is not retried forever
                let fails = self.fails.entry(peer).or_default();
                *fails += 1;
                if *fails >= TRIES {
                    self.fails.remove(&peer);
                    self.down.insert(peer, Instant::now() + DOWN);
                    if let Some(lost) = self.pending.remove(&peer) {
                        debug!("mix: {} frames for {peer} dropped, it would not take a link", lost.len());
                    }
                }
            }
        }
    }

    // ----- sending: paths, the clock, the gateway -----

    /// Sends `message` to `to` through the mixnet. Says the packet was queued, not that it arrived: the mixnet
    /// delivers on effort, and answers come back as replies to reply blocks.
    fn send(&mut self, to: PeerId, message: &[u8]) -> Result<(), String> {
        if message.len() > LONGEST {
            return Err(format!("a message through the mixnet is at most {LONGEST} bytes"));
        }
        let to = mix::peer_address(&to).ok_or("that peer cannot be addressed in the mixnet")?;
        // a long message goes as several packets, each by its own path; every packet is the same size anyway
        let id: [u8; ID] = rand::random();
        let mut packets = Vec::new();
        for part in parts(&id, message) {
            // sealed for the receiver: the last mix learns where it goes, never what it says
            let sealed = mix::seal(&to, &part)?;
            packets.push(mix::packet(&self.path()?, Service::Deliver, to, &sealed, mix::MEAN_DELAY)?);
        }
        for packet in packets { self.go(wrap(&packet)); }
        Ok(())
    }

    /// Answers through someone's reply block. The block's maker chose its first mix and may run it, so the packet
    /// never goes there from this node: our own gateway passes it on, and that mix sees only the gateway.
    fn answer(&mut self, block: &[u8], message: &[u8]) -> Result<(), String> {
        if message.len() > MESSAGE {
            return Err(format!("a packet carries at most {MESSAGE} bytes"));
        }
        let (packet, first) = mix::use_reply_block(block, message)?;
        let next = mix::peer(&first).ok_or("the return path starts at a peer we cannot address")?;
        let gateway = self.guard()?;
        // a block whose route starts at our own gateway needs no passing on
        self.go(if next == gateway { wrap(&packet) } else { pass(PASS, &first, &packet) });
        Ok(())
    }

    /// A frame for the mixnet: in the next slot of the sender's clock while it runs, at once otherwise. A serving
    /// mix sends its own packets (a host's answers) at once: they leave among the many it forwards for others, all
    /// alike, and a clock at the helper's loop rate let a host answer one request every two seconds at most.
    fn go(&mut self, frame: Vec<u8>) {
        if self.cover_mean().is_some() && !self.serving() { self.queue.push_back(frame); } else { self.out(frame); }
    }

    /// Makes a reply block for this node: embed it in a message, and the answer comes back through the mixnet and
    /// lands in the inbox. We keep the material to open it, by the block's identifier.
    fn surb(&mut self) -> Result<Vec<u8>, String> {
        let path = self.path()?;
        // the return path ends at our gateway, which hands us the reply over our own stream to it
        let back = vec![path[1].clone(), path[2].clone(), path[0].clone()];
        let (block, recovery) = mix::reply_block(&back, self.address, mix::MEAN_DELAY)?;
        if let Some(id) = mix::recovery_id(&recovery) {
            if self.recoveries.len() >= BLOCKS {
                if let Some(old) = self.order.pop_front() { self.recoveries.remove(&old); }
            }
            self.order.push_back(id);
            self.recoveries.insert(id, recovery);
        }
        Ok(block)
    }

    /// Three distinct fresh mixes, the gateway first: the guard kept for weeks, and two chosen for this packet.
    /// The three stand on three different networks whenever the mixes known allow it, so one operator's network
    /// is not the whole path.
    fn path(&mut self) -> Result<Vec<MixNode>, String> {
        let gateway = self.guard()?;
        let known = self.mixes.get(&gateway).ok_or("no mix nodes known yet")?;
        let (guard, guard_nets) = (known.node.clone(), known.nets());
        let others: Vec<(MixNode, HashSet<[u8; 5]>)> = self.usable()
            .filter(|(_, k)| k.node.address != guard.address)
            .map(|(_, k)| (k.node.clone(), k.nets()))
            .collect();
        let apart = |a: &HashSet<[u8; 5]>, b: &HashSet<[u8; 5]>| !a.is_empty() && !b.is_empty() && a.is_disjoint(b);
        for _ in 0..32 {
            let Some(((a, an), (b, bn))) = pick_two(others.clone()) else { break };
            if apart(&guard_nets, &an) && apart(&guard_nets, &bn) && apart(&an, &bn) {
                return Ok(vec![guard, a, b]);
            }
        }
        let (a, b) = pick_two(others).ok_or("too few mix nodes for a path")?;
        Ok(vec![guard, a.0, b.0])
    }

    /// The fresh mixes other than `guard`.
    fn others(&self, guard: &MixNode) -> Vec<MixNode> {
        self.usable().filter(|(_, k)| k.node.address != guard.address).map(|(_, k)| k.node.clone()).collect()
    }

    /// The gateway everything we send leaves through: the one kept, while it is fresh or only briefly missing,
    /// else a new one.
    fn guard(&mut self) -> Result<PeerId, String> {
        self.prune();
        self.want_mixes();
        match self.gateway {
            Some(g) if self.mixes.contains_key(&g) && !self.down.contains_key(&g) => self.lost = None,
            // a gateway that keeps refusing links is given up at once: waiting would only lose packets
            Some(g) if self.down.contains_key(&g) => self.pick_gateway()?,
            Some(_) => {
                let gone = self.lost.get_or_insert_with(Instant::now).elapsed();
                if gone < GONE {
                    return Err("the gateway mix is not available".into());
                }
                self.pick_gateway()?;
            }
            None => self.pick_gateway()?,
        }
        Ok(self.gateway.expect("a gateway was just picked"))
    }

    /// Mixes whose key no longer opens packets are let go: after a day change, once the overlap hour is over.
    /// Mixes whose newest descriptor was signed over STALE ago go too (they stopped renewing it: they went away),
    /// and so do the marks of mixes that were down once their time is over.
    fn prune(&mut self) {
        let now = now_secs();
        let days = MixKeys::days(now);
        self.mixes.retain(|_, k| days.contains(&k.node.day) && now.saturating_sub(k.node.published) < STALE);
        let now = Instant::now();
        self.down.retain(|_, until| *until > now);
        self.partial.retain(|_, (at, _)| at.elapsed() < PARTIAL_LIFE);
    }

    /// Too few mixes for a path: ask the DHT now rather than at the next five-minute round, at most every 30 s.
    /// After every mix went away (a restart, an outage), a phone otherwise waited minutes before it could send.
    fn want_mixes(&mut self) {
        if self.usable().count() >= 3 || self.asked.is_some_and(|t| t.elapsed() < ASK_AGAIN) {
            return;
        }
        self.asked = Some(Instant::now());
        let _ = self.cmd.send(Cmd::Mixes);
    }

    /// The mixes a path may use: known, fresh, and not refusing links lately.
    fn usable(&self) -> impl Iterator<Item = (&PeerId, &Known)> {
        self.mixes.iter().filter(|(p, _)| !self.down.contains_key(*p))
    }

    fn pick_gateway(&mut self) -> Result<(), String> {
        let all: Vec<PeerId> = self.usable().map(|(p, _)| *p).collect();
        if all.is_empty() {
            return Err("no mix nodes known yet".into());
        }
        self.gateway = Some(all[(rand::random::<u64>() as usize) % all.len()]);
        self.lost = None;
        save_state(&self.file, &self.seed, self.gateway.as_ref());
        Ok(())
    }

    /// One slot of the sender's clock: a real packet if one is waiting, a cover loop if not.
    fn emit(&mut self) {
        if let Some(frame) = self.queue.pop_front() {
            self.out(frame);
        } else if self.serving() || self.app_loops {
            if let Some(frame) = self.loop_packet() { self.out(frame); }
        }
    }

    /// A cover packet, as in Loopix a loop back to ourselves: out through the gateway and another mix, back to the
    /// gateway, which hands it to us like a reply. Loops that never return show packets being dropped.
    fn loop_packet(&mut self) -> Option<Vec<u8>> {
        let gateway = self.guard().ok()?;
        let guard = self.mixes.get(&gateway)?.node.clone();
        let mut others = self.others(&guard);
        if others.is_empty() { return None }
        let middle = others.swap_remove((rand::random::<u64>() as usize) % others.len());
        let (packet, id) = mix::packet_with_id(&[guard.clone(), middle, guard], Service::Reply, self.address, b"",
                                               mix::MEAN_DELAY).ok()?;
        if self.loops.len() >= BLOCKS {
            if let Some(old) = self.loop_order.pop_front() { self.loops.remove(&old); }
        }
        self.loops.insert(id);
        self.loop_order.push_back(id);
        self.loops_sent += 1;
        Some(wrap(&packet))
    }

    /// Everything this node sends into the mixnet leaves through its gateway.
    fn out(&mut self, frame: Vec<u8>) {
        if let Some(gateway) = self.gateway {
            self.push(gateway, frame);
        }
    }

    /// The cover clock and our serving follow the settings; called whenever either may have changed.
    fn refresh(&mut self) {
        if let Some(mean) = self.cover_mean() {
            self.cover_at = Instant::now() + exp(mean);
        } else {
            // no clock: real packets go at once, and any waiting ones too
            for frame in std::mem::take(&mut self.queue) { self.out(frame); }
        }
        if self.serving() {
            self.publish();
        } else if self.published.take().is_some() {
            let _ = self.cmd.send(Cmd::MixStop);
        }
    }

    /// Publish our descriptor for today: again RENEW after the DHT took one, and five seconds after a
    /// refused try; the record lives two hours.
    fn publish(&mut self) {
        let today = now_secs() / mix::DAY;
        if let Some((day, at)) = self.published {
            if day == today && at.elapsed() < RENEW {
                return;
            }
        }
        if self.tried.map_or(false, |t| t.elapsed() < Duration::from_secs(5)) {
            return;
        }
        self.tried = Some(Instant::now());
        if let Ok(record) = mix::descriptor(&self.key, self.mixer.keys(), today, now_secs(), &self.own_nets) {
            if self.cmd.send(Cmd::MixPublish(record)).is_ok() {
                // optimistic until the loop says the DHT refused it
                self.published = Some((today, Instant::now()));
            }
        }
    }

    fn serving(&self) -> bool { self.serve_allowed && self.serve_on }

    /// A helper loops all the time; a phone loops at the rate the app set while Protection is on.
    fn cover_mean(&self) -> Option<Duration> {
        if self.serving() { Some(HELPER_LOOP) } else { self.app_cover }
    }

    // ----- serving: packets in, delays, exits -----

    /// One frame from a peer: a packet to process (we may be a mix), or something addressed to us.
    fn frame(&mut self, peer: PeerId, stream: u64, bytes: Vec<u8>) {
        debug!("mix: a frame from {peer}");
        let frame = unwrap(&bytes);
        if matches!(frame, Some(Frame::Packet(_)) | Some(Frame::Pass { .. })) {
            // packets come only on a client's link to its gateway (or a mix's to the next): replies go on it
            if let Some(s) = self.sessions.get_mut(&peer).and_then(|all| all.iter_mut().find(|s| s.stream == stream)) {
                s.client = true;
            }
        }
        match frame {
            Some(Frame::Packet(sphinx)) => match self.mixer.process(&sphinx, now_secs()) {
                Ok(Out::Forward { next, delay, frame }) => match mix::peer(&next) {
                    Some(p) => self.hold(delay, p, wrap(&frame)),
                    None => debug!("mix: a hop we cannot address"),
                },
                Ok(Out::Final { service, to, id, payload }) => match service {
                    Service::Loop => {} // cover: it ends here
                    Service::Deliver => { debug!("mix: exit for a delivery"); self.deliver(to, id, payload) }
                    Service::Adopt => debug!("mix: an adopted chunk, but adoption is not built yet"),
                    Service::Reply => self.reply(to, id, payload),
                },
                Err(e) => debug!("mix: {e}"),
            },
            Some(Frame::Pass { next, packet }) => {
                // a client's answer through a reply block: we pass it on as its gateway, after a delay of our own
                // so it leaves among the other packets we hold
                if !self.serving() {
                    debug!("mix: asked to pass a packet on, but we are not a mix");
                } else if let Some(p) = mix::peer(&next) {
                    self.hold(exp(mix::MEAN_DELAY), p, wrap(&packet));
                }
            }
            Some(Frame::Hand { kind: DELIVERY, to, id, body }) if to == self.address => {
                // acknowledge on the stream it came by: the exit counts it delivered only then, since a write
                // into a dead connection (a phone that just left Wi-Fi) looks just like one that arrived
                if let Some(ack) = hand(ACK, &to, &id, b"") {
                    if let Some(s) = self.sessions.get(&peer).and_then(|all| all.iter().find(|s| s.stream == stream)) {
                        let _ = s.back.send(ack);
                    }
                }
                if !self.delivered.contains(&id) {
                    if self.delivered.len() >= DELIVERED { self.delivered.pop_front(); }
                    self.delivered.push_back(id);
                    self.opened(&body);
                }
            }
            Some(Frame::Hand { kind: ACK | HELLO, .. }) => {} // read by the task that opened the stream
            Some(Frame::Hand { kind: REPLY, to, id, .. }) if to == self.address && self.loops.remove(&id) => {
                // one of our cover loops, back through the gateway
                self.loop_order.retain(|o| *o != id);
                self.loops_back += 1;
            }
            Some(Frame::Hand { kind: REPLY, to, id, body }) if to == self.address => {
                match self.recoveries.remove(&id) {
                    Some(recovery) => match mix::open_reply(&recovery, &body) {
                        Ok(message) => {
                            self.order.retain(|o| *o != id);
                            self.land(In::Replied { id, message });
                        }
                        Err(e) => debug!("mix: a reply would not open: {e}"),
                    },
                    None => debug!("mix: a reply for a block we no longer hold"),
                }
            }
            _ => debug!("mix: a frame we cannot read"),
        }
    }

    /// The last hop of a delivery: hand the message to the peer it names, over a stream to it.
    fn deliver(&mut self, to: [u8; 32], id: [u8; ID], payload: Vec<u8>) {
        if to == self.address {
            self.opened(&payload);
            return;
        }
        let Some(peer) = mix::peer(&to) else { return };
        let Some(frame) = hand(DELIVERY, &to, &id, &payload) else { return };
        let (cmd, mut control) = (self.cmd.clone(), self.control.clone());
        tokio::spawn(async move {
            // a receiver that just changed networks (Wi-Fi to mobile data) still looks connected on the old one
            // until ping gives up, about half a minute: the later tries wait that out and look it up afresh, so
            // its new relay address is used
            for (attempt, wait) in DELIVERY_WAITS.into_iter().enumerate() {
                tokio::time::sleep(wait).await;
                if !reach(&cmd, peer, attempt > 0).await {
                    debug!("mix: delivery to {peer}, try {}: not found", attempt + 1);
                    continue;
                }
                let stream = match tokio::time::timeout(Duration::from_secs(20), control.open_stream(peer, MIX)).await {
                    Ok(Ok(stream)) => stream,
                    Ok(Err(e)) => { debug!("mix: delivery to {peer}, try {}: {e}", attempt + 1); continue }
                    Err(_) => { debug!("mix: delivery to {peer}, try {}: no stream in 20 s", attempt + 1); continue }
                };
                let mut s = stream.compat();
                if s.write_all(&frame).await.is_err() || s.flush().await.is_err() {
                    debug!("mix: delivery to {peer}, try {}: the stream broke", attempt + 1);
                    continue;
                }
                // delivered only once the receiver says so
                // the receiver says hello, then acknowledges; anything else in time means it did not arrive
                let acked = tokio::time::timeout(ACK_WAIT, async {
                    let mut back = vec![0u8; WIRE];
                    loop {
                        if s.read_exact(&mut back).await.is_err() { return false }
                        match unwrap(&back) {
                            Some(Frame::Hand { kind: HELLO, .. }) => continue,
                            Some(Frame::Hand { kind: ACK, id: got, .. }) => return got == id,
                            _ => return false,
                        }
                    }
                }).await;
                if matches!(acked, Ok(true)) {
                    debug!("mix: delivered to {peer} on try {}", attempt + 1);
                    return;
                }
                debug!("mix: delivery to {peer}, try {}: no acknowledgement", attempt + 1);
            }
            debug!("mix: gave up delivering to {peer}");
        });
    }

    /// The last hop of a return path: a gateway hands its client a reply, on the stream the client opened to it.
    fn reply(&mut self, to: [u8; 32], id: [u8; ID], sealed: Vec<u8>) {
        let Some(peer) = mix::peer(&to) else { return };
        let Some(frame) = hand(REPLY, &to, &id, &sealed) else { return };
        // the newest live link the client sends packets on; failing that, its newest live stream
        let sent = self.sessions.get_mut(&peer).is_some_and(|all| {
            all.retain(|s| !s.back.is_closed());
            let best = all.iter().rev().find(|s| s.client).or_else(|| all.last());
            best.is_some_and(|s| s.back.send(frame).is_ok())
        });
        if !sent {
            debug!("mix: a reply for a client that is not connected to us");
        }
    }

    /// Holds a frame for its delay before it goes to `peer`, while there is room.
    fn hold(&mut self, delay: Duration, peer: PeerId, frame: Vec<u8>) {
        if self.held.len() >= HELD {
            debug!("mix: full, a packet dropped");
            return;
        }
        self.held.push((Instant::now() + delay, peer, frame));
    }

    /// A delivery for us: a part of a message, once its seal opens, so nothing a mix made up or changed gets in.
    /// The message lands in the inbox when its last part does, under the message's id.
    fn opened(&mut self, sealed: &[u8]) {
        let Some(part) = mix::open_sealed(&self.key, sealed) else {
            debug!("mix: a delivery whose seal would not open");
            return;
        };
        if let Some((id, message)) = take_part(&mut self.partial, &part) {
            self.land(In::Delivered { id, message });
        }
    }

    fn land(&self, message: In) {
        let mut inbox = self.inbox.lock().unwrap();
        if inbox.len() >= INBOX { inbox.pop_front(); }
        inbox.push_back(message);
    }

    /// A stream arrived: read its frames at our rate, and keep its write half for replies to that peer.
    fn arrive(&mut self, peer: PeerId, stream: libp2p::Stream) {
        {
            let mut stats = self.stats.lock().unwrap();
            let used = stats.entry(peer).or_default();
            if used.streams >= STREAMS { return }
            used.streams += 1;
        }
        let (back, mut back_rx) = mpsc::unbounded_channel::<Vec<u8>>();
        // say hello first: the opener counts its link alive only then, since a stream opened on a connection that
        // died with the network a phone left accepts writes and delivers nothing
        let _ = back.send(hello());
        self.streams += 1;
        let number = self.streams;
        self.sessions.entry(peer).or_default().push(Session { stream: number, back, client: false });
        let (msg, stats) = (self.msg.clone(), self.stats.clone());
        tokio::spawn(async move {
            let (read, write) = tokio::io::split(stream.compat());
            // the write half waits for replies the engine sends this peer; it ends with the stream
            tokio::spawn(async move {
                let mut w = write;
                while let Some(frame) = back_rx.recv().await {
                    if w.write_all(&frame).await.is_err() { break }
                    let _ = w.flush().await;
                }
            });
            read_frames(read, peer, number, &msg, &stats).await;
            if let Some(used) = stats.lock().unwrap().get_mut(&peer) {
                used.streams = used.streams.saturating_sub(1);
            }
            let _ = msg.send(Msg::Ended(peer, number));
        });
    }

    // ----- the links: one long-lived stream per mix -----

    /// A frame for a mix: over the long-lived link if it is up, else it waits while one opens.
    fn push(&mut self, peer: PeerId, frame: Vec<u8>) {
        if let Some(link) = self.links.get(&peer) {
            if !link.is_closed() {
                let _ = link.send(frame);
                return;
            }
            self.links.remove(&peer); // its writer is gone
        }
        let waiting = self.pending.entry(peer).or_default();
        if waiting.len() < HELD_OUT { waiting.push(frame); }
        if !self.opening.contains(&peer) && self.wait.get(&peer).is_none_or(|at| Instant::now() >= *at) {
            self.spawn_open(peer);
        }
    }

    /// Once a second: the mixes whose frames are waiting get another try at a link.
    fn retry(&mut self) {
        for peer in self.pending.keys().copied().collect::<Vec<_>>() {
            if !self.opening.contains(&peer) && self.wait.get(&peer).is_none_or(|at| Instant::now() >= *at) {
                self.spawn_open(peer);
            }
        }
    }

    /// The packets whose delay has passed go on to the next mix.
    fn drain(&mut self) {
        let now = Instant::now();
        let mut i = 0;
        while i < self.held.len() {
            if self.held[i].0 <= now {
                let (_, peer, frame) = self.held.swap_remove(i);
                self.push(peer, frame);
            } else {
                i += 1;
            }
        }
    }

    /// Opens the one long-lived stream we keep to a mix; the frames that piled up go out when it is ready.
    fn spawn_open(&mut self, peer: PeerId) {
        self.opening.insert(peer);
        let generation = self.generation;
        debug!("mix: opening a link to {peer}");
        // just after a move, what is known of the mix is from the old network: look it up afresh first
        let fresh_first = self.moved_at.is_some_and(|t| t.elapsed() < Duration::from_secs(60));
        let (mut control, cmd, msg, stats) = (self.control.clone(), self.cmd.clone(), self.msg.clone(), self.stats.clone());
        tokio::spawn(async move {
            // a mix we can already reach opens at once; an unknown one is found in the DHT first, as open() does
            let mut stream = None;
            if fresh_first && reach(&cmd, peer, true).await {
                if let Ok(Ok(open)) = tokio::time::timeout(Duration::from_secs(30), control.open_stream(peer, MIX)).await {
                    stream = Some(open);
                }
            } else if let Ok(Ok(open)) = tokio::time::timeout(Duration::from_secs(10), control.open_stream(peer, MIX)).await {
                stream = Some(open);
            } else if reach(&cmd, peer, true).await {
                if let Ok(Ok(open)) = tokio::time::timeout(Duration::from_secs(30), control.open_stream(peer, MIX)).await {
                    stream = Some(open);
                }
            }
            match stream {
                Some(open) => {
                    let (tx, mut rx) = mpsc::unbounded_channel::<Vec<u8>>();
                    let (mut read, mut w) = tokio::io::split(open.compat());
                    // no frame goes out before the mix has said hello on this stream
                    let mut first = vec![0u8; WIRE];
                    let greeted = matches!(tokio::time::timeout(HELLO_WAIT, read.read_exact(&mut first)).await,
                                           Ok(Ok(_)) if matches!(unwrap(&first), Some(Frame::Hand { kind: HELLO, .. })));
                    if !greeted {
                        debug!("mix: a link to {peer} opened but the mix never said hello");
                        let _ = msg.send(Msg::Shut(peer));
                        return;
                    }
                    tokio::spawn(async move {
                        while let Some(frame) = rx.recv().await {
                            if w.write_all(&frame).await.is_err() { break }
                            let _ = w.flush().await;
                        }
                    });
                    let _ = msg.send(Msg::Open(peer, tx, generation));
                    // our gateway hands us replies and returning loops on this same stream
                    read_frames(read, peer, 0, &msg, &stats).await;
                }
                None => { let _ = msg.send(Msg::Shut(peer)); }
            }
        });
    }
}

/// Asks the node's event loop to find a peer, as an open() of HAMMER does; `fresh` asks the DHT even when the peer
/// looks known, because what is known may be a dead connection.
async fn reach(cmd: &mpsc::UnboundedSender<Cmd>, peer: PeerId, fresh: bool) -> bool {
    let (tx, rx) = oneshot::channel();
    if cmd.send(Cmd::Find(peer, tx, fresh)).is_err() { return false }
    matches!(tokio::time::timeout(Duration::from_secs(25), rx).await, Ok(Ok(true)))
}

// ---------- the handle for the app ----------

/// The mixnet for the app: send through it, ask it for a reply block, read its inbox, set its cover. Cheap to clone,
/// so a caller can let go of the node before it waits for the engine.
#[derive(Clone)]
pub struct Mixnet {
    ask: mpsc::UnboundedSender<Ask>,
    tell: mpsc::UnboundedSender<Event>,
    inbox: Arc<Mutex<VecDeque<In>>>,
    rt: tokio::runtime::Handle,
}

impl Mixnet {
    /// Starts the mixnet: the engine that moves the frames, and this handle for the app. `file` keeps the seed and
    /// the gateway between runs; `control` opens mix streams; `cmd` reaches the node's own event loop.
    pub(crate) fn start(
        file: &str,
        key: identity::Keypair,
        control: libp2p_stream::Control,
        cmd: mpsc::UnboundedSender<Cmd>,
    ) -> Result<Mixnet, String> {
        let address = mix::address(&key.public()).ok_or("the node key is not an ed25519 key")?;
        let (seed, gateway, missing) = load_state(file);
        if missing { save_state(file, &seed, gateway.as_ref()); }
        let (ask, ask_rx) = mpsc::unbounded_channel();
        let (tell, events) = mpsc::unbounded_channel();
        let (msg, msg_rx) = mpsc::unbounded_channel();
        let incoming = control.clone().accept(MIX).map_err(|e| format!("the mix protocol: {e}"))?;
        let inbox = Arc::new(Mutex::new(VecDeque::new()));
        let engine = Engine {
            address, key, seed, mixer: Mixer::new(MixKeys::new(seed)), cmd: cmd.clone(), msg,
            control, file: file.to_string(), inbox: inbox.clone(), stats: Arc::new(Mutex::new(HashMap::new())),
            mixes: HashMap::new(), gateway, lost: None, serve_allowed: false, serve_on: true, published: None,
            own_nets: Vec::new(), tried: None,
            app_cover: None, app_loops: true, cover_at: Instant::now(), queue: VecDeque::new(), held: Vec::new(),
            links: HashMap::new(), pending: HashMap::new(), opening: HashSet::new(), wait: HashMap::new(),
            fails: HashMap::new(), down: HashMap::new(), loops: HashSet::new(), loop_order: VecDeque::new(), loops_sent: 0, loops_back: 0, asked: None, delivered: VecDeque::new(), partial: HashMap::new(), moved_at: None, generation: 0,
            sessions: HashMap::new(), streams: 0, recoveries: HashMap::new(), order: VecDeque::new(),
        };
        tokio::spawn(engine.run(ask_rx, events, msg_rx, incoming));
        // mix nodes are found in the DHT like hosts are; ask at once rather than waiting for the first tick
        let _ = cmd.send(Cmd::Mixes);
        Ok(Mixnet { ask, tell, inbox, rt: tokio::runtime::Handle::current() })
    }

    /// The channel the node's own event loop tells the mixnet things on.
    pub fn tell(&self) -> mpsc::UnboundedSender<Event> { self.tell.clone() }

    /// Sends `message` to `to` through the mixnet.
    pub fn send(&self, to: PeerId, message: &[u8]) -> Result<(), String> {
        self.call(|done| Ask::Send { to, message: message.to_vec(), done }).and_then(|r| r)
    }

    /// Answers through a reply block someone sent us: the answer travels the block's own route back.
    pub fn reply(&self, block: &[u8], message: &[u8]) -> Result<(), String> {
        self.call(|done| Ask::Reply { block: block.to_vec(), message: message.to_vec(), done }).and_then(|r| r)
    }

    /// Makes a reply block for this node and keeps the material to open the reply with.
    pub fn surb(&self) -> Result<Vec<u8>, String> {
        self.call(|done| Ask::Surb(done)).and_then(|r| r)
    }

    /// The sender's clock: a packet every `mean` on average while it runs, cover loops in the empty slots.
    /// None stops the clock and real packets go at once.
    pub fn cover(&self, mean: Option<Duration>) {
        let _ = self.ask.send(Ask::Cover(mean, true));
    }

    /// The sender's clock without cover: real packets leave at most one every `mean` on average and empty slots stay
    /// empty. For an upload, which must not outrun what its gateway takes from one peer (RATE).
    pub fn pace(&self, mean: Duration) {
        let _ = self.ask.send(Ask::Cover(Some(mean), false));
    }

    /// The owner's choice whether this node serves as a mix node when it is a reachable helper.
    pub fn serve(&self, on: bool) {
        let _ = self.ask.send(Ask::Serve(on));
    }

    /// Takes everything waiting in the inbox.
    pub fn inbox(&self) -> Vec<In> {
        self.inbox.lock().unwrap().drain(..).collect()
    }

    /// The honest numbers for the Protection setting: how many mix nodes, how many independent ones.
    pub fn look(&self) -> Result<Look, String> {
        self.call(|done| Ask::Look(done))
    }

    /// The mix nodes a path may use now. They are the network's helpers, the ones a hidden song is offered to.
    pub fn nodes(&self) -> Result<Vec<PeerId>, String> {
        self.call(|done| Ask::Nodes(done))
    }

    fn call<T>(&self, make: impl FnOnce(oneshot::Sender<T>) -> Ask) -> Result<T, String> {
        let (tx, rx) = oneshot::channel();
        self.ask.send(make(tx)).map_err(|_| "the mixnet is not running".to_string())?;
        match self.rt.block_on(async { tokio::time::timeout(Duration::from_secs(5), rx).await }) {
            Ok(Ok(v)) => Ok(v),
            _ => Err("the mixnet is not answering".into()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn addr(text: &str) -> Multiaddr {
        text.parse().unwrap()
    }

    #[test]
    fn frames_round_trip() {
        let packet = wrap(&[7u8; FRAME]);
        assert_eq!(packet.len(), WIRE);
        match unwrap(&packet).unwrap() {
            Frame::Packet(p) => assert_eq!(p.len(), FRAME),
            _ => panic!(),
        }
        // every kind of frame is the same length on the wire, whatever it carries
        let delivery = hand(DELIVERY, &[1; 32], &[2; ID], b"hello through the mix").unwrap();
        let reply = hand(REPLY, &[1; 32], &[2; ID], &[9u8; MESSAGE]).unwrap();
        assert_eq!(delivery.len(), WIRE);
        assert_eq!(reply.len(), WIRE);
        match unwrap(&delivery).unwrap() {
            Frame::Hand { kind, to, id, body } => {
                assert_eq!((kind, to, id), (DELIVERY, [1; 32], [2; ID]));
                assert_eq!(body, b"hello through the mix");
            }
            _ => panic!(),
        }
        match unwrap(&reply).unwrap() {
            Frame::Hand { kind, body, .. } => {
                assert_eq!(kind, REPLY);
                assert_eq!(body.len(), MESSAGE);
            }
            _ => panic!(),
        }
        // a packet passed on by the gateway is as long as any other, and names its next hop
        let passed = pass(PASS, &[5; 32], &[7u8; FRAME]);
        assert_eq!(passed.len(), WIRE);
        match unwrap(&passed).unwrap() {
            Frame::Pass { next, packet } => {
                assert_eq!(next, [5; 32]);
                assert_eq!(packet.len(), FRAME);
            }
            _ => panic!(),
        }
        // a receiver's acknowledgement is a frame like any other, and names the delivery it answers
        assert!(matches!(unwrap(&hello()), Some(Frame::Hand { kind: HELLO, .. })));
        let ack = hand(ACK, &[1; 32], &[3; ID], b"").unwrap();
        assert_eq!(ack.len(), WIRE);
        assert!(matches!(unwrap(&ack), Some(Frame::Hand { kind: ACK, id, .. }) if id == [3; ID]));
        assert!(hand(DELIVERY, &[1; 32], &[2; ID], &[0u8; FRAME]).is_none()); // too much to carry
        let mut junk = vec![9u8]; // no such kind
        junk.resize(WIRE, 0);
        assert!(unwrap(&junk).is_none());
        let mut torn = delivery.clone();
        torn.truncate(WIRE - 1); // a short frame is no frame
        assert!(unwrap(&torn).is_none());
    }

    #[tokio::test]
    async fn frames_written_back_to_back_stay_whole() {
        // two frames in one write arrive as two frames, not one torn read that loses the second
        let (mut a, b) = tokio::io::duplex(4 * WIRE);
        let first = wrap(&[1u8; FRAME]);
        let second = hand(DELIVERY, &[1; 32], &[2; ID], b"second").unwrap();
        a.write_all(&[first.clone(), second.clone()].concat()).await.unwrap();
        drop(a);
        let (msg, mut rx) = mpsc::unbounded_channel();
        let peer = PeerId::random();
        read_frames(b, peer, 1, &msg, &Mutex::new(HashMap::new())).await;
        let got: Vec<Vec<u8>> = std::iter::from_fn(|| match rx.try_recv() {
            Ok(Msg::In(_, _, f)) => Some(f),
            _ => None,
        }).collect();
        assert_eq!(got, vec![first, second]);
    }

    fn mixes(of: &[&[&str]]) -> Vec<HashSet<[u8; 5]>> {
        of.iter().map(|m| nets(&m.iter().map(|a| addr(a)).collect::<Vec<_>>())).collect()
    }

    #[test]
    fn long_messages_go_in_parts_and_come_back_whole() {
        let mut partial = Partial::new();
        let message: Vec<u8> = (0..3 * PART + 5).map(|i| i as u8).collect();
        let mut ps = parts(&[9; ID], &message);
        assert_eq!(ps.len(), 4);
        assert!(ps.iter().all(|p| p.len() + SEAL <= MESSAGE));
        ps.swap(0, 3); // parts arrive in any order, each by its own path
        for p in &ps[..3] { assert!(take_part(&mut partial, p).is_none()); }
        assert!(take_part(&mut partial, &ps[1]).is_none()); // a part twice changes nothing
        assert_eq!(take_part(&mut partial, &ps[3]), Some(([9; ID], message)));
        assert!(partial.is_empty());
        let one = parts(&[1; ID], b"a vote");
        assert_eq!(take_part(&mut partial, &one[0]), Some(([1; ID], b"a vote".to_vec())));
        assert_eq!(parts(&[2; ID], b"").len(), 1);
    }

    #[test]
    fn parts_that_lie_are_refused() {
        let mut bad = parts(&[3; ID], b"x").remove(0);
        bad[ID + 3] = 0; // count 0
        assert!(read_part(&bad).is_none());
        bad[ID + 3] = 2; bad[ID + 1] = 2; // index past the count
        assert!(read_part(&bad).is_none());
        assert!(read_part(&[0; PART_HEAD - 1]).is_none());
        let mut partial = Partial::new();
        for i in 0..PARTIAL + 10 { // the oldest unfinished messages are let go
            let mut p = parts(&[(i % 250) as u8; ID], &vec![0; PART + 1]).remove(0);
            p[1] = (i / 250) as u8;
            take_part(&mut partial, &p);
        }
        assert_eq!(partial.len(), PARTIAL);
    }

    #[test]
    fn independent_networks_are_counted() {
        // two mixes behind one /16 count once; an IPv6 network counts by its first /32
        let m = mixes(&[&["/ip4/1.2.3.4/tcp/1"], &["/ip4/1.2.9.9/tcp/1"], &["/ip4/5.6.7.8/tcp/1"], &["/ip4/9.9.9.9/tcp/1"]]);
        assert_eq!(networks(m.clone().into_iter()), 3);
        let m = mixes(&[&["/ip6/2001:db8::1/tcp/1"], &["/ip6/2001:db8:1::2/tcp/1"], &["/ip4/1.2.3.4/tcp/1"]]);
        assert_eq!(networks(m.clone().into_iter()), 2);
        // home, VPN, loopback and relay addresses never count, so mixes known only by them count as nothing
        let m = mixes(&[&["/ip4/192.168.1.2/tcp/1", "/ip4/127.0.0.1/tcp/1"], &["/ip4/100.86.126.120/udp/1/quic-v1"],
                        &["/ip6/fd7a:115c:a1e0::1/tcp/1", "/ip6/::1/tcp/1"],
                        &["/ip4/66.179.209.233/udp/47291/quic-v1/p2p/12D3KooWSGeDbNKwygVgQ3dbF8TSTZnj5e1y7U2MA2tRGBWTULDv/p2p-circuit"]]);
        assert_eq!(networks(m.clone().into_iter()), 0);
        // three mixes on one machine, as on a test bench: each has many addresses, but they share their network
        let m = mixes(&[&["/ip4/10.0.0.104/tcp/1", "/ip6/2601:644:8301:7240::9497/tcp/1", "/ip4/127.0.0.1/tcp/1"],
                        &["/ip6/2601:644:8301:7240::9497/udp/2/quic-v1", "/ip4/192.168.122.1/tcp/2"],
                        &["/ip6/2601:644:8301:7240:afce:5be9:e432:f53/tcp/3"]]);
        assert_eq!(networks(m.clone().into_iter()), 1);
        // a mix linking two networks joins them: one operator may stand on both
        let m = mixes(&[&["/ip4/1.2.3.4/tcp/1"], &["/ip4/5.6.7.8/tcp/1"], &["/ip4/1.2.0.1/tcp/1", "/ip4/5.6.0.1/tcp/1"]]);
        assert_eq!(networks(m.clone().into_iter()), 1);
        // a mix with no address cannot be claimed as independent
        assert_eq!(networks(Vec::<HashSet<[u8; 5]>>::new().into_iter()), 0);
        // a mix behind a router, known only by its relay circuit, counts by the network it claims
        let mut m = mixes(&[&["/ip4/66.179.209.233/udp/1/quic-v1"], &["/ip4/66.179.209.233/udp/1/quic-v1/p2p/12D3KooWSGeDbNKwygVgQ3dbF8TSTZnj5e1y7U2MA2tRGBWTULDv/p2p-circuit"],
                            &["/ip4/192.168.1.2/tcp/1"]]);
        assert_eq!(networks(m.clone().into_iter()), 1);
        m[1].insert([4, 0, 0, 96, 54]);
        m[2].insert([4, 0, 0, 24, 7]);
        assert_eq!(networks(m.into_iter()), 3);
    }

    #[test]
    fn mix_listings_move_on_every_hour() {
        let now = 20_000 * mix::DAY + 7 * 3600 + 120;
        let [this, last] = mixes_keys(now);
        assert_eq!(this, mixes_key(now / MIXES_HOUR));
        assert_eq!(last, mixes_key(now / MIXES_HOUR - 1));
        // an hour on, last hour's listing is this hour's of before, and the one before is no longer asked for
        let [next, again] = mixes_keys(now + MIXES_HOUR);
        assert_eq!(again, this);
        assert!(next != this && next != last);
    }

    #[test]
    fn the_rate_takes_a_burst_then_refuses() {
        let mut take = Take::default();
        let now = Instant::now();
        for _ in 0..RATE {
            assert!(take.take(now, RATE));
        }
        assert!(!take.take(now, RATE)); // one too many in the same second
        assert!(take.take(now + Duration::from_secs(2), RATE)); // a new second, a new budget
        // another mix forwards for everyone it mixes for: far more in the same second is still taken
        let mut mix = Take::default();
        assert!((0..RATE * 4).all(|_| mix.take(now, MIX_RATE)));
    }

    #[test]
    fn two_picks_are_distinct() {
        for _ in 0..100 {
            let (a, b) = pick_two((0..5).collect()).unwrap();
            assert_ne!(a, b);
        }
        assert!(pick_two(vec![1]).is_none());
    }

    #[test]
    fn the_cover_clock_waits_like_a_poisson_process() {
        let mean = Duration::from_secs(2);
        let mut short = 0;
        for _ in 0..100 {
            let d = exp(mean);
            assert!(d > Duration::ZERO);
            assert!(d < 40 * mean); // uncapped by design: 10x the mean turns up in about 1 run in 220
            if d < mean { short += 1; }
        }
        assert!(short > 35 && short < 90); // most waits are short: it is exponential, not uniform
    }

    #[test]
    fn the_seed_and_gateway_survive_a_restart() {
        let file = std::env::temp_dir().join(format!("killanet-mix-{}", std::process::id()));
        let file = file.to_str().unwrap();
        let (seed, gateway, missing) = load_state(file);
        assert!(missing);
        assert_eq!(gateway, None);
        let peer: PeerId = "12D3KooWQgeCBdZSCuCvpdBUsZvrbjYh5WqSaNABf6cQVTjzanPF".parse().unwrap();
        save_state(file, &seed, Some(&peer));
        let (seed2, gateway2, missing2) = load_state(file);
        assert!(!missing2);
        assert_eq!(seed, seed2);
        assert_eq!(gateway2, Some(peer));
        save_state(file, &seed, None);
        let (_, gateway3, _) = load_state(file);
        assert_eq!(gateway3, None);
        let _ = std::fs::remove_file(file);
    }
}
