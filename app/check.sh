#!/bin/bash
# Compile check without natives or signing: resources, all Java against the API 35 android.jar, and dex.
# Used by CI on every pull request; also handy before a commit. Needs ANDROID_JAR and BUILD_TOOLS (a build-tools folder).
set -e
cd "$(dirname "$0")"
: "${ANDROID_JAR:?set ANDROID_JAR to an API 35 android.jar}"
: "${BUILD_TOOLS:?set BUILD_TOOLS to an Android SDK build-tools folder (aapt2, d8)}"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/gen" "$T/classes" "$T/dex"
"$BUILD_TOOLS/aapt2" compile --dir res -o "$T/res.zip"
"$BUILD_TOOLS/aapt2" link -o "$T/base.apk" -I "$ANDROID_JAR" -A assets --manifest AndroidManifest.xml --java "$T/gen" \
    --min-sdk-version 28 --target-sdk-version 28 "$T/res.zip"
javac -Xlint:-options --release 8 -cp "$ANDROID_JAR" -d "$T/classes" $(find src "$T/gen" -name '*.java')
"$BUILD_TOOLS/d8" --release --min-api 26 --lib "$ANDROID_JAR" --output "$T/dex" $(find "$T/classes" -name '*.class')
ls -l "$T/dex/classes.dex"
echo "App compiles."
