#!/bin/bash
# Helpers that update themselves: a real helper, a real launcher, real signatures, and releases in a local folder.
# Needs the host builds of libhammer.so (peer/test/run.sh makes it) and libkillanet.so (net/build.sh host).
set -euo pipefail
cd "$(dirname "$0")/../.."
hammer="${HAMMER_HOST_BUILD:-$PWD/build-hammer-host}"
net="${KILLANET_HOST_BUILD:-$PWD/net/target/release}"
tmp=$(mktemp -d)
pid=
cleanup() { [ -z "$pid" ] || kill "$pid" 2>/dev/null || true; pkill -f "hammer-server.jar.*$tmp" 2>/dev/null || true; rm -rf "$tmp"; }
trap cleanup EXIT INT TERM
passed=0
ok() { echo "ok $1"; passed=$((passed+1)); }
die() { echo "FAILED: $1"; tail -20 "$tmp/log" 2>/dev/null; exit 1; }

openssl genpkey -algorithm ed25519 -out "$tmp/release.pem" 2>/dev/null
openssl genpkey -algorithm ed25519 -out "$tmp/recovery.pem" 2>/dev/null
openssl genpkey -algorithm ed25519 -out "$tmp/stranger.pem" 2>/dev/null
{ echo "release $(sh peer/sign-update.sh --public "$tmp/release.pem")"; echo "recovery $(sh peer/sign-update.sh --public "$tmp/recovery.pem")"; } > "$tmp/keys"

build() {  # sequence: a helper zip that calls itself this sequence, in its own folder
    mkdir -p "$tmp/rel$1"
    HELPER_SEQUENCE=$1 HELPER_VERSION=9.0.$1 HELPER_KEYS="$tmp/keys" HAMMER_DESKTOP_LIB="$hammer/libhammer.so" \
        HAMMER_NETWORK_LIB="$net/libkillanet.so" bash peer/build-companion.sh "$tmp/rel$1" >/dev/null
    mv "$tmp/rel$1/hammer-server.zip" "$tmp/rel$1/helper.zip"
}
for s in 100 101 102 103; do build $s; done
feed="$tmp/feed"; mkdir -p "$feed"
publish() {  # sequence key [options]: makes it the release on offer
    local s=$1 k=$2; shift 2
    sh peer/sign-update.sh "$k" "$tmp/rel$s/helper.zip" 9.0.$s $s "$@" >/dev/null
    cp "$tmp/rel$s/update.txt" "$tmp/rel$s/helper.zip" "$feed/"
}

home="$tmp/home"
install() {
    rm -rf "$tmp/home"; mkdir -p "$home/versions/100" "$home/update" "$home/state"
    unzip -q "$tmp/rel100/helper.zip" -d "$home/versions/100"
    cp peer/helper-launcher.sh "$home/launcher.sh"
    echo 100 > "$home/update/current"
    printf "JAVA='%s'\nARGS='%s'\n" "$(command -v java)" "--daemon --help-network --network-port $((RANDOM % 2000 + 52000)) --data $home/state --address 127.0.0.1 --bind 127.0.0.1 --port 0 --update-from file://$feed --update-every 1 --update-spread 0 --update-healthy-after 2 --update-deadline ${DEADLINE:-20} --update-peers ${PEERS:-0}" > "$home/launcher.env"
    echo keep-me > "$home/state/marker"
}
# the part systemd plays: run the launcher again whenever the helper exits
supervise() { ( while true; do sh "$home/launcher.sh" >> "$tmp/log" 2>&1 || true; sleep 1; done ) & pid=$!; }
stop() { kill "$pid" 2>/dev/null || true; pkill -f "hammer-server.jar.*$tmp" 2>/dev/null || true; pid=; sleep 1; }
wait_for() {  # seconds, then a test
    local n=$1; shift
    for _ in $(seq 1 "$n"); do if eval "$*"; then return 0; fi; sleep 1; done
    return 1
}
holds() { sleep "${1:-6}"; [ "$(cat "$home/update/current")" = 100 ] && [ ! -f "$home/update/next" ]; }

: > "$tmp/log"; install; supervise
wait_for 30 'grep -q "listening on" "$tmp/log"' || die "the helper did not start"

publish 101 "$tmp/stranger.pem"
holds 5 || die "took a release signed by a stranger"; ok "a stranger's signature is refused"

publish 101 "$tmp/release.pem"
sed -i 's/^zip_size=.*/zip_size=99/' "$feed/update.txt"
holds 5 || die "took a changed manifest"; ok "a manifest changed after signing is refused"

publish 101 "$tmp/release.pem"
cp "$tmp/rel102/helper.zip" "$feed/helper.zip"
holds 5 || die "took a zip that is not the signed one"; ok "a zip that is not the signed one is refused"

publish 100 "$tmp/release.pem"
holds 4 || die "reinstalled its own version"; ok "a well-signed release that is not newer is refused"

touch "$home/update/off"
publish 101 "$tmp/release.pem"
holds 4 || die "updated while off"; ok "updates that are off stay off"
grep -q "Updates are off" "$tmp/log" || die "no word that it had fallen behind"; ok "a helper with updates off says it has fallen behind"
rm "$home/update/off"

wait_for 30 '[ "$(cat "$home/update/current")" = 101 ]' || die "did not take a good update"; ok "a good update is taken"
wait_for 30 '[ ! -f "$home/update/trying" ]' || die "the new version did not prove itself"; ok "the new version proves itself"
[ "$(cat "$home/state/marker")" = keep-me ] || die "state changed"; ok "state is kept across an update"
[ "$(cat "$home/update/previous")" = 100 ] || die "previous not kept"; ok "the version before is kept to go back to"

# a release that can't even start
mkdir -p "$tmp/x"
(cd "$tmp/x"; unzip -q "$tmp/rel102/helper.zip"; echo broken > hammer-server/hammer-server.jar; rm "$tmp/rel102/helper.zip"; python3 -c "import shutil;shutil.make_archive('$tmp/rel102/helper','zip','.')")
publish 102 "$tmp/release.pem"
wait_for 60 'grep -q "^102$" "$home/update/bad" 2>/dev/null' || die "a broken release was not rolled back"
[ "$(cat "$home/update/current")" = 101 ] || die "not back on the version before"; ok "a release that can't start is rolled back"
sleep 5; [ "$(cat "$home/update/current")" = 101 ] || die "took the broken release again"; ok "a rolled back release is not taken again"
stop

# a release that runs but never finds a peer
: > "$tmp/log"; PEERS=1 DEADLINE=3 install; publish 103 "$tmp/release.pem"
supervise
wait_for 90 'grep -q "^103$" "$home/update/bad" 2>/dev/null' || die "an unhealthy release was not rolled back"
[ "$(cat "$home/update/current")" = 100 ] || die "not back on the version before"; ok "a release that can't find peers is rolled back"
stop

# the recovery key: only it may retire a key
: > "$tmp/log"; rm -f "$tmp/feed/update.txt" "$tmp/feed/helper.zip"; install; supervise
wait_for 30 'grep -q "listening on" "$tmp/log"' || die "the helper did not start"
rel=$(sh peer/sign-update.sh --public "$tmp/release.pem"); rec=$(sh peer/sign-update.sh --public "$tmp/recovery.pem")
touch "$home/update/off"
publish 100 "$tmp/release.pem" "retire=$rec"
sleep 4; [ ! -f "$home/update/retired" ] || die "the release key retired a key"; ok "the release key can't retire the recovery key"
publish 100 "$tmp/recovery.pem" "retire=$rel"
wait_for 10 'grep -q "$rel" "$home/update/retired" 2>/dev/null' || die "the recovery key did not retire the release key"; ok "the recovery key retires a stolen release key"
rm "$home/update/off"
publish 101 "$tmp/release.pem"
holds 5 || die "a retired key was still trusted"; ok "a retired key is refused"
stop

echo "$passed update checks passed"
