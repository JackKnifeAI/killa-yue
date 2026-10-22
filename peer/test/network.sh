#!/bin/bash
# Killa YuE network nodes in separate processes on this machine, real libp2p and real TLS, three ways:
#   1. same network: found by mDNS;
#   2. worldwide lookup: no mDNS; the listener knows one entry peer and finds the host in the DHT's public host list;
#   3. behind NAT: the host has no reachable address at all and is reached through a relay peer.
#   4. moved from Wi-Fi to mobile data: the listener remembers peers that no longer answer and still finds the host.
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
for who in relay host1 listen1 host2 listen2 host3 listen3 listen4; do key $who; done
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
