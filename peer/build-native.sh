#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
bash source/peer/fetch-opus.sh "$PWD/vendor"
cmake -S source/peer/native -B build-native -DCMAKE_BUILD_TYPE=Release -DOPUS_DIR="$PWD/vendor/opus-1.6.1"
cmake --build build-native --config Release -j2
case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) platform=linux-x86_64; library=libhammer.so ;;
    Linux-aarch64) platform=linux-aarch64; library=libhammer.so ;;
    Darwin-arm64) platform=macos-aarch64; library=libhammer.dylib ;;
    Darwin-x86_64) platform=macos-x86_64; library=libhammer.dylib ;;
    *) echo 'Copy the built library into the appropriate native/ folder for your system.'; exit 1 ;;
esac
mkdir -p "native/$platform"
cp "build-native/$library" "native/$platform/"
