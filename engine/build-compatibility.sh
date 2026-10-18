#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 5 ]; then
    echo "Usage: $0 YUE_SOURCE PORTABLE_LIB_DIR ANDROID_NDK VULKAN_HEADERS OUTPUT" >&2
    exit 2
fi
source_dir=$(realpath "$1")
libs=$(realpath "$2")
ndk=$(realpath "$3")
headers=$(realpath "$4")
output=$(realpath -m "$5")
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$output"
cxx="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang++"
"$cxx" -std=c++17 -O2 -march=armv8-a -DGGML_MAX_NAME=128 \
    -I"$source_dir/ggml/include" "$root/engine/compatibility-test.cpp" \
    -L"$libs" -lkyggml -lkyggml-base -ldl -Wl,-z,max-page-size=16384 \
    -o "$output/libcompatmath.so"
"$cxx" -std=c++17 -O2 -march=armv8-a -I"$headers/include" \
    "$root/tools/android-device-probe.cpp" -ldl -Wl,-z,max-page-size=16384 \
    -o "$output/libcompatprobe.so"
sha256sum "$output"/libcompat*.so > "$output/SHA256SUMS"
