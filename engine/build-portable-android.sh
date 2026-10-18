#!/usr/bin/env bash
# Source must already include the mobile compatibility and Android runtime patches.
set -euo pipefail
if [ "$#" -ne 5 ]; then
    echo "Usage: $0 YUE_SOURCE BUILD_DIR ANDROID_NDK VULKAN_HEADERS SPIRV_PREFIX" >&2
    exit 2
fi
yue_source=$(realpath "$1")
build_dir=$(realpath -m "$2")
android_ndk=$(realpath "$3")
vulkan_headers=$(realpath "$4")
spirv_prefix=$(realpath "$5")
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
toolchain="$android_ndk/toolchains/llvm/prebuilt/linux-x86_64"
if ! grep -q 'OUTPUT_NAME kyggml' "$yue_source/CMakeLists.txt"; then
    echo "Apply yue2-android-runtime.patch before building (see BUILDING.md)." >&2
    exit 1
fi
cmake -S "$yue_source" -B "$build_dir" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
    -DCMAKE_TOOLCHAIN_FILE="$android_ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared \
    -DBUILD_SHARED_LIBS=ON -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON \
    -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH= -DGGML_OPENMP=OFF -DGGML_CCACHE=OFF \
    -DGGML_VULKAN=ON -DCMAKE_C_FLAGS=-march=armv8-a \
    -DCMAKE_CXX_FLAGS="-march=armv8-a -isystem $spirv_prefix/include" \
    -DVulkan_LIBRARY="$toolchain/sysroot/usr/lib/aarch64-linux-android/28/libvulkan.so" \
    -DVulkan_INCLUDE_DIR="$vulkan_headers/include" \
    -DVulkan_GLSLC_EXECUTABLE="$(command -v glslc)" \
    -DSPIRV-Headers_DIR="$spirv_prefix/share/cmake/SPIRV-Headers" \
    -DYUE_ENGINE_JNI="$script_dir/jni/yue_jni.cpp" \
    -DYUE_ENGINE_OUTPUT_NAME=yueengine_portable
targets=(yue-server yue-engine ggml-vulkan)
for variant in armv8.0_1 armv8.2_1 armv8.2_2 armv8.6_1 armv9.0_1 armv9.2_1 armv9.2_2; do
    targets+=("ggml-cpu-android_$variant")
done
cmake --build "$build_dir" -j "${BUILD_JOBS:-2}" --target "${targets[@]}"

# A small correctness/loader test: no models, networking or song generation.
"$toolchain/bin/aarch64-linux-android28-clang++" -std=c++17 -O2 -march=armv8-a \
    -DGGML_MAX_NAME=128 -I"$yue_source/ggml/include" \
    "$script_dir/portable-runtime-test.cpp" -L"$build_dir" -lkyggml -lkyggml-base -ldl \
    -Wl,-z,max-page-size=16384 -o "$build_dir/portable-runtime-test"
