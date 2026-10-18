#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -lt 4 ] || [ "$#" -gt 5 ]; then
    echo "Usage: $0 YUE_SOURCE YUE_BUILD ANDROID_NDK OUTPUT [TEST_SOURCE]" >&2
    exit 2
fi
yue_source=$1
yue_build=$2
android_ndk=$3
output=$4
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
test_source=${5:-"$script_dir/mobile-vulkan-test.cpp"}
"$android_ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang++" \
    -std=c++17 -O2 -DGGML_MAX_NAME=128 -I"$yue_source/ggml/include" -I"$yue_source/src" \
    "$test_source" \
    -Wl,--whole-archive \
    "$yue_build/ggml/src/libggml.a" \
    "$yue_build/ggml/src/libggml-base.a" \
    "$yue_build/ggml/src/libggml-cpu.a" \
    "$yue_build/ggml/src/ggml-vulkan/libggml-vulkan.a" \
    -Wl,--no-whole-archive -static-libstdc++ -lm -ldl -lvulkan -pthread \
    -o "$output"
