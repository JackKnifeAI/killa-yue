#!/bin/bash
# Two network nodes in two processes on this machine: HAMMER over libp2p, found by mDNS, no addresses exchanged.
# Needs the host builds of libhammer.so (peer/test/run.sh makes it) and libkillanet.so (net/build.sh host).
set -euo pipefail
cd "$(dirname "$0")/../.."
hammer="${HAMMER_HOST_BUILD:-$PWD/build-hammer-host}"
net="${KILLANET_HOST_BUILD:-$PWD/net/target/release}"
tmp=$(mktemp -d)
host_pid=""
trap '[ -n "$host_pid" ] && kill "$host_pid" 2>/dev/null; rm -rf "$tmp"' EXIT
mkdir -p "$tmp/classes"
javac -Xlint:-options --release 8 -d "$tmp/classes" app/src/ai/jackknife/killayue/peer/*.java \
    peer/test/HammerIntegration.java peer/test/NetworkTest.java
for who in host listen; do
    keytool -genkeypair -alias test -keyalg EC -groupname secp256r1 -dname "CN=HAMMER $who test only" -validity 2 \
        -storetype PKCS12 -keystore "$tmp/$who.p12" -storepass hammer-test-only -noprompt >/dev/null 2>&1
done
run() { java -Djava.library.path="$hammer:$net" -cp "$tmp/classes" ai.jackknife.killayue.peer.NetworkTest "$@"; }
run host "$tmp/host.p12" "$tmp/host" "$tmp/card" > "$tmp/host.log" 2>&1 &
host_pid=$!
for i in $(seq 1 100); do grep -q ready "$tmp/host.log" 2>/dev/null && break; sleep 0.2; done
cat "$tmp/host.log"
run listen "$tmp/listen.p12" "$tmp/listen" "$tmp/card"
