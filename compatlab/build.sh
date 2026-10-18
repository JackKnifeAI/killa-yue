#!/usr/bin/env bash
# Shared source, separate app sandbox. No model files or music-app permissions.
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
: "${ANDROID_JAR:?set ANDROID_JAR}"
: "${BUILD_TOOLS:?set BUILD_TOOLS}"
: "${COMPATIBILITY_DIR:?set COMPATIBILITY_DIR}"
: "${PORTABLE_ENGINE_DIR:?set PORTABLE_ENGINE_DIR}"
: "${DRIVER_DIR:?set DRIVER_DIR (the same Turnip stack as the candidate app)}"
: "${LIBCXX:?set LIBCXX}"
output=${1:-"$root/out-compatlab"}
mkdir -p "$output"
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/classes" "$stage/dex" "$stage/apk/lib/arm64-v8a"
"$BUILD_TOOLS/aapt2" link -o "$stage/base.apk" -I "$ANDROID_JAR" --manifest "$root/compatlab/AndroidManifest.xml" \
    --min-sdk-version 28 --target-sdk-version 28
javac -Xlint:-options --release 8 -cp "$ANDROID_JAR" -d "$stage/classes" "$root"/app/src/ai/jackknife/killayue/compat/*.java
"$BUILD_TOOLS/d8" --release --min-api 28 --lib "$ANDROID_JAR" --output "$stage/dex" "$stage"/classes/ai/jackknife/killayue/compat/*.class
cp "$stage/dex/classes.dex" "$stage/apk/"
cp "$COMPATIBILITY_DIR/libcompatprobe.so" "$COMPATIBILITY_DIR/libcompatmath.so" "$stage/apk/lib/arm64-v8a/"
cp "$PORTABLE_ENGINE_DIR/libkyggml.so" "$PORTABLE_ENGINE_DIR/libkyggml-base.so" "$PORTABLE_ENGINE_DIR/libkyvulkan.so" \
    "$PORTABLE_ENGINE_DIR"/libkycpu-*.so "$stage/apk/lib/arm64-v8a/"
cp "$LIBCXX" "$stage/apk/lib/arm64-v8a/libc++_shared.so"
cp "$DRIVER_DIR/libvkloader.so" "$DRIVER_DIR/libturnip.so" "$stage/apk/lib/arm64-v8a/"
cp "$DRIVER_DIR/libz.so.1" "$stage/apk/lib/arm64-v8a/libkz.so"
cp "$DRIVER_DIR/libzstd.so.1" "$stage/apk/lib/arm64-v8a/libkzstd.so"
cp "$DRIVER_DIR/libexpat.so.1" "$stage/apk/lib/arm64-v8a/libkexpat.so"
cp "$stage/base.apk" "$stage/package.apk"
python3 - "$stage/package.apk" "$stage/apk" <<'PY'
import pathlib, sys, zipfile
root = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(sys.argv[1], 'a', compression=zipfile.ZIP_STORED) as apk:
    for file in sorted(root.rglob('*')):
        if file.is_file():
            apk.write(file, file.relative_to(root).as_posix())
PY
"$BUILD_TOOLS/zipalign" -P 16 -f 4 "$stage/package.apk" "$output/compatlab-unsigned.apk"
if [ -n "${KEYSTORE:-}" ]; then
    : "${KS_ALIAS:?set KS_ALIAS}" "${KS_PASS:?set KS_PASS}"
    export KS_PASS
    "$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" --ks-pass env:KS_PASS \
        --out "$output/killa-yue-compatlab-1.6.2.apk" "$output/compatlab-unsigned.apk"
    "$BUILD_TOOLS/apksigner" verify "$output/killa-yue-compatlab-1.6.2.apk"
    sha256sum "$output/killa-yue-compatlab-1.6.2.apk" > "$output/SHA256SUMS"
fi
