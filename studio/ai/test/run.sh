#!/bin/bash
# Host tests for the Studio's AI jobs (stems), under AddressSanitizer and UBSan, with a stand-in model.
# The ExecuTorch model itself only builds for Android (see CMakeLists.txt). Run on ops: studio/ai/test/run.sh
set -e
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
CXX=${CXX:-g++}
FLAGS="-std=c++17 -O1 -g -Wall -Wextra -Werror -Iinclude -I../native/include -I../native/test -fno-pie -no-pie"
NATIVE="../native/src/wav.cpp ../native/src/resample.cpp ../native/src/import.cpp ../native/src/peaks.cpp"
for t in test/*_test.cpp; do
    n=$(basename "$t" .cpp)
    $CXX $FLAGS -fsanitize=address,undefined -fno-sanitize-recover=all "$t" src/stems.cpp $NATIVE -o "$OUT/$n" -lpthread
    "$OUT/$n"
done
if command -v clang++ >/dev/null; then clang++ -std=c++17 -Wall -Wextra -Werror -Iinclude -I../native/include -fsyntax-only src/stems.cpp; fi
