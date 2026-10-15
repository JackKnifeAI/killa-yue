#!/bin/bash
# Builds and signs the Killa YuE APK without Gradle. See ../BUILDING.md.
# Paths come from local.env (not in git); copy local.env.example and edit it.
set -e
cd "$(dirname "$0")"
[ -f local.env ] && . ./local.env
: "${ANDROID_JAR:?set ANDROID_JAR to an android.jar (API 23 or later)}"
: "${ENGINE_BIN:?set ENGINE_BIN to the yue-server binary built for Android}"
: "${DRIVER_DIR:?set DRIVER_DIR to the folder with libvkloader.so, libturnip.so, libz.so.1, libzstd.so.1, libexpat.so.1}"
: "${LIBCXX:?set LIBCXX to libc++_shared.so for arm64 Android}"
: "${KEYSTORE:?set KEYSTORE, KS_ALIAS and KS_PASS for signing}"
STRIP=${STRIP:-llvm-strip}
DX=${DX:-dalvik-exchange}

rm -rf out && mkdir -p out/gen out/classes out/apk/lib/arm64-v8a
aapt2 compile --dir res -o out/res.zip
aapt2 link -o out/base.apk -I "$ANDROID_JAR" -A assets --manifest AndroidManifest.xml --java out/gen \
    --min-sdk-version 28 --target-sdk-version 28 out/res.zip
# Java 8 bytecode and no lambdas, so the classic dx tool can convert it
javac -nowarn --release 8 -cp "$ANDROID_JAR" -d out/classes $(find src out/gen -name '*.java') 2>&1 | grep -v '^warning: \[options\]' || true
[ -f out/classes/ai/jackknife/killayue/MainActivity.class ]
(cd out && $DX --dex --min-sdk-version=26 --output=apk/classes.dex classes)

# The engine is an executable; Android only unpacks files named lib*.so, so it travels under that name.
cp "$ENGINE_BIN" out/apk/lib/arm64-v8a/libyueserver.so
$STRIP out/apk/lib/arm64-v8a/libyueserver.so 2>/dev/null || true
cp "$LIBCXX" out/apk/lib/arm64-v8a/libc++_shared.so
# Our own GPU driver stack (see EngineService.prepareDriver for how the names are mapped back).
cp "$DRIVER_DIR/libvkloader.so" "$DRIVER_DIR/libturnip.so" out/apk/lib/arm64-v8a/
cp "$DRIVER_DIR/libz.so.1" out/apk/lib/arm64-v8a/libkz.so
cp "$DRIVER_DIR/libzstd.so.1" out/apk/lib/arm64-v8a/libkzstd.so
cp "$DRIVER_DIR/libexpat.so.1" out/apk/lib/arm64-v8a/libkexpat.so

# NPU cover painter: our runner plus Qualcomm's QNN runtime. The runtime is fetched from Maven
# (com.qualcomm.qti:qnn-runtime) into NPU_DIR; its licence lets it ship inside the APK but not in this repository.
# The engine as a JNI library for its own app process (EngineHost), and our Vulkan loader under its own
# soname (libkvulkan.so) so it never collides with the system's libvulkan.so in an app process.
if [ -n "$ENGINE_LIB_DIR" ]; then
    cp "$ENGINE_LIB_DIR/libyueengine.so" "$ENGINE_LIB_DIR/libkvulkan.so" out/apk/lib/arm64-v8a/
fi
# Write with AI: llama.cpp's server as a JNI library for the ":writer" process (engine/writer, see BUILDING.md).
if [ -n "$WRITER_LIB" ]; then
    cp "$WRITER_LIB" out/apk/lib/arm64-v8a/libkillawriter.so
    $STRIP out/apk/lib/arm64-v8a/libkillawriter.so 2>/dev/null || true
fi
# Lyric sync: whisper.cpp's whisper-cli built for Android.
if [ -n "$WHISPER_BIN" ]; then
    cp "$WHISPER_BIN" out/apk/lib/arm64-v8a/libwhisper.so
    $STRIP out/apk/lib/arm64-v8a/libwhisper.so 2>/dev/null || true
fi
# GPU cover painter for phones without the NPU: stable-diffusion.cpp's sd-cli built for Android (see BUILDING.md).
if [ -n "$SD_BIN" ]; then
    cp "$SD_BIN" out/apk/lib/arm64-v8a/libsdgpu.so
    $STRIP out/apk/lib/arm64-v8a/libsdgpu.so 2>/dev/null || true
fi
if [ -n "$NPU_DIR" ]; then
    cp "$NPU_DIR/sdnpu" out/apk/lib/arm64-v8a/libsdnpu.so
    cp "$NPU_DIR"/lib/libQnnHtp.so "$NPU_DIR"/lib/libQnnSystem.so "$NPU_DIR"/lib/libQnnHtpV79Stub.so "$NPU_DIR"/lib/libQnnHtpV79Skel.so out/apk/lib/arm64-v8a/
fi
cp out/base.apk out/unsigned.apk
python3 - <<'PY'
import zipfile, os
z = zipfile.ZipFile('out/unsigned.apk', 'a')
z.write('out/apk/classes.dex', 'classes.dex', zipfile.ZIP_DEFLATED)
for n in sorted(os.listdir('out/apk/lib/arm64-v8a')):
    # compressed: the manifest has extractNativeLibs="true", so Android unpacks them at install
    z.write('out/apk/lib/arm64-v8a/' + n, 'lib/arm64-v8a/' + n, zipfile.ZIP_DEFLATED)
z.close()
PY
zipalign -f -p 4 out/unsigned.apk out/aligned.apk
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" \
    --out out/killa-yue.apk out/aligned.apk
apksigner verify out/killa-yue.apk && ls -l out/killa-yue.apk
