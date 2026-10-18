#!/bin/sh
set -eu
cd "$(dirname "$0")"
case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) platform=linux-x86_64 ;;
    Linux-aarch64) platform=linux-aarch64 ;;
    Darwin-arm64) platform=macos-aarch64 ;;
    Darwin-x86_64) platform=macos-x86_64 ;;
    *) echo 'Build the native library for this computer with build-native.sh first.'; exit 1 ;;
esac
if [ ! -f "native/$platform/libhammer.so" ] && [ ! -f "native/$platform/libhammer.dylib" ]; then
    echo 'The native audio library needs to be built on this computer. See README.md and run build-native.sh.'
    exit 1
fi
exec java -Djava.library.path="native/$platform" -jar hammer-server.jar "$@"
