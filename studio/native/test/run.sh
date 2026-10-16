#!/bin/bash
# Build each native source once per sanitizer, then exercise every test and the JNI bridge.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
CXX=${CXX:-g++}
FLAGS=(-std=c++17 -O1 -g -Wall -Wextra -Werror -Iinclude -isystem vendor -Itest -fno-pie -no-pie)
SRC=(src/*.cpp)
if command -v clang++ >/dev/null; then
 for f in "${SRC[@]}"; do clang++ -std=c++17 -Wall -Wextra -Werror -Iinclude -isystem vendor -fsyntax-only "$f"; done
fi
for mode in address thread; do
 mkdir "$OUT/$mode"
 SAN=(-fsanitize=$mode)
 if [[ $mode == address ]]; then SAN=(-fsanitize=address,undefined -fno-sanitize-recover=all); fi
 for f in "${SRC[@]}"; do "$CXX" "${FLAGS[@]}" "${SAN[@]}" -c "$f" -o "$OUT/$mode/$(basename "$f" .cpp).o"; done
 for t in test/*_test.cpp; do
  if [[ $mode == thread ]] && ! grep -q THREADED "$t"; then continue; fi
  n=$(basename "$t" .cpp)
  "$CXX" "${FLAGS[@]}" "${SAN[@]}" "$t" "$OUT/$mode/"*.o -o "$OUT/$n-$mode" -lpthread
  "$OUT/$n-$mode"
 done
done
J=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
"$CXX" -std=c++17 -O1 -g -Wall -Wextra -Werror -Iinclude -isystem vendor -I"$J/include" -I"$J/include/linux" -fPIC -shared "${SRC[@]}" jni/*.cpp -o "$OUT/libstudio.so" -lpthread
APP=../../app/src/ai/jackknife/killayue/studio
javac -Xlint:-options --release 8 -d "$OUT/classes" "$APP/"*.java test/java/*.java
for t in test/java/*Test.java; do
 java --enable-native-access=ALL-UNNAMED -Djava.library.path="$OUT" -cp "$OUT/classes" "ai.jackknife.killayue.studio.$(basename "$t" .java)"
done
