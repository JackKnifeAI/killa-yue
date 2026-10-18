#!/bin/bash
set -euo pipefail
dest="${1:?usage: fetch-opus.sh VENDOR_DIRECTORY}"
mkdir -p "$dest"
archive="$dest/opus-1.6.1.tar.gz"
if [ ! -f "$archive" ]; then
    curl --fail --location --silent --show-error https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz -o "$archive.part"
    mv "$archive.part" "$archive"
fi
python3 - "$archive" <<'PY'
from pathlib import Path
import hashlib, sys
assert hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() == '6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1', 'Opus checksum mismatch'
PY
if [ ! -d "$dest/opus-1.6.1" ]; then tar xzf "$archive" -C "$dest"; fi
