#!/bin/sh
# Signs a helper release. Writes update.txt beside the helper zip, to attach to the GitHub release. If the key has a
# passphrase, openssl asks for it right here, and it never lands in a file or in the shell's history.
#
#   peer/sign-update.sh KEY.pem release/killa-yue-helper.zip VERSION SEQUENCE [urgent] [retire=BASE64_KEY]
#
# SEQUENCE is the release's versionCode. "urgent" spreads a security fix over one hour, not 24.
# Make a key with: openssl genpkey -algorithm ed25519 -aes-256-cbc -out KEY.pem
#   and read its public half for peer/desktop/update-keys.txt with: peer/sign-update.sh --public KEY.pem
set -eu
public() { openssl pkey -in "$1" -pubout -outform DER | tail -c 32 | openssl base64 -A; echo; }
if [ "${1:-}" = "--public" ]; then public "$2"; exit; fi
KEY=${1:?usage: sign-update.sh KEY.pem ZIP VERSION SEQUENCE [urgent] [retire=KEY]}
ZIP=${2:?zip}; VERSION=${3:?version}; SEQUENCE=${4:?sequence}
URGENT=0; RETIRE=
shift 4
for a in "$@"; do
    case "$a" in
        urgent) URGENT=1 ;;
        retire=*) RETIRE=${a#retire=} ;;
        *) echo "Unknown option $a"; exit 1 ;;
    esac
done
OUT=$(dirname "$ZIP")/update.txt
BODY=$(mktemp); SIG=$(mktemp)
trap 'rm -f "$BODY" "$SIG"' EXIT
{
    echo "killa-update 1"
    echo "sequence=$SEQUENCE"
    echo "version=$VERSION"
    echo "zip=$(basename "$ZIP")"
    echo "zip_sha256=$( (sha256sum "$ZIP" 2>/dev/null || shasum -a 256 "$ZIP") | cut -d' ' -f1)"
    echo "zip_size=$(wc -c < "$ZIP" | tr -d ' ')"
    echo "published=$(date +%s)"
    echo "urgent=$URGENT"
    [ -z "$RETIRE" ] || echo "retire=$RETIRE"
} > "$BODY"
openssl pkeyutl -sign -rawin -inkey "$KEY" -in "$BODY" -out "$SIG"
{ cat "$BODY"; printf 'sig=%s\n' "$(openssl base64 -A -in "$SIG")"; } > "$OUT"
echo "Signed $VERSION ($SEQUENCE): $OUT"
