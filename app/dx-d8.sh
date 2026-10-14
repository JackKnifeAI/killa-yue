#!/bin/bash
# Translates build.sh's classic dx call into d8 (ops has d8, not dalvik-exchange):
#   dx --dex --min-sdk-version=N --output=DIR/classes.dex CLASSDIR
set -e
min=26; out=; in=
for a in "$@"; do
  case "$a" in
    --dex) ;;
    --min-sdk-version=*) min=${a#*=} ;;
    --output=*) out=${a#*=} ;;
    *) in=$a ;;
  esac
done
: "${ANDROID_JAR:?}"; : "${D8:=d8}"
mkdir -p "$(dirname "$out")"
"$D8" --release --min-api "$min" --lib "$ANDROID_JAR" --output "$(dirname "$out")" $(find "$in" -name '*.class')
