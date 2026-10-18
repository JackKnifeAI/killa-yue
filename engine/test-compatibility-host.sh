#!/usr/bin/env bash
# Verifies the harness and references on CPU; this is not Android GPU validation.
set -euo pipefail
if [ "$#" -ne 2 ]; then echo "Usage: $0 PREPARED_YUE_SOURCE BUILD_DIR" >&2; exit 2; fi
source_dir=$(realpath "$1")
build=$(realpath -m "$2")
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
cmake -S "$source_dir/ggml" -B "$build" -DCMAKE_BUILD_TYPE=Release -DGGML_BACKEND_DL=ON \
    -DGGML_NATIVE=OFF -DGGML_CPU_ALL_VARIANTS=OFF -DGGML_VULKAN=OFF -DGGML_OPENMP=OFF \
    -DGGML_BUILD_TESTS=OFF -DGGML_BUILD_EXAMPLES=OFF
cmake --build "$build" -j2
g++ -std=c++17 -O2 -DGGML_MAX_NAME=128 -I"$source_dir/ggml/include" \
    "$root/engine/compatibility-test.cpp" -L"$build/src" -lggml -lggml-base -ldl \
    -Wl,-rpath,"$build/src" -o "$build/compatibility-test"
"$build/compatibility-test" "$build/bin" CPU
