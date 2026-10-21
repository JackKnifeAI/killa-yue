#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
build="${HAMMER_HOST_BUILD:-$PWD/build-hammer-host}"
vendor="${HAMMER_VENDOR:-$PWD/build-hammer-vendor}"
bash peer/fetch-opus.sh "$vendor"
cmake -S peer/native -B "$build" -DCMAKE_BUILD_TYPE=Release -DOPUS_DIR="$vendor/opus-1.6.1"
cmake --build "$build" -j2
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/classes" "$tmp/data"
javac -Xlint:-options --release 8 -d "$tmp/classes" app/src/ai/jackknife/killayue/peer/*.java peer/desktop/*.java peer/test/HammerIntegration.java
keytool -genkeypair -alias test -keyalg EC -groupname secp256r1 -dname 'CN=HAMMER test only' -validity 2 -storetype PKCS12 \
    -keystore "$tmp/identity.p12" -storepass hammer-test-only -noprompt >/dev/null 2>&1
java -Djava.library.path="$build" -cp "$tmp/classes" ai.jackknife.killayue.peer.HammerIntegration "$tmp/identity.p12" "$tmp/data"
