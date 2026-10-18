#!/bin/bash
# Builds libkillanet.so. Needs Rust (rustup target add aarch64-linux-android) and, for Android, the NDK.
#   net/build.sh android   → net/target/aarch64-linux-android/release/libkillanet.so (set NDK=/path/to/ndk)
#   net/build.sh host      → net/target/release/libkillanet.so (desktop companion and tests)
# Cargo.lock pins every crate's exact version and checksum. When net/vendor exists (unpack the vendored-source
# archive named in VENDOR.txt there), the build uses only those sources and never the internet.
set -e
cd "$(dirname "$0")"
FROM=(--locked)
if [ -d vendor ]; then
    FROM=(--offline --locked --config 'source.crates-io.replace-with="vendored-sources"' --config 'source.vendored-sources.directory="vendor"')
fi
case "${1:-android}" in
    android)
        : "${NDK:?set NDK to the Android NDK folder}"
        BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
        export CC_aarch64_linux_android="$BIN/aarch64-linux-android28-clang"
        export AR_aarch64_linux_android="$BIN/llvm-ar"
        export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$BIN/aarch64-linux-android28-clang"
        cargo build "${FROM[@]}" --release --target aarch64-linux-android
        ls -l target/aarch64-linux-android/release/libkillanet.so ;;
    host)
        cargo build "${FROM[@]}" --release
        ls -l target/release/libkillanet.so ;;
    vendor)  # maintainers: after changing Cargo.toml, make the archive and record its SHA-256 in VENDOR.txt
        rm -rf vendor
        cargo vendor --locked vendor > /dev/null
        tar -cJf killanet-vendor.tar.xz vendor
        sha256sum killanet-vendor.tar.xz ;;
esac
