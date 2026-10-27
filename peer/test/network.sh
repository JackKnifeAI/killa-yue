#!/bin/bash
# Killa YuE network nodes in separate processes on this machine, real libp2p and real TLS, three ways:
#   1. same network: found by mDNS;
#   2. worldwide lookup: no mDNS; the listener knows one entry peer and finds the host in the DHT's public host list;
#   3. behind NAT: the host has no reachable address at all and is reached through a relay peer.
#   4. moved from Wi-Fi to mobile data: the listener remembers peers that no longer answer and still finds the host.
#   5. a host changes networks: it drops its relay links, takes a new relay spot and is reached through it again.
#   6. a crowd: ten listeners at once reach the host behind NAT through the same relay.
#   7. the mixnet: three helpers carry a message between two clients, the answer and cover loops come back.
#   8. Protection: a vote and a two-packet comment reach a host behind NAT through the mixnet, and are taken.
#   9. a hidden artist: a song goes in pieces through the mixnet to three helpers that adopt and host it.
# Run it on a machine with real network interfaces: steps 1-6 need mDNS and dialable addresses. In a PRoot, where
# interfaces cannot be listed, only step 7 can run, with KILLANET_LOOPBACK=1 so nodes bind loopback and find each other.
# Needs the host builds of libhammer.so (peer/test/run.sh makes it) and libkillanet.so (net/build.sh host).
set -euo pipefail
cd "$(dirname "$0")/../.."
hammer="${HAMMER_HOST_BUILD:-$PWD/build-hammer-host}"
net="${KILLANET_HOST_BUILD:-$PWD/net/target/release}"
tmp=$(mktemp -d)
pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done; rm -rf "$tmp"; }
trap cleanup EXIT INT TERM
mkdir -p "$tmp/classes"
javac -Xlint:-options --release 8 -d "$tmp/classes" app/src/ai/jackknife/killayue/peer/*.java \
    peer/test/HammerIntegration.java peer/test/NetworkTest.java
key() {
    keytool -genkeypair -alias test -keyalg EC -groupname secp256r1 -dname "CN=HAMMER $1 test only" -validity 2 \
        -storetype PKCS12 -keystore "$tmp/$1.p12" -storepass hammer-test-only -noprompt >/dev/null 2>&1
}
# preferIPv6Addresses: the JDK then answers ::1 for the loopback address, as Android always does
# ${EXEC} is "exec" for background nodes, so the PID kept is java's own and cleanup really stops it
run() { ${EXEC:-} java --enable-native-access=ALL-UNNAMED -Djava.net.preferIPv6Addresses=true -Djava.library.path="$hammer:$net" -cp "$tmp/classes" \
        ai.jackknife.killayue.peer.NetworkTest "$@"; }
background() {  # name, then NetworkTest arguments; waits for "ready"
    local name=$1; shift
    EXEC=exec run "$@" > "$tmp/$name.log" 2>&1 &
    pids+=($!)
    for i in $(seq 1 150); do grep -q ready "$tmp/$name.log" 2>/dev/null && return; sleep 0.2; done
    cat "$tmp/$name.log"; echo "$name did not start"; exit 1
}
stop_all() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done; pids=(); sleep 1; }
for who in relay host1 listen1 host2 listen2 host3 listen3 listen4 host5 listen5 crowd{1..10} mix1 mix2 mix3 mixsend mixlisten mixhost mixvote adopt1 adopt2 adopt3 hidden; do key $who; done
NOMAIN=4; NOMDNS=2; RELAYONLY=8; NOUPNP=16

echo "== 1. same network (mDNS)"
background host1 host "$tmp/host1.p12" "$tmp/host1" "$tmp/card1" $((NOMAIN|NOUPNP))
run listen "$tmp/listen1.p12" "$tmp/listen1" "$tmp/card1" $((NOMAIN|NOUPNP))
stop_all

echo "== 2. worldwide lookup (no mDNS; one entry peer; DHT public host list)"
background relay relay "$tmp/relay.p12" "$tmp/relay" "$tmp/entry"
background host2 host "$tmp/host2.p12" "$tmp/host2" "$tmp/card2" $((NOMAIN|NOMDNS|NOUPNP)) "$tmp/entry"
sleep 3  # the host offers itself in the DHT
run listen "$tmp/listen2.p12" "$tmp/listen2" "$tmp/card2" $((NOMAIN|NOMDNS|NOUPNP)) "$tmp/entry"

echo "== 3. behind NAT (host has no reachable address; reached through the relay)"
background host3 host "$tmp/host3.p12" "$tmp/host3" "$tmp/card3" $((NOMAIN|NOMDNS|NOUPNP|RELAYONLY)) "$tmp/entry"
sleep 4  # the host reserves a slot on the relay and publishes its signed address record
run listen "$tmp/listen3.p12" "$tmp/listen3" "$tmp/card3" $((NOMAIN|NOMDNS|NOUPNP)) "$tmp/entry" relayed

echo "== 4. moved from Wi-Fi to mobile data (remembered peers that never answer; host reached through the relay)"
# 200 peers remembered from another network, as many as a node keeps: enough to fill the DHT routing table. Their
# TEST-NET addresses (RFC 5737) are never routed, so every dial hangs, as home Wi-Fi peers do on mobile data.
mkdir -p "$tmp/listen4/network"
cp peer/test/remembered-elsewhere.txt "$tmp/listen4/network/peers.txt"
run listen "$tmp/listen4.p12" "$tmp/listen4" "$tmp/card3" $((NOMAIN|NOMDNS|NOUPNP)) "$tmp/entry" relayed

echo "== 5. a host changes networks (drops its relay links, takes a new spot, reached through it again)"
MOVE_AFTER=4 background host5 host "$tmp/host5.p12" "$tmp/host5" "$tmp/card5" $((NOMAIN|NOMDNS|NOUPNP|RELAYONLY)) "$tmp/entry"
sleep 4  # the new relay spot and the signed address record that names it
run listen "$tmp/listen5.p12" "$tmp/listen5" "$tmp/card5" $((NOMAIN|NOMDNS|NOUPNP)) "$tmp/entry" relayed

echo "== 6. a crowd (ten listeners at once reach the host behind NAT through one relay and stay connected)"
crowd=()
for i in $(seq 1 10); do
    LINGER=20 run listen "$tmp/crowd$i.p12" "$tmp/crowd$i" "$tmp/card3" $((NOMAIN|NOMDNS|NOUPNP)) "$tmp/entry" relayed > "$tmp/crowd$i.log" 2>&1 &
    crowd+=($!)
done
failed=0
for i in $(seq 1 10); do wait "${crowd[$((i-1))]}" || { failed=$((failed+1)); grep -vE "^\[killanet\]|^\s+at " "$tmp/crowd$i.log" | tail -4; }; done
[ "$failed" = 0 ] || { echo "$failed of 10 listeners could not reach the host"; exit 1; }
echo "all 10 listeners reached the host through the relay"

stop_all
echo "== 7. the mixnet (three helpers mix a message between two clients; only the gateway sees the sender)"
# three helpers serve as mix nodes (reachable, so they publish descriptors and offer themselves), and two clients
# talk through them: the sender's message rides three hops, and the answer comes back through a reply block
background mix1 relay "$tmp/mix1.p12" "$tmp/mix1" "$tmp/mixentry"
background mix2 relay "$tmp/mix2.p12" "$tmp/mix2" "$tmp/mix2entry" 0 "$tmp/mixentry"
background mix3 relay "$tmp/mix3.p12" "$tmp/mix3" "$tmp/mix3entry" 0 "$tmp/mixentry"
sleep 3  # the helpers publish their mix descriptors and offer themselves in the DHT
background mixlisten mixlisten "$tmp/mixlisten.p12" "$tmp/mixlisten" "$tmp/mixpeer" $((NOMAIN|NOMDNS|NOUPNP)) "$tmp/mixentry"
for i in $(seq 1 100); do [ -s "$tmp/mixpeer" ] && break; sleep 0.2; done
run mixsend "$tmp/mixsend.p12" "$tmp/mixsend" "$tmp/mixpeer" $((NOMAIN|NOMDNS|NOUPNP)) "$tmp/mixentry"
echo "a message went through three helpers and the answer came back through the reply block"

echo "== 8. Protection (a vote and a long comment reach a host behind NAT through the mixnet and are taken)"
background mixhost mixhost "$tmp/mixhost.p12" "$tmp/mixhost" "$tmp/mixcard" $((NOMAIN|NOMDNS|NOUPNP)) "$tmp/mixentry"
sleep 4  # the host takes a relay spot and publishes the address record that names it
run mixvote "$tmp/mixvote.p12" "$tmp/mixvote" "$tmp/mixcard" $((NOMAIN|NOMDNS|NOUPNP)) "$tmp/mixentry"
echo "the host took a protected vote and comment, and its refusal came back with the reason"

echo "== 9. a hidden artist (a song in pieces through the mixnet to three helpers that adopt it)"
for i in 1 2 3; do
    background adopt$i adopter "$tmp/adopt$i.p12" "$tmp/adopt$i" "$tmp/adopt${i}entry" 0 "$tmp/mixentry"
done
cat "$tmp"/adopt{1,2,3}entry.card > "$tmp/adopters"
sleep 3  # the adopters publish their mix descriptors too
run hidden "$tmp/hidden.p12" "$tmp/hidden" "$tmp/adopters" $((NOMAIN|NOMDNS|NOUPNP)) "$tmp/mixentry"
echo "three helpers adopted a hidden song, and a listener downloaded it whole from one"
