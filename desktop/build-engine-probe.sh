#!/usr/bin/env bash
# Optional Linux native engine fixture. This is compiled local code, never a
# downloaded executable supplied by another peer. Use the pinned prepared source.
set -euo pipefail
if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then
    echo "Usage: $0 PREPARED_YUE_SOURCE BUILD_DIR NEW_BUNDLE_DIR [cpu|vulkan]" >&2
    exit 2
fi
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
source_dir=$(realpath "$1")
build=$(realpath -m "$2")
bundle=$(realpath -m "$3")
backend=${4:-cpu}
test "$(git -C "$source_dir" rev-parse HEAD)" = 83b65f5d6bf4806557ef17fe763021ff4eead928
test "$(git -C "$source_dir/ggml" rev-parse HEAD)" = 2eddaf94a8cdbbf1c30017d28313cd661a9da543
# Same preparation checks as the phone build; do not apply patches twice.
grep -q 'OUTPUT_NAME kyggml' "$source_dir/CMakeLists.txt"
case "$backend" in cpu) vulkan=OFF;; vulkan) vulkan=ON;; *) echo 'Expected cpu or vulkan' >&2; exit 2;; esac
test ! -e "$bundle" || { echo 'Use a new bundle directory' >&2; exit 2; }
cmake -S "$source_dir/ggml" -B "$build" -DCMAKE_BUILD_TYPE=Release -DGGML_BACKEND_DL=ON \
    -DGGML_NATIVE=OFF -DGGML_CPU_ALL_VARIANTS=OFF -DGGML_VULKAN="$vulkan" -DGGML_OPENMP=OFF \
    -DGGML_SSE42=OFF -DGGML_AVX=OFF -DGGML_AVX2=OFF -DGGML_BMI2=OFF -DGGML_F16C=OFF -DGGML_FMA=OFF \
    -DGGML_AVX512=OFF -DGGML_AVX_VNNI=OFF -DGGML_AVX512_VBMI=OFF -DGGML_AVX512_VNNI=OFF -DGGML_AVX512_BF16=OFF \
    -DGGML_AMX_TILE=OFF -DGGML_AMX_INT8=OFF -DGGML_AMX_BF16=OFF \
    -DGGML_BUILD_TESTS=OFF -DGGML_BUILD_EXAMPLES=OFF
cmake --build "$build" -j2
mkdir -p "$bundle/lib"
find "$build/src" "$build/bin" -name 'libggml*.so*' -type f -exec cp -L {} "$bundle/lib/" \;
find "$build/src" "$build/bin" -name 'libggml*.so*' -type l -exec cp -L {} "$bundle/lib/" \;
g++ -std=c++17 -O2 -DGGML_MAX_NAME=128 -I"$source_dir/ggml/include" \
    "$root/engine/compatibility-test.cpp" -L"$bundle/lib" -lggml -lggml-base -ldl \
    '-Wl,-rpath,$ORIGIN/lib' -o "$bundle/compatibility-test"
cp "$source_dir/LICENSE" "$bundle/YUE-LICENSE"
cp "$source_dir/ggml/LICENSE" "$bundle/GGML-LICENSE"
python3 - "$bundle" "$backend" <<'PY'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1])
manifest = {"schema": 1, "backend_build": sys.argv[2], "yue_commit": "83b65f5d6bf4806557ef17fe763021ff4eead928", "ggml_commit": "2eddaf94a8cdbbf1c30017d28313cd661a9da543", "files": {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(root.rglob('*')) if p.is_file()}}
(root/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
PY
echo "Built trusted local fixture: $bundle"
