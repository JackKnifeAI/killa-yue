#!/bin/bash
# Host tests for the Studio's native core: every test with AddressSanitizer and UBSan,
# threaded tests also with ThreadSanitizer. Run on ops: studio/native/test/run.sh
set -e
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
CXX=${CXX:-g++}
# non-PIE: ops runs a hardened kernel that loads PIE programs where the sanitizers keep their shadow memory
FLAGS="-std=c++17 -O1 -g -Wall -Wextra -Werror -Iinclude -Itest -fno-pie -no-pie"
SRC=$(ls src/*.cpp 2>/dev/null || true)
for t in test/*_test.cpp; do
    n=$(basename "$t" .cpp)
    $CXX $FLAGS -fsanitize=address,undefined -fno-sanitize-recover=all "$t" $SRC -o "$OUT/$n" -lpthread
    "$OUT/$n"
    if grep -q "THREADED" "$t"; then
        $CXX $FLAGS -fsanitize=thread "$t" $SRC -o "$OUT/$n-tsan" -lpthread
        "$OUT/$n-tsan"
    fi
done
