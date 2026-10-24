//! The Killa YuE mixnet: Sphinx packets (Nym's `sphinx-packet` crate) through three mix nodes, as in Loopix.
//!
//! This module is the packet layer and nothing else: mix keys and their signed descriptors, building packets and
//! reply blocks (SURBs), and what a mix does with a packet it receives. It never touches the network; lib.rs moves
//! the frames, holds them for their delays and picks the paths. See docs/designs/2026-10-24-mixnet-and-hidden-artists.md.

use std::collections::HashSet;
use std::time::Duration;

use libp2p::{identity, PeerId};
use sha2::{Digest, Sha256};
use sphinx_packet::{
    header::delays::{self, Delay},
    packet::builder::SphinxPacketBuilder,
    payload::Payload,
    route::{Destination, DestinationAddressBytes, Node, NodeAddressBytes, SURBIdentifier},
    surb::{SURBMaterial, SurbReplyRecovery, SURB},
    version::SINGLE_SEED_SURB_VERSION,
    ProcessedPacketData, SphinxPacket,
};
use x25519_dalek::{PublicKey, StaticSecret};

/// Every packet's payload, whatever it carries: a chat line, a song chunk, an acknowledgement or nothing.
pub const PAYLOAD: usize = 2048;
/// The most a sender can put in one packet.
pub const MESSAGE: usize = PAYLOAD - sphinx_packet::payload::PAYLOAD_OVERHEAD_SIZE;
/// One packet on the wire.
pub const FRAME: usize = sphinx_packet::header::HEADER_SIZE + PAYLOAD;
/// One reply block, as carried inside a message.
pub const SURB_LEN: usize = sphinx_packet::header::HEADER_SIZE
    + sphinx_packet::constants::NODE_ADDRESS_LENGTH
    + sphinx_packet::constants::PAYLOAD_KEY_SEED_SIZE;
pub const HOPS: usize = 3;
/// Mean hold time at each hop (exponential, chosen by the sender).
pub const MEAN_DELAY: Duration = Duration::from_millis(500);
/// Mix keys change every day; the previous one still opens packets for an hour.
pub const DAY: u64 = 24 * 3600;
pub const OVERLAP: u64 = 3600;

/// What the last hop does with a packet, read from the first byte of the identifier.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Service {
    /// cover: dropped
    Loop = 0,
    /// handed to the peer whose key is the destination
    Deliver = 1,
    /// kept by the last hop itself: a chunk of a hidden song it adopts
    Adopt = 2,
    /// a reply through a SURB, handed to the client that uses this mix as its gateway
    Reply = 3,
}

impl Service {
    fn from_byte(b: u8) -> Option<Service> {
        match b {
            0 => Some(Service::Loop),
            1 => Some(Service::Deliver),
            2 => Some(Service::Adopt),
            3 => Some(Service::Reply),
            _ => None,
        }
    }
}

/// The 32-byte ed25519 key that names a node in a Sphinx route; its PeerId follows from it, so a hop cannot be
/// pointed at someone else.
pub fn address(key: &identity::PublicKey) -> Option<[u8; 32]> {
    key.clone().try_into_ed25519().ok().map(|k| k.to_bytes())
}

pub fn peer(address: &[u8; 32]) -> Option<PeerId> {
    let k = identity::ed25519::PublicKey::try_from_bytes(address).ok()?;
    Some(PeerId::from_public_key(&identity::PublicKey::from(k)))
}

pub fn peer_address(peer: &PeerId) -> Option<[u8; 32]> {
    let hash = libp2p::multihash::Multihash::<64>::from_bytes(&peer.to_bytes()).ok()?;
    if hash.code() != 0 {
        return None; // not an identity hash: an RSA or other key we never use
    }
    address(&identity::PublicKey::try_decode_protobuf(hash.digest()).ok()?)
}

/// A mix node as a sender sees it: who it is and the mix key it published for today.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MixNode {
    pub address: [u8; 32],
    pub mix: [u8; 32],
    pub day: u64,
}

impl MixNode {
    fn node(&self) -> Node {
        Node::new(NodeAddressBytes::from_bytes(self.address), PublicKey::from(self.mix))
    }
}

/// A mix node's keys: derived from a secret seed kept in the node's files, one per day, so a restart keeps them.
pub struct MixKeys {
    seed: [u8; 32],
}

fn day_secret(seed: &[u8; 32], day: u64) -> StaticSecret {
    let mut h = Sha256::new();
    h.update(b"killa mix key 1");
    h.update(seed);
    h.update(day.to_be_bytes());
    let bytes: [u8; 32] = h.finalize().into();
    StaticSecret::from(bytes)
}

impl MixKeys {
    pub fn new(seed: [u8; 32]) -> MixKeys {
        MixKeys { seed }
    }
    pub fn public(&self, day: u64) -> [u8; 32] {
        PublicKey::from(&day_secret(&self.seed, day)).to_bytes()
    }
    /// The days whose keys open packets at `now` (seconds since 1970): today, and yesterday during the overlap.
    pub fn days(now: u64) -> Vec<u64> {
        let today = now / DAY;
        if now % DAY < OVERLAP && today > 0 { vec![today, today - 1] } else { vec![today] }
    }
}

const DESCRIPTOR_TAG: &[u8] = b"killa mix descriptor 1";

/// The DHT record a mix node publishes for one day: version, day, mix key, signed by its network identity.
pub fn descriptor(key: &identity::Keypair, keys: &MixKeys, day: u64) -> Result<Vec<u8>, String> {
    let mix = keys.public(day);
    let mut signed = DESCRIPTOR_TAG.to_vec();
    signed.extend_from_slice(&day.to_be_bytes());
    signed.extend_from_slice(&mix);
    let sig = key.sign(&signed).map_err(|e| e.to_string())?;
    let mut out = vec![1u8];
    out.extend_from_slice(&day.to_be_bytes());
    out.extend_from_slice(&mix);
    out.extend_from_slice(&sig);
    Ok(out)
}

/// Checks a descriptor against the PeerId that published it.
pub fn read_descriptor(publisher: &PeerId, bytes: &[u8]) -> Option<MixNode> {
    if bytes.len() != 1 + 8 + 32 + 64 || bytes[0] != 1 {
        return None;
    }
    let day = u64::from_be_bytes(bytes[1..9].try_into().ok()?);
    let mix: [u8; 32] = bytes[9..41].try_into().ok()?;
    let address = peer_address(publisher)?;
    let key = identity::PublicKey::from(identity::ed25519::PublicKey::try_from_bytes(&address).ok()?);
    let mut signed = DESCRIPTOR_TAG.to_vec();
    signed.extend_from_slice(&day.to_be_bytes());
    signed.extend_from_slice(&mix);
    key.verify(&signed, &bytes[41..]).then_some(MixNode { address, mix, day })
}

fn identifier(service: Service) -> SURBIdentifier {
    let mut id: SURBIdentifier = rand::random();
    id[0] = service as u8;
    id
}

fn route(path: &[MixNode]) -> Result<Vec<Node>, String> {
    if path.len() != HOPS {
        return Err(format!("a mixnet path has {HOPS} hops"));
    }
    Ok(path.iter().map(MixNode::node).collect())
}

/// A packet through `path` (gateway first, last hop last) for `service`; `to` names the receiving peer for Deliver.
pub fn packet(path: &[MixNode], service: Service, to: [u8; 32], message: &[u8], mean: Duration)
    -> Result<Vec<u8>, String> {
    let destination = Destination::new(DestinationAddressBytes::from_bytes(to), identifier(service));
    let delays: Vec<Delay> = delays::generate_from_average_duration(HOPS, mean);
    let p = SphinxPacketBuilder::new()
        .with_payload_size(PAYLOAD)
        .build_packet(message, &route(path)?, &destination, &delays)
        .map_err(|e| e.to_string())?;
    Ok(p.to_bytes())
}

/// A reply block whose route ends at `path`'s last node (the client's own gateway), addressed to `client`.
/// Returns the block to send away and the recovery material the client keeps to read the reply.
pub fn reply_block(path: &[MixNode], client: [u8; 32], mean: Duration) -> Result<(Vec<u8>, Vec<u8>), String> {
    let destination = Destination::new(DestinationAddressBytes::from_bytes(client), identifier(Service::Reply));
    let delays = delays::generate_from_average_duration(HOPS, mean);
    let (surb, recovery) = SURBMaterial::new(route(path)?, delays, destination, SINGLE_SEED_SURB_VERSION)
        .construct_recoverable_SURB()
        .map_err(|e| e.to_string())?;
    Ok((surb.to_bytes(), recovery.to_bytes()))
}

/// Answers through someone's reply block: the packet and the mix to hand it to.
pub fn use_reply_block(block: &[u8], message: &[u8]) -> Result<(Vec<u8>, [u8; 32]), String> {
    let surb = SURB::from_bytes(block).map_err(|e| e.to_string())?;
    let (p, first) = surb.use_surb(message, PAYLOAD).map_err(|e| e.to_string())?;
    Ok((p.to_bytes(), first.to_bytes()))
}

/// The identifier a reply arrives with, to find the recovery material kept for it.
pub fn recovery_id(recovery: &[u8]) -> Option<SURBIdentifier> {
    SurbReplyRecovery::from_bytes(recovery).ok().map(|r| *r.identifier())
}

/// Reads a reply that arrived through one of our reply blocks.
pub fn open_reply(recovery: &[u8], sealed: &[u8]) -> Result<Vec<u8>, String> {
    let r = SurbReplyRecovery::from_bytes(recovery).map_err(|e| e.to_string())?;
    let p = Payload::from_bytes(sealed).map_err(|e| e.to_string())?;
    r.recover_plaintext(p).map_err(|e| e.to_string())
}

/// What a mix does with a packet.
#[derive(Debug)]
pub enum Out {
    /// hold it for `delay`, then pass `frame` to the node named `next`
    Forward { next: [u8; 32], delay: Duration, frame: Vec<u8> },
    /// this was the last hop. For Reply the payload is still sealed for the client; otherwise it is the message.
    Final { service: Service, to: [u8; 32], id: SURBIdentifier, payload: Vec<u8> },
}

/// A mix node's packet processing: its keys and the replay tags it has seen under each of them.
pub struct Mixer {
    keys: MixKeys,
    seen: Vec<(u64, HashSet<[u8; 32]>)>,
}

/// How many replay tags a mix remembers per key: a day of a busy helper. Past that it refuses new packets
/// rather than forgetting old ones, which would let a replay through.
const SEEN_MAX: usize = 4_000_000;

impl Mixer {
    pub fn new(keys: MixKeys) -> Mixer {
        Mixer { keys, seen: Vec::new() }
    }
    pub fn keys(&self) -> &MixKeys {
        &self.keys
    }
    pub fn process(&mut self, frame: &[u8], now: u64) -> Result<Out, String> {
        if frame.len() != FRAME {
            return Err("wrong packet size".into());
        }
        let days = MixKeys::days(now);
        self.seen.retain(|(d, _)| days.contains(d));
        for day in days {
            let p = SphinxPacket::from_bytes(frame).map_err(|e| e.to_string())?;
            let secret = day_secret(&self.keys.seed, day);
            let expanded = p.header.compute_expanded_shared_secret(&secret);
            if p.header.ensure_header_integrity(&expanded).is_err() {
                continue; // not for this day's key
            }
            let tag = *expanded.replay_tag();
            let seen = match self.seen.iter_mut().find(|(d, _)| *d == day) {
                Some((_, s)) => s,
                None => {
                    self.seen.push((day, HashSet::new()));
                    &mut self.seen.last_mut().unwrap().1
                }
            };
            if seen.contains(&tag) {
                return Err("replayed packet".into());
            }
            if seen.len() >= SEEN_MAX {
                return Err("mix is full for today".into());
            }
            seen.insert(tag);
            let done = p.process_with_expanded_secret(&expanded).map_err(|e| e.to_string())?;
            return match done.data {
                ProcessedPacketData::ForwardHop { next_hop_packet, next_hop_address, delay } => Ok(Out::Forward {
                    next: next_hop_address.to_bytes(),
                    delay: delay.to_duration().min(Duration::from_secs(60)),
                    frame: next_hop_packet.to_bytes(),
                }),
                ProcessedPacketData::FinalHop { destination, identifier, payload } => {
                    let service = Service::from_byte(identifier[0]).ok_or("unknown service")?;
                    let payload = if service == Service::Reply {
                        payload.into_bytes()
                    } else {
                        payload.recover_plaintext().map_err(|e| e.to_string())?
                    };
                    Ok(Out::Final { service, to: destination.as_bytes(), id: identifier, payload })
                }
            };
        }
        Err("no mix key opens this packet".into())
    }
}

/// Proof of work bound to `what` (a song's ID): a nonce whose hash starts with `bits` zero bits.
pub fn stamp(what: &[u8], bits: u32) -> u64 {
    (0u64..).find(|n| stamp_ok(what, bits, *n)).unwrap()
}

pub fn stamp_ok(what: &[u8], bits: u32, nonce: u64) -> bool {
    let mut h = Sha256::new();
    h.update(b"killa stamp 1");
    h.update(what);
    h.update(nonce.to_be_bytes());
    let d: [u8; 32] = h.finalize().into();
    let lead = u128::from_be_bytes(d[..16].try_into().unwrap()).leading_zeros();
    lead >= bits
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Net {
        mixers: Vec<Mixer>,
        nodes: Vec<MixNode>,
    }

    const NOW: u64 = 20_000 * DAY + 7 * 3600;

    fn net(n: usize) -> Net {
        let mut mixers = Vec::new();
        let mut nodes = Vec::new();
        for i in 0..n {
            let k = identity::Keypair::generate_ed25519();
            let m = MixKeys::new([i as u8 + 1; 32]);
            let d = descriptor(&k, &m, NOW / DAY).unwrap();
            nodes.push(read_descriptor(&PeerId::from(k.public()), &d).unwrap());
            mixers.push(Mixer::new(m));
        }
        Net { mixers, nodes }
    }

    impl Net {
        fn at(&self, a: &[u8; 32]) -> usize {
            self.nodes.iter().position(|n| &n.address == a).unwrap()
        }
        /// runs a frame from mix `first` until it leaves the mixnet
        fn run(&mut self, mut at: usize, mut frame: Vec<u8>) -> (usize, Out) {
            loop {
                match self.mixers[at].process(&frame, NOW).unwrap() {
                    Out::Forward { next, frame: f, delay } => {
                        assert!(delay < Duration::from_secs(61));
                        at = self.at(&next);
                        frame = f;
                    }
                    out => return (at, out),
                }
            }
        }
    }

    #[test]
    fn three_hops_deliver() {
        let mut n = net(4);
        let path = vec![n.nodes[0].clone(), n.nodes[2].clone(), n.nodes[3].clone()];
        let to = [9u8; 32];
        let f = packet(&path, Service::Deliver, to, b"hello through the mix", MEAN_DELAY).unwrap();
        assert_eq!(f.len(), FRAME);
        let (exit, out) = n.run(0, f);
        assert_eq!(exit, 3);
        match out {
            Out::Final { service, to: t, payload, .. } => {
                assert_eq!(service, Service::Deliver);
                assert_eq!(t, to);
                assert_eq!(payload, b"hello through the mix");
            }
            _ => panic!(),
        }
    }

    #[test]
    fn every_packet_is_the_same_size() {
        let n = net(3);
        let small = packet(&n.nodes, Service::Loop, [0; 32], b"", MEAN_DELAY).unwrap();
        let full = packet(&n.nodes, Service::Adopt, [0; 32], &[7u8; MESSAGE], MEAN_DELAY).unwrap();
        assert_eq!(small.len(), full.len());
        assert!(packet(&n.nodes, Service::Adopt, [0; 32], &[7u8; MESSAGE + 1], MEAN_DELAY).is_err());
    }

    #[test]
    fn replays_are_refused() {
        let mut n = net(3);
        let f = packet(&n.nodes, Service::Loop, [0; 32], b"x", MEAN_DELAY).unwrap();
        assert!(n.mixers[0].process(&f, NOW).is_ok());
        assert_eq!(n.mixers[0].process(&f, NOW).unwrap_err(), "replayed packet");
    }

    #[test]
    fn a_wrong_or_stale_key_opens_nothing() {
        let mut n = net(3);
        let f = packet(&n.nodes, Service::Loop, [0; 32], b"x", MEAN_DELAY).unwrap();
        assert!(n.mixers[1].process(&f, NOW).is_err()); // meant for mix 0
        assert!(n.mixers[0].process(&f, NOW + 2 * DAY).is_err()); // two days later the key is gone
        let mut torn = f.clone();
        torn[100] ^= 1;
        assert!(n.mixers[0].process(&torn, NOW).is_err());
    }

    #[test]
    fn yesterdays_key_works_for_an_hour() {
        let mut n = net(3);
        let f = packet(&n.nodes, Service::Loop, [0; 32], b"x", MEAN_DELAY).unwrap();
        let tomorrow = (NOW / DAY + 1) * DAY;
        assert!(n.mixers[0].process(&f, tomorrow + 1800).is_ok());
        let g = packet(&n.nodes, Service::Loop, [0; 32], b"x", MEAN_DELAY).unwrap();
        assert!(n.mixers[0].process(&g, tomorrow + 2 * OVERLAP).is_err());
    }

    #[test]
    fn replies_come_back_through_a_reply_block() {
        let mut n = net(5);
        let client = [42u8; 32];
        // the reply's route ends at the client's gateway, mix 4
        let back = vec![n.nodes[1].clone(), n.nodes[2].clone(), n.nodes[4].clone()];
        let (block, recovery) = reply_block(&back, client, MEAN_DELAY).unwrap();
        assert_eq!(block.len(), SURB_LEN);
        // the answering side only has the block
        let (frame, first) = use_reply_block(&block, b"got your song, chunks 0-31").unwrap();
        let (exit, out) = n.run(n.at(&first), frame);
        assert_eq!(exit, 4);
        match out {
            Out::Final { service, to, id, payload } => {
                assert_eq!(service, Service::Reply);
                assert_eq!(to, client);
                assert_eq!(Some(id), recovery_id(&recovery));
                assert_ne!(payload.windows(5).position(|w| w == b"chunk"), Some(0)); // sealed in transit
                assert_eq!(open_reply(&recovery, &payload).unwrap(), b"got your song, chunks 0-31");
            }
            _ => panic!(),
        }
    }

    #[test]
    fn a_reply_block_fits_in_a_message() {
        assert!(SURB_LEN + 512 < MESSAGE);
    }

    #[test]
    fn descriptors_are_bound_to_their_publisher() {
        let k = identity::Keypair::generate_ed25519();
        let other = identity::Keypair::generate_ed25519();
        let m = MixKeys::new([3; 32]);
        let d = descriptor(&k, &m, 5).unwrap();
        assert!(read_descriptor(&PeerId::from(k.public()), &d).is_some());
        assert!(read_descriptor(&PeerId::from(other.public()), &d).is_none());
        let mut forged = d.clone();
        forged[20] ^= 1; // another mix key
        assert!(read_descriptor(&PeerId::from(k.public()), &forged).is_none());
        let a = peer_address(&PeerId::from(k.public())).unwrap();
        assert_eq!(peer(&a), Some(PeerId::from(k.public())));
    }

    #[test]
    fn stamps() {
        let n = stamp(b"song", 12);
        assert!(stamp_ok(b"song", 12, n));
        assert!((0..64).filter(|i| stamp_ok(b"song", 12, *i)).count() < 4);
    }
}
