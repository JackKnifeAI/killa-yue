//! The Killa YuE network node. rust-libp2p finds peers and opens encrypted streams to them (QUIC first, TCP with
//! Noise and yamux as the fallback); HAMMER's own pinned TLS connections ride inside those streams unchanged.
//! Design: docs/designs/2026-10-18-worldwide-network.md.
//!
//! How a node reaches the world, with no server of ours:
//! - first contact: peers remembered from earlier runs, the BitTorrent Mainline DHT (reachable Killa YuE nodes
//!   announce themselves under one infohash), entry peers handed in by the app, and mDNS on the same Wi-Fi;
//! - finding a peer or the public hosts: our own Kademlia DHT (`/killa-yue/kad/1.0.0`, never the IPFS one);
//! - getting through NAT: AutoNAT (am I reachable?), UPnP (open a port on the home router), relay reservations on
//!   reachable Killa YuE peers when not reachable, and DCUtR hole punching to turn a relayed connection direct.
//!
//! Java talks to the node through a handful of calls (see `peer/KillaNet.java`): start, open (a one-use loopback
//! port per HAMMER connection), announce (offer this host in the public list), lookup, peers and status (tab
//! separated text, so the desktop needs no JSON library). Inbound HAMMER streams reach Java's loopback bridge with a
//! token and the remote PeerId first, so HAMMER's admission limits apply per peer, not to the loopback address.

use futures::StreamExt;
use libp2p::{
    autonat, dcutr, identify, identity, kad, mdns, multiaddr::Protocol, noise, ping, relay,
    swarm::{behaviour::toggle::Toggle, NetworkBehaviour, SwarmEvent},
    tcp, upnp, yamux, Multiaddr, PeerId, StreamProtocol, Swarm,
};
use std::collections::{HashMap, HashSet};
use std::net::Ipv4Addr;
use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{mpsc, oneshot, Semaphore};
use tokio_util::compat::FuturesAsyncReadCompatExt;

/// Diagnostics on stderr when KILLANET_DEBUG is set (desktop tests); silent otherwise.
macro_rules! debug {
    ($($t:tt)*) => { if std::env::var_os("KILLANET_DEBUG").is_some() { eprintln!("[killanet] {}", format!($($t)*)); } };
}

pub const HAMMER: StreamProtocol = StreamProtocol::new("/killa-yue/hammer/1.0.0");
const KAD: StreamProtocol = StreamProtocol::new("/killa-yue/kad/1.0.0");
const IDENTIFY: &str = "/killa-yue/id/1.0.0";
const HOP: StreamProtocol = StreamProtocol::new("/libp2p/circuit/relay/0.2.0/hop");
/// The DHT key under which every public host offers itself: Discover's worldwide list.
const HOSTS_KEY: &[u8] = b"ky:hosts:1";
/// SHA-1 of "killa-yue/net/1": the Mainline DHT infohash reachable Killa YuE nodes announce under.
const MEETING: [u8; 20] = [150, 33, 244, 148, 119, 77, 5, 160, 72, 223, 178, 220, 63, 125, 107, 31, 122, 59, 71, 254];
/// The port QUIC (UDP) and TCP both listen on, so a Mainline "IP:port" can be dialled both ways.
pub const DEFAULT_PORT: u16 = 47291;
const INBOUND_AT_ONCE: usize = 16;
const AUTO_DIALS: usize = 32;
const RELAYS: usize = 2;
const REMEMBERED: usize = 200;

/// Start flags from Java.
pub const HELP: i32 = 1; // relay for others and answer DHT queries (Wi-Fi and charging, or a computer)
pub const NO_MDNS: i32 = 2;
pub const NO_MAINLINE: i32 = 4;
pub const RELAY_ONLY: i32 = 8; // tests: no direct listeners, reachable only through relays
pub const NO_UPNP: i32 = 16;
/// Tests only: treat this node's own addresses, loopback included, as confirmed reachable. A relay offers relaying
/// only once confirmed reachable, which AutoNAT never does for loopback.
pub const TEST_REACHABLE: i32 = 32;

#[derive(NetworkBehaviour)]
struct Behaviour {
    relay_client: relay::client::Behaviour,
    relay: Toggle<relay::Behaviour>,
    dcutr: dcutr::Behaviour,
    autonat: autonat::Behaviour,
    upnp: Toggle<upnp::tokio::Behaviour>,
    mdns: Toggle<mdns::tokio::Behaviour>,
    kad: kad::Behaviour<kad::store::MemoryStore>,
    identify: identify::Behaviour,
    ping: ping::Behaviour,
    stream: libp2p_stream::Behaviour,
}

#[derive(Default)]
struct State {
    listen: Vec<Multiaddr>,
    addrs: HashMap<PeerId, HashSet<Multiaddr>>,
    agents: HashMap<PeerId, String>,
    connected: HashSet<PeerId>,
    relayed: HashSet<PeerId>, // ever connected through a relay
    punched: HashSet<PeerId>, // a relayed connection turned direct by hole punching
    local: HashSet<PeerId>,
    nat: String,
    worldwide: bool, // confirmed reachable on a public internet address, not only on a LAN or a VPN
    helping: bool,   // volunteers as a relay and DHT server (a computer, or a phone on Wi-Fi and charging)
    test_reachable: bool,
    relays: HashSet<PeerId>,
    hosts: HashSet<PeerId>,
}

/// Whoever waits for a peer's addresses; shared by the routing lookup and the address-record lookup.
type Waiter = Arc<Mutex<Option<oneshot::Sender<bool>>>>;

enum Cmd {
    Find(PeerId, oneshot::Sender<bool>),
    Dial(Vec<Multiaddr>),
    Announce(bool),
    Lookup,
    Help(bool),
}

pub struct Node {
    rt: tokio::runtime::Runtime,
    control: libp2p_stream::Control,
    state: Arc<Mutex<State>>,
    cmd: mpsc::UnboundedSender<Cmd>,
    stop: Arc<AtomicBool>,
    pub peer_id: PeerId,
}

/// The node key lives in the app's private files. It is the network address, not the artist or HAMMER identity:
/// HAMMER's TLS still checks the pinned identity inside every stream, so a forged PeerId only fails the handshake.
fn load_key(path: &str) -> Result<identity::Keypair, String> {
    if let Ok(bytes) = std::fs::read(path) {
        return identity::Keypair::from_protobuf_encoding(&bytes).map_err(|e| format!("node key unreadable: {e}"));
    }
    let key = identity::Keypair::generate_ed25519();
    let bytes = key.to_protobuf_encoding().map_err(|e| e.to_string())?;
    let tmp = format!("{path}.tmp");
    std::fs::write(&tmp, &bytes).map_err(|e| format!("node key not saved: {e}"))?;
    std::fs::rename(&tmp, path).map_err(|e| format!("node key not saved: {e}"))?;
    Ok(key)
}

/// Peers we reached before, one "PeerId multiaddr" per line.
fn load_remembered(path: &str) -> Vec<(PeerId, Multiaddr)> {
    let text = std::fs::read_to_string(path).unwrap_or_default();
    text.lines()
        .filter_map(|l| {
            let mut f = l.split(' ');
            Some((f.next()?.parse().ok()?, f.next()?.parse().ok()?))
        })
        .take(REMEMBERED)
        .collect()
}

fn save_remembered(path: &str, peers: &HashMap<PeerId, Multiaddr>) {
    if path.is_empty() {
        return;
    }
    let mut out = String::new();
    for (p, a) in peers.iter().take(REMEMBERED) {
        out.push_str(&format!("{p} {a}\n"));
    }
    let tmp = format!("{path}.tmp");
    if std::fs::write(&tmp, out).is_ok() {
        let _ = std::fs::rename(&tmp, path);
    }
}

fn is_loopback(a: &Multiaddr) -> bool {
    a.iter().any(|p| matches!(p, Protocol::Ip4(ip) if ip.is_loopback()) || matches!(p, Protocol::Ip6(ip) if ip.is_loopback()))
}

/// Not loopback, not an unspecified or link-local address: worth telling another node about.
fn dialable(a: &Multiaddr) -> bool {
    !is_loopback(a)
        && !a.iter().any(|p| match p {
            Protocol::Ip4(ip) => ip.is_unspecified() || ip.is_link_local(),
            Protocol::Ip6(ip) => ip.is_unspecified() || (ip.segments()[0] & 0xffc0) == 0xfe80,
            _ => false,
        })
}

/// A public internet address: not private, carrier-grade NAT, a VPN such as Tailscale (100.64.0.0/10, fd00::/8),
/// link-local or loopback. Being reachable only on one of those does not make a phone on mobile data reachable.
fn worldwide(a: &Multiaddr) -> bool {
    a.iter().any(|p| match p {
        Protocol::Ip4(ip) => {
            let o = ip.octets();
            !(ip.is_private() || ip.is_loopback() || ip.is_link_local() || ip.is_unspecified() ||
              (o[0] == 100 && (o[1] & 0xc0) == 64))
        }
        Protocol::Ip6(ip) => (ip.segments()[0] & 0xe000) == 0x2000, // global unicast
        _ => false,
    }) && !is_circuit(a)
}

/// Lower is more useful to a peer elsewhere: 0 internet, 1 home network, 2 VPN (Tailscale), 3 container and VM
/// bridges (172.16.0.0/12, 192.168.122.0/24), which no other machine can reach.
fn usefulness(a: &Multiaddr) -> u8 {
    for p in a.iter() {
        match p {
            Protocol::Ip4(ip) => {
                let o = ip.octets();
                return if (o[0] == 172 && (o[1] & 0xf0) == 16) || (o[0] == 192 && o[1] == 168 && o[2] == 122) { 3 }
                       else if o[0] == 100 && (o[1] & 0xc0) == 64 { 2 }
                       else if ip.is_private() { 1 } else { 0 };
            }
            Protocol::Ip6(ip) => {
                let first = ip.segments()[0];
                return if (first & 0xe000) == 0x2000 { 0 } else if (first & 0xfe00) == 0xfc00 { 2 } else { 1 };
            }
            _ => {}
        }
    }
    1
}

fn is_circuit(a: &Multiaddr) -> bool {
    a.iter().any(|p| matches!(p, Protocol::P2pCircuit))
}

/// The UDP port of a QUIC address, for the Mainline announcement.
fn udp_port(a: &Multiaddr) -> Option<u16> {
    a.iter().find_map(|p| if let Protocol::Udp(port) = p { Some(port) } else { None })
}

/// The DHT key of a node's signed address record.
fn addr_key(p: &PeerId) -> kad::RecordKey {
    kad::RecordKey::new(&[b"ky:addr:".as_slice(), &p.to_bytes()].concat())
}

fn now_secs() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

/// Publishes where this node can be reached (relay circuits and confirmed public addresses), signed with the node
/// key: phones behind NAT run the DHT as clients and are not in anyone's routing table, so this record is how a
/// card naming only their PeerId finds them.
fn publish_addrs(swarm: &mut Swarm<Behaviour>, key: &identity::Keypair) {
    // confirmed public addresses first, then relay circuits, then this node's own interface addresses (useful on
    // the same network, or when the router lets them through but nothing has confirmed it yet)
    let mut addrs: Vec<Multiaddr> = swarm.external_addresses().cloned().collect();
    addrs.extend(swarm.listeners().filter(|a| is_circuit(a)).cloned());
    // the record holds eight: internet addresses before home-network ones, and Docker, VM and VPN bridges last
    let mut own: Vec<Multiaddr> = swarm.listeners().filter(|a| !is_circuit(a) && dialable(a)).cloned().collect();
    own.sort_by_key(|a| usefulness(a));
    addrs.extend(own);
    let mut seen = HashSet::new();
    addrs.retain(|a| seen.insert(a.clone()));
    if addrs.is_empty() {
        return;
    }
    addrs.truncate(8);
    let body = format!("{}\n{}", now_secs(), addrs.iter().map(|a| a.to_string()).collect::<Vec<_>>().join("\n"));
    let Ok(sig) = key.sign(body.as_bytes()) else { return };
    let mut value = (sig.len() as u16).to_be_bytes().to_vec();
    value.extend(sig);
    value.extend(body.as_bytes());
    let me = *swarm.local_peer_id();
    let _ = swarm.behaviour_mut().kad.put_record(kad::Record::new(addr_key(&me), value), kad::Quorum::One);
}

/// The addresses in a peer's address record, if its signature checks against the PeerId and it is fresh.
fn read_addrs(peer: &PeerId, value: &[u8]) -> Vec<Multiaddr> {
    let check = || -> Option<Vec<Multiaddr>> {
        let n = u16::from_be_bytes([*value.first()?, *value.get(1)?]) as usize;
        let sig = value.get(2..2 + n)?;
        let body = value.get(2 + n..)?;
        let hash: &libp2p::multihash::Multihash<64> = peer.as_ref();
        if hash.code() != 0 {
            return None; // only inline (Ed25519) identities carry their public key
        }
        let key = identity::PublicKey::try_decode_protobuf(hash.digest()).ok()?;
        if key.to_peer_id() != *peer || !key.verify(body, sig) {
            return None;
        }
        let text = std::str::from_utf8(body).ok()?;
        let mut lines = text.lines();
        let stamp: u64 = lines.next()?.parse().ok()?;
        if now_secs().abs_diff(stamp) > 2 * 3600 {
            return None;
        }
        Some(lines.filter_map(|l| l.parse().ok()).take(8).collect())
    };
    check().unwrap_or_default()
}

pub struct Options {
    pub key_file: String,
    pub peers_file: String,
    pub agent: String,
    pub inbound_port: u16,
    pub token: Vec<u8>,
    pub flags: i32,
    pub port: u16,
    pub entry: Vec<Multiaddr>,
}

impl Node {
    pub fn start(o: Options) -> Result<Node, String> {
        let key = load_key(&o.key_file)?;
        let signing = key.clone();
        let peer_id = key.public().to_peer_id();
        let flags = o.flags;
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("killanet")
            .enable_all()
            .build()
            .map_err(|e| e.to_string())?;
        let agent = o.agent.clone();
        let _inside = rt.enter(); // QUIC, mDNS and UPnP bind their sockets on the runtime
        let mut swarm: Swarm<Behaviour> = rt.block_on(async {
            libp2p::SwarmBuilder::with_existing_identity(key)
                .with_tokio()
                .with_tcp(tcp::Config::default().nodelay(true), noise::Config::new, yamux::Config::default)
                .map_err(|e| e.to_string())?
                .with_quic()
                .with_relay_client(noise::Config::new, yamux::Config::default)
                .map_err(|e| e.to_string())?
                .with_behaviour(|key, relay_client| {
                    let me = key.public().to_peer_id();
                    let mut kc = kad::Config::new(KAD);
                    kc.set_query_timeout(Duration::from_secs(30));
                    kc.set_provider_record_ttl(Some(Duration::from_secs(48 * 3600)));
                    kc.set_provider_publication_interval(Some(Duration::from_secs(12 * 3600)));
                    kc.set_record_ttl(Some(Duration::from_secs(2 * 3600)));
                    let store = kad::store::MemoryStore::with_config(
                        me,
                        kad::store::MemoryStoreConfig { max_providers_per_key: 1024, ..Default::default() },
                    );
                    // Relays carry whole streams, not only hole-punch handshakes: 30 minutes and 64 MiB a circuit.
                    let relay_config = relay::Config {
                        max_reservations: 32,
                        max_reservations_per_peer: 2,
                        max_circuits: 8,
                        max_circuits_per_peer: 2,
                        max_circuit_duration: Duration::from_secs(30 * 60),
                        max_circuit_bytes: 64 << 20,
                        ..Default::default()
                    };
                    Ok(Behaviour {
                        relay_client,
                        // always present, but it serves only while this node helps and is reachable from the
                        // internet: the relay library serves exactly when there are confirmed external addresses,
                        // and reachable_as only confirms direct ones then
                        relay: Toggle::from(Some(relay::Behaviour::new(me, relay_config))),
                        dcutr: dcutr::Behaviour::new(me),
                        autonat: autonat::Behaviour::new(
                            me,
                            autonat::Config { boot_delay: Duration::from_secs(10), ..Default::default() },
                        ),
                        upnp: Toggle::from((flags & NO_UPNP == 0).then(upnp::tokio::Behaviour::default)),
                        mdns: Toggle::from(if flags & NO_MDNS == 0 {
                            Some(mdns::tokio::Behaviour::new(mdns::Config::default(), me)?)
                        } else {
                            None
                        }),
                        kad: kad::Behaviour::with_config(me, store, kc),
                        identify: identify::Behaviour::new(
                            identify::Config::new(IDENTIFY.into(), key.public())
                                // only confirmed addresses: a computer listens on dozens (Docker and VM bridges,
                                // IPv6, VPNs, relays), and an identify message over 4096 bytes is dropped, leaving
                                // a connected peer unnamed and unlisted. Neighbours learn every address by mDNS,
                                // others from the DHT address record.
                                .with_hide_listen_addrs(true)
                                .with_agent_version(agent)
                                .with_cache_size(256),
                        ),
                        ping: ping::Behaviour::default(),
                        stream: libp2p_stream::Behaviour::new(),
                    })
                })
                .map_err(|e| e.to_string())
                .map(|b| b.with_swarm_config(|c| c.with_idle_connection_timeout(Duration::from_secs(90))).build())
        })?;
        // The DHT role is chosen, never inferred: a helper (Wi-Fi and charging, or a computer) answers queries even
        // before AutoNAT confirms it reachable; every other node is a client, so a phone on mobile data never ends up
        // in routing tables where nobody can reach it.
        swarm.behaviour_mut().kad.set_mode(Some(if flags & HELP != 0 { kad::Mode::Server } else { kad::Mode::Client }));
        if flags & RELAY_ONLY == 0 {
            for port in [o.port, 0] {
                let mut ok = false;
                for addr in [
                    format!("/ip4/0.0.0.0/udp/{port}/quic-v1"),
                    format!("/ip6/::/udp/{port}/quic-v1"),
                    format!("/ip4/0.0.0.0/tcp/{port}"),
                    format!("/ip6/::/tcp/{port}"),
                ] {
                    // IPv6 may be missing on some networks; one working listener is enough
                    ok |= swarm.listen_on(addr.parse::<Multiaddr>().unwrap()).is_ok();
                }
                if ok {
                    break;
                }
            }
        }
        let control = swarm.behaviour().stream.new_control();
        let mut incoming = control.clone().accept(HAMMER).map_err(|e| format!("stream protocol: {e}"))?;
        let state = Arc::new(Mutex::new(State { nat: "unknown".into(), helping: flags & HELP != 0,
                                                   test_reachable: flags & TEST_REACHABLE != 0, ..Default::default() }));
        let (cmd, mut cmds) = mpsc::unbounded_channel::<Cmd>();
        let stop = Arc::new(AtomicBool::new(false));

        // inbound HAMMER streams → Java's loopback bridge, token and PeerId first
        let gate = Arc::new(Semaphore::new(INBOUND_AT_ONCE));
        let (inbound_port, token) = (o.inbound_port, o.token.clone());
        rt.spawn(async move {
            while let Some((peer, stream)) = incoming.next().await {
                let Ok(permit) = gate.clone().try_acquire_owned() else { continue };
                let token = token.clone();
                tokio::spawn(async move {
                    let _permit = permit;
                    let Ok(mut local) = tokio::net::TcpStream::connect((Ipv4Addr::LOCALHOST, inbound_port)).await else { return };
                    let _ = local.set_nodelay(true);
                    let id = peer.to_base58();
                    let mut head = token;
                    head.push(id.len() as u8);
                    head.extend_from_slice(id.as_bytes());
                    if local.write_all(&head).await.is_err() {
                        return;
                    }
                    let mut remote = stream.compat();
                    let _ = tokio::io::copy_bidirectional(&mut local, &mut remote).await;
                });
            }
        });

        // first contact through the Mainline DHT: find reachable Killa YuE nodes, announce ourselves when reachable
        let public_port = Arc::new(AtomicU16::new(0));
        if flags & NO_MAINLINE == 0 {
            let (tx, port, halt) = (cmd.clone(), public_port.clone(), stop.clone());
            std::thread::Builder::new()
                .name("killanet-mainline".into())
                .spawn(#[allow(deprecated)] // the blocking API is right for its own thread
                move || {
                    let Ok(dht) = mainline::Dht::client() else { debug!("mainline: could not start"); return };
                    let meeting = mainline::Id::from(MEETING);
                    while !halt.load(Ordering::Relaxed) {
                        let mut found = Vec::new();
                        for batch in dht.get_peers(meeting) {
                            found.extend(batch);
                            if found.len() >= 64 {
                                break;
                            }
                        }
                        debug!("mainline: bootstrapped {}, {} Killa YuE nodes at the meeting point", dht.bootstrapped(), found.len());
                        let addrs: Vec<Multiaddr> = found
                            .iter()
                            .flat_map(|s| {
                                [
                                    format!("/ip4/{}/udp/{}/quic-v1", s.ip(), s.port()),
                                    format!("/ip4/{}/tcp/{}", s.ip(), s.port()),
                                ]
                            })
                            .filter_map(|a| a.parse().ok())
                            .collect();
                        if !addrs.is_empty() && tx.send(Cmd::Dial(addrs)).is_err() {
                            return;
                        }
                        let p = port.load(Ordering::Relaxed);
                        if p != 0 {
                            let r = dht.announce_peer(meeting, Some(p));
                            debug!("mainline: announced port {p}: {}", r.is_ok());
                        }
                        // look again in ten minutes (announcements expire after about thirty)
                        for _ in 0..600 {
                            if halt.load(Ordering::Relaxed) {
                                return;
                            }
                            std::thread::sleep(Duration::from_secs(1));
                        }
                    }
                })
                .map_err(|e| e.to_string())?;
        }

        // entry peers from the app, and peers remembered from earlier runs
        let mut remembered: HashMap<PeerId, Multiaddr> = HashMap::new();
        let mut first: Vec<Multiaddr> = o.entry.clone();
        for (p, a) in load_remembered(&o.peers_file) {
            swarm.behaviour_mut().kad.add_address(&p, a.clone());
            remembered.insert(p, a.clone());
            if first.len() < 16 + o.entry.len() {
                first.push(a.with(Protocol::P2p(p)));
            }
        }
        for a in &o.entry {
            if let Some(Protocol::P2p(p)) = a.iter().last() {
                swarm.behaviour_mut().kad.add_address(&p, a.clone());
            }
        }
        for a in first {
            let _ = swarm.dial(a);
        }

        let st = state.clone();
        let halt = stop.clone();
        let peers_file = o.peers_file.clone();
        let relay_only = flags & RELAY_ONLY != 0;
        rt.spawn(async move {
            let mut dialled: HashSet<PeerId> = HashSet::new();
            // neighbours on this network have their own budget, so dead hosts lingering in the DHT's host list
            // (provider records live 48 hours) can never use up the dials before the artist next door is found
            let mut dialled_local: HashSet<PeerId> = HashSet::new();
            // a peer whose dial failed is left alone for ten minutes, not dialled again on every lookup
            let mut failed: HashMap<PeerId, std::time::Instant> = HashMap::new();
            // DHT lookups for a peer's addresses: who, who is waiting, and whether to dial once found
            let mut finds: HashMap<kad::QueryId, (PeerId, Waiter, bool)> = HashMap::new();
            let mut hop: HashMap<PeerId, Multiaddr> = HashMap::new(); // relays and an address we reached them on
            let mut announcing = false;
            // the first round runs five seconds in, once the listeners are up
            let mut tick = tokio::time::interval_at(tokio::time::Instant::now() + Duration::from_secs(5), Duration::from_secs(60));
            let mut minutes: u64 = 0;
            let hosts_key = kad::RecordKey::new(&HOSTS_KEY);
            loop {
                tokio::select! {
                    _ = tick.tick() => {
                        if halt.load(Ordering::Relaxed) { break; }
                        minutes += 1;
                        if minutes == 1 || minutes % 10 == 0 { publish_addrs(&mut swarm, &signing); }
                        if minutes == 1 || minutes % 5 == 0 {
                            let _ = swarm.behaviour_mut().kad.bootstrap();
                            swarm.behaviour_mut().kad.get_providers(hosts_key.clone());
                            save_remembered(&peers_file, &remembered);
                        }
                    }
                    Some(c) = cmds.recv() => match c {
                        Cmd::Find(peer, reply) => {
                            let known = { let s = st.lock().unwrap(); s.connected.contains(&peer) || s.addrs.get(&peer).map_or(false, |a| !a.is_empty()) };
                            if known {
                                let _ = reply.send(true);
                            } else {
                                // a reachable peer is in the routing table; one behind NAT has a signed address record
                                let waiter: Waiter = Arc::new(Mutex::new(Some(reply)));
                                let q = swarm.behaviour_mut().kad.get_closest_peers(peer);
                                let r = swarm.behaviour_mut().kad.get_record(addr_key(&peer));
                                finds.insert(q, (peer, waiter.clone(), false));
                                finds.insert(r, (peer, waiter, false));
                            }
                        }
                        Cmd::Dial(addrs) => {
                            for a in addrs.into_iter().take(128) { let _ = swarm.dial(a); }
                        }
                        Cmd::Announce(on) => {
                            if on && !announcing {
                                reachable_as(&mut swarm, &st);
                                let _ = swarm.behaviour_mut().kad.start_providing(hosts_key.clone());
                                publish_addrs(&mut swarm, &signing);
                            } else if !on && announcing {
                                swarm.behaviour_mut().kad.stop_providing(&hosts_key);
                            }
                            announcing = on;
                        }
                        Cmd::Lookup => { swarm.behaviour_mut().kad.get_providers(hosts_key.clone()); }
                        Cmd::Help(on) => {
                            let changed = {
                                let mut s = st.lock().unwrap();
                                let changed = s.helping != on;  // only a real change: Android reports often
                                s.helping = on;
                                changed
                            };
                            if changed {
                                swarm.behaviour_mut().kad.set_mode(Some(if on { kad::Mode::Server } else { kad::Mode::Client }));
                                reachable_as(&mut swarm, &st);
                            }
                        }
                    },
                    event = swarm.select_next_some() => { match &event {
                        SwarmEvent::ConnectionEstablished { peer_id, endpoint, .. } => debug!("connected {peer_id} via {}", endpoint.get_remote_address()),
                        SwarmEvent::OutgoingConnectionError { peer_id, error, .. } => debug!("dial failed {peer_id:?}: {error}"),
                        SwarmEvent::Behaviour(BehaviourEvent::Kad(e)) => debug!("kad {e:?}"),
                        SwarmEvent::Behaviour(BehaviourEvent::RelayClient(e)) => debug!("relay client {e:?}"),
                        SwarmEvent::NewListenAddr { address, .. } => debug!("listening {address}"),
                        _ => {}
                    }
                    match event {
                        SwarmEvent::NewListenAddr { address, .. } => {
                            if flags & TEST_REACHABLE != 0 { swarm.add_external_address(address.clone()); }
                            let circuit = is_circuit(&address);
                            // a public IPv4 address on this machine's own interface (a server, not a phone behind
                            // NAT): offer it and the relay at once, or nobody could ever meet the first such node to
                            // confirm it. Peers dial what they find, so a firewalled one only costs a failed dial.
                            let v4 = address.iter().any(|p| matches!(p, Protocol::Ip4(_)));
                            if v4 && worldwide(&address) {
                                let helping = {
                                    let mut s = st.lock().unwrap();
                                    s.worldwide = true;
                                    s.helping
                                };
                                if helping { swarm.add_external_address(address.clone()); }
                                if let Some(p) = udp_port(&address) { public_port.store(p, Ordering::Relaxed); }
                            }
                            st.lock().unwrap().listen.push(address);
                            if circuit {
                                // reachable through a relay now: say so in the address record and the host list
                                reachable_as(&mut swarm, &st);
                                publish_addrs(&mut swarm, &signing);
                                if announcing { let _ = swarm.behaviour_mut().kad.start_providing(hosts_key.clone()); }
                            }
                        }
                        SwarmEvent::ExpiredListenAddr { address, .. } => st.lock().unwrap().listen.retain(|a| a != &address),
                        SwarmEvent::ConnectionEstablished { peer_id, endpoint, .. } => {
                            let addr = endpoint.get_remote_address().clone();
                            let mut s = st.lock().unwrap();
                            s.connected.insert(peer_id);
                            if is_circuit(&addr) { s.relayed.insert(peer_id); }
                            if endpoint.is_dialer() && !is_circuit(&addr) && !is_loopback(&addr) {
                                if remembered.len() < REMEMBERED || remembered.contains_key(&peer_id) {
                                    remembered.insert(peer_id, addr.clone());
                                }
                            }
                            if endpoint.is_dialer() && !is_circuit(&addr) {
                                s.addrs.entry(peer_id).or_default().insert(addr);
                            }
                        }
                        SwarmEvent::OutgoingConnectionError { peer_id: Some(peer), .. } => {
                            if !st.lock().unwrap().connected.contains(&peer) {
                                // free its place; it is tried again when found again, after a rest
                                dialled.remove(&peer);
                                dialled_local.remove(&peer);
                                failed.insert(peer, std::time::Instant::now());
                            }
                        }
                        SwarmEvent::ConnectionClosed { peer_id, num_established, .. } => {
                            if num_established == 0 {
                                let mut s = st.lock().unwrap();
                                s.connected.remove(&peer_id);
                                dialled.remove(&peer_id);  // found again later, it is dialled again
                                dialled_local.remove(&peer_id);
                            }
                        }
                        SwarmEvent::Behaviour(BehaviourEvent::Mdns(mdns::Event::Discovered(found))) => {
                            // learn every address first, then dial: a computer answers with one address per
                            // interface (Docker and VM bridges too), and a dial made on the first one alone
                            // could fail and never be tried again
                            let mut fresh = Vec::new();
                            for (peer, addr) in found {
                                swarm.add_peer_address(peer, addr.clone());
                                swarm.behaviour_mut().kad.add_address(&peer, addr.clone());
                                let mut s = st.lock().unwrap();
                                s.addrs.entry(peer).or_default().insert(addr);
                                s.local.insert(peer);
                                if !s.connected.contains(&peer) && !fresh.contains(&peer) { fresh.push(peer); }
                            }
                            for peer in fresh {
                                failed.remove(&peer);  // just heard from: worth a try
                                if dialled_local.len() < AUTO_DIALS * 2 && dialled_local.insert(peer) {
                                    let _ = swarm.dial(peer);
                                }
                            }
                        }
                        SwarmEvent::Behaviour(BehaviourEvent::Mdns(mdns::Event::Expired(gone))) => {
                            let mut s = st.lock().unwrap();
                            for (peer, addr) in gone {
                                if let Some(set) = s.addrs.get_mut(&peer) { set.remove(&addr); }
                                if s.addrs.get(&peer).map_or(true, |a| a.is_empty()) {
                                    s.local.remove(&peer);
                                    dialled.remove(&peer);
                                    dialled_local.remove(&peer);
                                }
                            }
                        }
                        SwarmEvent::Behaviour(BehaviourEvent::Identify(identify::Event::Received { peer_id, info, .. })) => {
                            let kad_peer = info.protocols.contains(&KAD);
                            let relay_peer = info.protocols.contains(&HOP);
                            debug!("identify {peer_id}: kad {kad_peer} relay {relay_peer} addrs {:?}", info.listen_addrs);
                            let addrs: Vec<Multiaddr> = info.listen_addrs.into_iter().take(16).collect();
                            {
                                let mut s = st.lock().unwrap();
                                s.agents.insert(peer_id, info.agent_version);
                                let set = s.addrs.entry(peer_id).or_default();
                                for a in &addrs { set.insert(a.clone()); }
                            }
                            for a in &addrs {
                                if kad_peer { swarm.behaviour_mut().kad.add_address(&peer_id, a.clone()); }
                                swarm.add_peer_address(peer_id, a.clone());
                            }
                            if relay_peer {
                                let reach = addrs.iter().find(|a| !is_circuit(a) && (relay_only || !is_loopback(a))).cloned();
                                if let Some(a) = reach { hop.insert(peer_id, a); }
                            }
                            reserve(&mut swarm, &st, &hop, relay_only);
                        }
                        SwarmEvent::Behaviour(BehaviourEvent::Autonat(autonat::Event::StatusChanged { new, .. })) => {
                            let label = match &new {
                                autonat::NatStatus::Public(a) => {
                                    if let Some(p) = udp_port(a) { public_port.store(p, Ordering::Relaxed); }
                                    st.lock().unwrap().worldwide = worldwide(a);
                                    "public"
                                }
                                autonat::NatStatus::Private => { public_port.store(0, Ordering::Relaxed); "private" }
                                autonat::NatStatus::Unknown => "unknown",
                            };
                            if !matches!(new, autonat::NatStatus::Public(_)) { st.lock().unwrap().worldwide = false; }
                            reachable_as(&mut swarm, &st);
                            st.lock().unwrap().nat = label.into();
                            reserve(&mut swarm, &st, &hop, relay_only);
                        }
                        SwarmEvent::Behaviour(BehaviourEvent::Upnp(upnp::Event::NewExternalAddr { external_addr, .. })) => {
                            if let Some(p) = udp_port(&external_addr) { public_port.store(p, Ordering::Relaxed); }
                            let mut s = st.lock().unwrap();
                            s.nat = "public (router opened a port)".into();
                            s.worldwide = worldwide(&external_addr);
                        }
                        SwarmEvent::Behaviour(BehaviourEvent::Dcutr(dcutr::Event { remote_peer_id, result: Ok(_) })) => {
                            st.lock().unwrap().punched.insert(remote_peer_id);
                        }
                        SwarmEvent::Behaviour(BehaviourEvent::RelayClient(relay::client::Event::ReservationReqAccepted { relay_peer_id, .. })) => {
                            st.lock().unwrap().relays.insert(relay_peer_id);
                            publish_addrs(&mut swarm, &signing);
                        }
                        SwarmEvent::Behaviour(BehaviourEvent::Kad(kad::Event::OutboundQueryProgressed { id, result, step, .. })) => match result {
                            kad::QueryResult::GetClosestPeers(r) => {
                                let peers = match r { Ok(ok) => ok.peers, Err(kad::GetClosestPeersError::Timeout { peers, .. }) => peers };
                                let target = finds.get(&id).map(|f| f.0);
                                if let Some(target) = target {
                                    let found: Vec<Multiaddr> = peers.iter().filter(|i| i.peer_id == target).flat_map(|i| i.addrs.clone()).collect();
                                    if !found.is_empty() { learned(&mut swarm, &st, &mut finds, id, target, found); }
                                }
                                if step.last { finished(&mut finds, id); }
                            }
                            kad::QueryResult::GetRecord(r) => {
                                let target = finds.get(&id).map(|f| f.0);
                                if let (Some(target), Ok(kad::GetRecordOk::FoundRecord(found))) = (target, r) {
                                    let addrs = read_addrs(&target, &found.record.value);
                                    if !addrs.is_empty() { learned(&mut swarm, &st, &mut finds, id, target, addrs); }
                                }
                                if step.last { finished(&mut finds, id); }
                            }
                            kad::QueryResult::GetProviders(Ok(kad::GetProvidersOk::FoundProviders { providers, .. })) => {
                                let me = *swarm.local_peer_id();
                                for p in providers {
                                    if p == me { continue; }
                                    st.lock().unwrap().hosts.insert(p);
                                    let connected = st.lock().unwrap().connected.contains(&p);
                                    let resting = failed.get(&p).map_or(false, |t| t.elapsed() < Duration::from_secs(600));
                                    if !connected && !resting && dialled.len() < AUTO_DIALS * 2 && dialled.insert(p) {
                                        // try what the swarm knows, and look up its signed address record too
                                        let _ = swarm.dial(p);
                                        let q = swarm.behaviour_mut().kad.get_record(addr_key(&p));
                                        finds.insert(q, (p, Arc::new(Mutex::new(None)), true));
                                    }
                                }
                            }
                            _ => {}
                        },
                        _ => {}
                    }}
                }
            }
            save_remembered(&peers_file, &remembered);
        });
        Ok(Node { rt, control, state, cmd, stop, peer_id })
    }

    /// A loopback port for one HAMMER connection to `peer`. The connecting side must send `token` first; anything
    /// else, or nobody within 15 seconds, closes the port. The node looks the peer up in the DHT when it does not
    /// know where it is, then opens the stream: direct, hole-punched or through a relay, whichever works.
    pub fn open(&self, peer: PeerId, token: Vec<u8>) -> Result<u16, String> {
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).map_err(|e| e.to_string())?;
        listener.set_nonblocking(true).map_err(|e| e.to_string())?;
        let port = listener.local_addr().map_err(|e| e.to_string())?.port();
        let mut control = self.control.clone();
        let cmd = self.cmd.clone();
        self.rt.spawn(async move {
            let Ok(listener) = tokio::net::TcpListener::from_std(listener) else { return };
            let Ok(Ok((mut local, _))) = tokio::time::timeout(Duration::from_secs(15), listener.accept()).await else { return };
            drop(listener);
            let _ = local.set_nodelay(true);
            let mut got = vec![0u8; token.len()];
            let Ok(Ok(_)) = tokio::time::timeout(Duration::from_secs(5), local.read_exact(&mut got)).await else { return };
            if got.iter().zip(token.iter()).fold(0u8, |d, (a, b)| d | (a ^ b)) != 0 {
                return;
            }
            let (tx, rx) = oneshot::channel();
            if cmd.send(Cmd::Find(peer, tx)).is_ok() {
                let _ = tokio::time::timeout(Duration::from_secs(25), rx).await;
            }
            let Ok(Ok(stream)) = tokio::time::timeout(Duration::from_secs(30), control.open_stream(peer, HAMMER)).await else { return };
            let mut remote = stream.compat();
            let _ = tokio::io::copy_bidirectional(&mut local, &mut remote).await;
        });
        Ok(port)
    }

    /// Offer (or stop offering) this host in the DHT's public host list.
    pub fn announce(&self, on: bool) {
        let _ = self.cmd.send(Cmd::Announce(on));
    }

    /// Ask the DHT for the public hosts now; the node connects to them and they appear in peers().
    pub fn lookup(&self) {
        let _ = self.cmd.send(Cmd::Lookup);
    }

    /// Volunteer as a relay and DHT server, or stop; the app switches it as the phone plugs in or leaves Wi-Fi.
    pub fn help(&self, on: bool) {
        let _ = self.cmd.send(Cmd::Help(on));
    }

    /// One line per peer: PeerId, identify agent, on this network, connected, ever through a relay, a public host,
    /// hole-punched to a direct connection (0/1 each); tab separated.
    pub fn peers(&self) -> String {
        let s = self.state.lock().unwrap();
        let mut ids: HashSet<&PeerId> = s.addrs.keys().collect();
        ids.extend(s.connected.iter());
        ids.extend(s.hosts.iter());
        let mut out = String::new();
        for p in ids {
            let agent = s.agents.get(p).map(|a| a.replace(['\t', '\n'], " ")).unwrap_or_default();
            out.push_str(&format!(
                "{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
                p.to_base58(),
                agent,
                s.local.contains(p) as u8,
                s.connected.contains(p) as u8,
                s.relayed.contains(p) as u8,
                s.hosts.contains(p) as u8,
                s.punched.contains(p) as u8
            ));
        }
        out
    }

    /// PeerId, connected, known, reachability, relays holding a reservation for us, then the listen addresses.
    pub fn status(&self) -> String {
        let s = self.state.lock().unwrap();
        let mut out = format!(
            "{}\t{}\t{}\t{}\t{}",
            self.peer_id.to_base58(),
            s.connected.len(),
            s.addrs.len(),
            s.nat,
            s.relays.len()
        );
        for a in &s.listen {
            out.push('\t');
            out.push_str(&a.to_string());
        }
        out
    }

    pub fn stop(self) {
        self.stop.store(true, Ordering::Relaxed);
        self.rt.shutdown_timeout(Duration::from_secs(2));
    }
}

/// The addresses this node confirms to others (which also makes a helper's relay serve): relay circuits always (a
/// reservation is proof of reachability through that relay), and its own interface addresses only while it helps
/// and is confirmed reachable from the internet. A node behind NAT, or reachable only on a LAN or a VPN, never
/// offers relay spots that nobody outside could use; its own addresses still go into its DHT record and identify.
fn reachable_as(swarm: &mut Swarm<Behaviour>, st: &Arc<Mutex<State>>) {
    let direct = {
        let s = st.lock().unwrap();
        s.test_reachable || (s.worldwide && s.helping)
    };
    let mine: Vec<Multiaddr> = swarm.listeners().filter(|a| is_circuit(a) || (direct && dialable(a))).cloned().collect();
    if !direct {
        let stale: Vec<Multiaddr> = swarm.external_addresses().filter(|a| !is_circuit(a)).cloned().collect();
        for a in stale {
            swarm.remove_external_address(&a);
        }
    }
    for a in mine {
        swarm.add_external_address(a);
    }
}

/// Addresses for a peer arrived from the DHT: remember them, wake whoever waits, dial if this was a host lookup.
fn learned(
    swarm: &mut Swarm<Behaviour>,
    st: &Arc<Mutex<State>>,
    finds: &mut HashMap<kad::QueryId, (PeerId, Waiter, bool)>,
    id: kad::QueryId,
    target: PeerId,
    addrs: Vec<Multiaddr>,
) {
    for a in &addrs {
        swarm.add_peer_address(target, a.clone());
        st.lock().unwrap().addrs.entry(target).or_default().insert(a.clone());
    }
    if let Some(f) = finds.get_mut(&id) {
        if let Some(r) = f.1.lock().unwrap().take() {
            let _ = r.send(true);
        }
        if f.2 {
            f.2 = false;
            let _ = swarm.dial(target);
        }
    }
}

/// A lookup ended. If it was the last one for its waiter and nothing was found, say so.
fn finished(finds: &mut HashMap<kad::QueryId, (PeerId, Waiter, bool)>, id: kad::QueryId) {
    if let Some((_, waiter, _)) = finds.remove(&id) {
        if Arc::strong_count(&waiter) == 1 {
            if let Some(r) = waiter.lock().unwrap().take() {
                let _ = r.send(false);
            }
        }
    }
}

/// Nodes not confirmed reachable from the internet (behind NAT, still unknown, or reachable only on a LAN or a VPN
/// such as Tailscale) listen through up to two relays, so peers anywhere can reach them; DCUtR then tries to turn
/// each relayed connection into a direct one.
fn reserve(swarm: &mut Swarm<Behaviour>, st: &Arc<Mutex<State>>, hop: &HashMap<PeerId, Multiaddr>, relay_only: bool) {
    let (worldwide, have) = {
        let s = st.lock().unwrap();
        (s.worldwide, s.relays.len())
    };
    if (worldwide && !relay_only) || have >= RELAYS {
        return;
    }
    let listening: HashSet<PeerId> = swarm
        .listeners()
        .flat_map(|a| a.iter().filter_map(|p| if let Protocol::P2p(id) = p { Some(id) } else { None }).collect::<Vec<_>>())
        .collect();
    let mut asked = listening.len();
    for (relay, addr) in hop {
        if asked >= RELAYS {
            break;
        }
        if listening.contains(relay) {
            continue;
        }
        let circuit = addr.clone().with(Protocol::P2p(*relay)).with(Protocol::P2pCircuit);
        let r = swarm.listen_on(circuit.clone());
        debug!("reserve on {circuit}: {r:?}");
        if r.is_ok() {
            asked += 1;
        }
    }
}

// ---------- JNI: ai.jackknife.killayue.peer.KillaNet ----------

mod jni_api {
    use super::*;
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::{jboolean, jint, jstring};
    use jni::JNIEnv;

    static NODE: Mutex<Option<Node>> = Mutex::new(None);

    fn string(env: &mut JNIEnv, s: &JString) -> String {
        env.get_string(s).map(|v| v.into()).unwrap_or_default()
    }

    fn reply(env: &mut JNIEnv, s: String) -> jstring {
        env.new_string(s).map(|v| v.into_raw()).unwrap_or(std::ptr::null_mut())
    }

    /// Returns the PeerId, or "error: …". entry: newline-separated multiaddrs ending in /p2p/<PeerId>.
    #[no_mangle]
    pub extern "system" fn Java_ai_jackknife_killayue_peer_KillaNet_nativeStart(
        mut env: JNIEnv,
        _c: JClass,
        dir: JString,
        agent: JString,
        inbound_port: jint,
        token: JByteArray,
        flags: jint,
        port: jint,
        entry: JString,
    ) -> jstring {
        let dir = string(&mut env, &dir);
        let agent = string(&mut env, &agent);
        let entry = string(&mut env, &entry);
        let token = env.convert_byte_array(&token).unwrap_or_default();
        let mut node = NODE.lock().unwrap();
        if let Some(n) = node.as_ref() {
            return reply(&mut env, n.peer_id.to_base58());
        }
        if token.len() != 32 || !(1..=65535).contains(&inbound_port) || !(0..=65535).contains(&port) {
            return reply(&mut env, "error: bad bridge".into());
        }
        let options = Options {
            key_file: format!("{dir}/node.key"),
            peers_file: format!("{dir}/peers.txt"),
            agent,
            inbound_port: inbound_port as u16,
            token,
            flags,
            port: port as u16,
            entry: entry.lines().filter_map(|l| l.trim().parse().ok()).collect(),
        };
        let started = std::panic::catch_unwind(|| Node::start(options));
        let out = match started.unwrap_or_else(|_| Err("network node failed to start".into())) {
            Ok(n) => {
                let id = n.peer_id.to_base58();
                *node = Some(n);
                id
            }
            Err(e) => format!("error: {e}"),
        };
        reply(&mut env, out)
    }

    /// Returns a loopback port, or -1.
    #[no_mangle]
    pub extern "system" fn Java_ai_jackknife_killayue_peer_KillaNet_nativeOpen(
        mut env: JNIEnv,
        _c: JClass,
        peer: JString,
        token: JByteArray,
    ) -> jint {
        let peer = string(&mut env, &peer);
        let token = env.convert_byte_array(&token).unwrap_or_default();
        let Ok(peer) = peer.parse::<PeerId>() else { return -1 };
        if token.len() != 32 {
            return -1;
        }
        let guard = NODE.lock().unwrap();
        let Some(n) = guard.as_ref() else { return -1 };
        match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| n.open(peer, token))) {
            Ok(Ok(port)) => port as jint,
            _ => -1,
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_ai_jackknife_killayue_peer_KillaNet_nativeAnnounce(_env: JNIEnv, _c: JClass, on: jboolean) {
        if let Some(n) = NODE.lock().unwrap().as_ref() {
            n.announce(on != 0);
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_ai_jackknife_killayue_peer_KillaNet_nativeHelp(_env: JNIEnv, _c: JClass, on: jboolean) {
        if let Some(n) = NODE.lock().unwrap().as_ref() {
            n.help(on != 0);
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_ai_jackknife_killayue_peer_KillaNet_nativeLookup(_env: JNIEnv, _c: JClass) {
        if let Some(n) = NODE.lock().unwrap().as_ref() {
            n.lookup();
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_ai_jackknife_killayue_peer_KillaNet_nativePeers(mut env: JNIEnv, _c: JClass) -> jstring {
        let s = NODE.lock().unwrap().as_ref().map(|n| n.peers()).unwrap_or_default();
        reply(&mut env, s)
    }

    #[no_mangle]
    pub extern "system" fn Java_ai_jackknife_killayue_peer_KillaNet_nativeStatus(mut env: JNIEnv, _c: JClass) -> jstring {
        let s = NODE.lock().unwrap().as_ref().map(|n| n.status()).unwrap_or_default();
        reply(&mut env, s)
    }

    #[no_mangle]
    pub extern "system" fn Java_ai_jackknife_killayue_peer_KillaNet_nativeStop(_env: JNIEnv, _c: JClass) {
        if let Some(n) = NODE.lock().unwrap().take() {
            n.stop();
        }
    }
}
