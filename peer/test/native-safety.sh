#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
build="${HAMMER_HOST_BUILD:-$PWD/build-hammer-host}"
vendor="${HAMMER_VENDOR:-$PWD/build-hammer-vendor}"
jdk="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
g++ -std=c++17 -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-pie -no-pie \
  -I"$jdk/include" -I"$jdk/include/linux" -Ipeer/native/vendor \
  -I"$vendor/opus-1.6.1/include" -Istudio/native/include \
  peer/test/native_safety.cpp studio/native/src/resample.cpp "$build/opus/libopus.a" \
  -lpthread -ldl -o "$tmp/native-safety"
ASAN_OPTIONS=detect_leaks=1 "$tmp/native-safety" "$tmp"
