#!/bin/bash
# Builds the Java companion and includes the supplied native library. No signing keys are read.
set -euo pipefail
cd "$(dirname "$0")/.."
dest="${1:?usage: peer/build-companion.sh OUTPUT_DIRECTORY}"
mkdir -p "$dest"
dest="$(cd "$dest" && pwd)"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/classes" "$tmp/package"
javac --release 8 -d "$tmp/classes" app/src/ai/jackknife/killayue/peer/*.java peer/desktop/HammerServer.java
jar cfe "$tmp/package/hammer-server.jar" ai.jackknife.killayue.peer.HammerServer -C "$tmp/classes" .
cp peer/README.md "$tmp/package/README.md"
cp peer/run.sh peer/run.bat peer/build-native.sh "$tmp/package/"
chmod +x "$tmp/package/run.sh" "$tmp/package/build-native.sh"
mkdir -p "$tmp/package/source/peer/native/vendor" "$tmp/package/source/studio/native/include/studio" "$tmp/package/source/studio/native/src"
cp peer/native/codec.cpp peer/native/CMakeLists.txt "$tmp/package/source/peer/native/"
cp peer/native/vendor/miniaudio.h peer/native/vendor/VERSIONS.txt "$tmp/package/source/peer/native/vendor/"
cp studio/native/include/studio/resample.h "$tmp/package/source/studio/native/include/studio/"
cp studio/native/src/resample.cpp "$tmp/package/source/studio/native/src/"
cp peer/fetch-opus.sh "$tmp/package/source/peer/"
cp LICENSE "$tmp/package/LICENSE"
cp peer/native/vendor/OPUS-LICENSE.txt "$tmp/package/OPUS-LICENSE.txt"
if [ -n "${HAMMER_DESKTOP_LIB:-}" ]; then
    mkdir -p "$tmp/package/native/linux-x86_64"
    cp "$HAMMER_DESKTOP_LIB" "$tmp/package/native/linux-x86_64/libhammer.so"
fi
python3 - "$tmp/package" "$dest/hammer-server.zip" <<'PY'
from pathlib import Path
import sys, zipfile
root=Path(sys.argv[1])
with zipfile.ZipFile(sys.argv[2], 'w', zipfile.ZIP_DEFLATED) as z:
    for p in sorted(root.rglob('*')):
        if p.is_file(): z.write(p, 'hammer-server/'+str(p.relative_to(root)))
PY
echo "Built $dest/hammer-server.zip"
