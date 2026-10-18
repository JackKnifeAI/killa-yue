//! The Killa YuE network node. rust-libp2p finds peers and opens encrypted streams to them (QUIC first, TCP with
//! Noise and yamux as the fallback); HAMMER's own pinned TLS connections ride inside those streams unchanged.
//!
//! Java talks to the node through four calls (see `peer/KillaNet.java`):
//! - start: load or make the node key, listen, look for peers on the same network (mDNS);
//! - open: a one-use loopback port that carries one HAMMER connection to a peer;
//! - peers / status: what the node knows, as tab-separated lines (no JSON, so the desktop needs no library).
//! Inbound HAMMER streams are handed to Java's loopback bridge with a token and the remote PeerId first, so HAMMER's
//! admission limits apply per peer, not to the loopback address.

use futures::StreamExt;
use libp2p::{
    identify, identity, mdns, noise, ping,
    swarm::{NetworkBehaviour, SwarmEvent},
    tcp, yamux, Multiaddr, PeerId, StreamProtocol,
};
use std::collections::{HashMap, HashSet};
use std::net::Ipv4Addr;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{mpsc, Semaphore};
use tokio_util::compat::FuturesAsyncReadCompatExt;

pub const HAMMER: StreamProtocol = StreamProtocol::new("/killa-yue/hammer/1.0.0");
const IDENTIFY: &str = "/killa-yue/id/1.0.0";
/// Inbound HAMMER streams handled at once; HAMMER's own guard limits each peer further.
const INBOUND_AT_ONCE: usize = 16;
/// Peers dialled automatically when found on the same network, so their identity (and HAMMER pin) is learned.
const LOCAL_DIALS: usize = 32;

#[derive(NetworkBehaviour)]
struct Behaviour {
    mdns: mdns::tokio::Behaviour,
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
    local: HashSet<PeerId>,
}

pub struct Node {
    rt: tokio::runtime::Runtime,
    control: libp2p_stream::Control,
    state: Arc<Mutex<State>>,
    stop: mpsc::UnboundedSender<()>,
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

impl Node {
    pub fn start(key_file: &str, agent: &str, inbound_port: u16, token: Vec<u8>) -> Result<Node, String> {
        let key = load_key(key_file)?;
        let peer_id = key.public().to_peer_id();
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("killanet")
            .enable_all()
            .build()
            .map_err(|e| e.to_string())?;
        let agent = agent.to_string();
        let _inside = rt.enter(); // QUIC and mDNS bind their sockets on the runtime
        let mut swarm = rt.block_on(async {
            libp2p::SwarmBuilder::with_existing_identity(key)
                .with_tokio()
                .with_tcp(tcp::Config::default().nodelay(true), noise::Config::new, yamux::Config::default)
                .map_err(|e| e.to_string())?
                .with_quic()
                .with_behaviour(|key| {
                    Ok(Behaviour {
                        mdns: mdns::tokio::Behaviour::new(mdns::Config::default(), key.public().to_peer_id())?,
                        identify: identify::Behaviour::new(
                            identify::Config::new(IDENTIFY.into(), key.public())
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
        for addr in ["/ip4/0.0.0.0/udp/0/quic-v1", "/ip6/::/udp/0/quic-v1", "/ip4/0.0.0.0/tcp/0", "/ip6/::/tcp/0"] {
            // IPv6 may be missing on some networks; one working listener is enough
            let _ = swarm.listen_on(addr.parse::<Multiaddr>().unwrap());
        }
        let control = swarm.behaviour().stream.new_control();
        let mut incoming = control
            .clone()
            .accept(HAMMER)
            .map_err(|e| format!("stream protocol: {e}"))?;
        let state = Arc::new(Mutex::new(State::default()));
        let (stop, mut stopped) = mpsc::unbounded_channel::<()>();

        // inbound HAMMER streams → Java's loopback bridge, token and PeerId first
        let gate = Arc::new(Semaphore::new(INBOUND_AT_ONCE));
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

        // the swarm itself: listen addresses, peers found nearby, who they are, who is connected
        let st = state.clone();
        rt.spawn(async move {
            let mut dialled: HashSet<PeerId> = HashSet::new();
            loop {
                tokio::select! {
                    _ = stopped.recv() => break,
                    event = swarm.select_next_some() => match event {
                        SwarmEvent::NewListenAddr { address, .. } => st.lock().unwrap().listen.push(address),
                        SwarmEvent::ExpiredListenAddr { address, .. } => st.lock().unwrap().listen.retain(|a| a != &address),
                        SwarmEvent::ConnectionEstablished { peer_id, .. } => { st.lock().unwrap().connected.insert(peer_id); }
                        SwarmEvent::ConnectionClosed { peer_id, num_established, .. } => {
                            if num_established == 0 { st.lock().unwrap().connected.remove(&peer_id); }
                        }
                        SwarmEvent::Behaviour(BehaviourEvent::Mdns(mdns::Event::Discovered(found))) => {
                            for (peer, addr) in found {
                                swarm.add_peer_address(peer, addr.clone());
                                {
                                    let mut s = st.lock().unwrap();
                                    s.addrs.entry(peer).or_default().insert(addr);
                                    s.local.insert(peer);
                                }
                                if dialled.len() < LOCAL_DIALS && dialled.insert(peer) {
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
                                }
                            }
                        }
                        SwarmEvent::Behaviour(BehaviourEvent::Identify(identify::Event::Received { peer_id, info, .. })) => {
                            let mut s = st.lock().unwrap();
                            s.agents.insert(peer_id, info.agent_version);
                            let set = s.addrs.entry(peer_id).or_default();
                            for a in info.listen_addrs.into_iter().take(16) { set.insert(a); }
                        }
                        _ => {}
                    }
                }
            }
        });
        Ok(Node { rt, control, state, stop, peer_id })
    }

    /// A loopback port for one HAMMER connection to `peer`. The connecting side must send `token` first; anything
    /// else, or nobody within 15 seconds, closes the port. The libp2p stream is opened only after the token checks.
    pub fn open(&self, peer: PeerId, token: Vec<u8>) -> Result<u16, String> {
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).map_err(|e| e.to_string())?;
        listener.set_nonblocking(true).map_err(|e| e.to_string())?;
        let port = listener.local_addr().map_err(|e| e.to_string())?.port();
        let mut control = self.control.clone();
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
            let Ok(Ok(stream)) = tokio::time::timeout(Duration::from_secs(30), control.open_stream(peer, HAMMER)).await else { return };
            let mut remote = stream.compat();
            let _ = tokio::io::copy_bidirectional(&mut local, &mut remote).await;
        });
        Ok(port)
    }

    /// One line per peer: PeerId, identify agent, on this network (0/1), connected (0/1); tab separated.
    pub fn peers(&self) -> String {
        let s = self.state.lock().unwrap();
        let mut ids: HashSet<&PeerId> = s.addrs.keys().collect();
        ids.extend(s.connected.iter());
        let mut out = String::new();
        for p in ids {
            let agent = s.agents.get(p).map(|a| a.replace(['\t', '\n'], " ")).unwrap_or_default();
            out.push_str(&format!(
                "{}\t{}\t{}\t{}\n",
                p.to_base58(),
                agent,
                s.local.contains(p) as u8,
                s.connected.contains(p) as u8
            ));
        }
        out
    }

    /// PeerId, connected peers, known peers, then each listen address; tab separated, one line.
    pub fn status(&self) -> String {
        let s = self.state.lock().unwrap();
        let mut out = format!("{}\t{}\t{}", self.peer_id.to_base58(), s.connected.len(), s.addrs.len());
        for a in &s.listen {
            out.push('\t');
            out.push_str(&a.to_string());
        }
        out
    }

    pub fn stop(self) {
        let _ = self.stop.send(());
        self.rt.shutdown_timeout(Duration::from_secs(2));
    }
}

// ---------- JNI: ai.jackknife.killayue.peer.KillaNet ----------

mod jni_api {
    use super::*;
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::{jint, jstring};
    use jni::JNIEnv;

    static NODE: Mutex<Option<Node>> = Mutex::new(None);

    fn string(env: &mut JNIEnv, s: &JString) -> String {
        env.get_string(s).map(|v| v.into()).unwrap_or_default()
    }

    fn reply(env: &mut JNIEnv, s: String) -> jstring {
        env.new_string(s).map(|v| v.into_raw()).unwrap_or(std::ptr::null_mut())
    }

    /// Returns the PeerId, or "error: …".
    #[no_mangle]
    pub extern "system" fn Java_ai_jackknife_killayue_peer_KillaNet_nativeStart(
        mut env: JNIEnv,
        _c: JClass,
        key_file: JString,
        agent: JString,
        inbound_port: jint,
        token: JByteArray,
    ) -> jstring {
        let key_file = string(&mut env, &key_file);
        let agent = string(&mut env, &agent);
        let token = env.convert_byte_array(&token).unwrap_or_default();
        let mut node = NODE.lock().unwrap();
        if let Some(n) = node.as_ref() {
            return reply(&mut env, n.peer_id.to_base58());
        }
        if token.len() != 32 || !(1..=65535).contains(&inbound_port) {
            return reply(&mut env, "error: bad bridge".into());
        }
        let started = std::panic::catch_unwind(|| Node::start(&key_file, &agent, inbound_port as u16, token));
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
