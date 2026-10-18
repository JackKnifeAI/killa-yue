# Building Killa YuE

There are three parts: the engine, the GPU driver, and the app. Release 1.1.0 is built on an x86-64 Linux PC with the Android NDK (r30) and SDK, as described first. Version 1.0.0 was built on the phone itself, in Termux and a proot Ubuntu; that route is kept further down.

## On a PC with the Android NDK (how 1.1.0 is built)

Tools: Android NDK r30 (`ndk;30.0.16248370`), Android SDK build-tools (`aapt2`, `d8`, `zipalign`, `apksigner`) and `platforms;android-35`, a JDK, CMake, Ninja, Meson, Python with Mako and PyYAML, `glslc`, and the Khronos Vulkan-Headers and SPIRV-Headers sources. Below, `$NDK` is the NDK folder and `$TC` is `$NDK/toolchains/llvm/prebuilt/linux-x86_64`.

### Engine

yue2.cpp at the commit given in section 1 below, with `engine/yue2-gentle.patch` applied (`git apply`). The patch adds gentle mode's pauses and thread limit, read from `YUE_AR_PAUSE_MS`, `YUE_NAR_PAUSE_MS` and `YUE_THREADS`; with them unset the engine behaves as before. On top of it and `engine/yue2-jni.patch` (below), apply `engine/yue2-resume.patch` (checkpoints: a song carries on where it stopped) and then `engine/yue2-preview.patch` (the `preview` request: an unfinished song decoded as far as it got, its checkpoint left as it was). `engine/resume_test.py` and `engine/preview_test.py` check both on a computer with a CPU build. Install SPIRV-Headers to a prefix (`cmake -S SPIRV-Headers -B b -DCMAKE_INSTALL_PREFIX=$SPV && cmake --install b`), then:

```
cmake -S . -B build-android -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DGGML_VULKAN=ON -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 \
  -DBUILD_SHARED_LIBS=OFF -DGGML_BACKEND_DL=OFF \
  -DVulkan_INCLUDE_DIR=/path/to/Vulkan-Headers/include \
  -DVulkan_LIBRARY=$TC/sysroot/usr/lib/aarch64-linux-android/28/libvulkan.so -DVulkan_GLSLC_EXECUTABLE=$(which glslc) \
  -DSPIRV-Headers_DIR=$SPV/share/cmake/SPIRV-Headers -DCMAKE_CXX_FLAGS="-isystem $SPV/include"
cmake --build build-android --target yue-server
```

ggml's Vulkan backend looks for SPIRV-Headers but does not add its include folder, hence the `-isystem`. The result needs only `libc`, `libm`, `libdl` and `libvulkan.so`.

### GPU driver

Mesa at the commit given in section 2 below, with `engine/turnip-no-fault-tolerance.patch` and then `engine/turnip-low-priority.patch` applied. The second patch lets gentle mode ask the kernel for the lowest GPU priority (`TU_KGSL_PRIORITY`), so the work that draws the screen can preempt the engine. Compile the two stubs, `engine/ahb_stub.c` and `engine/ndk_sync_wait.c` (the NDK's libsync lacks `sync_wait`, which the phone's own libsync has), with `$TC/bin/clang --target=aarch64-linux-android28 -fPIC -O2 -c`. Copy `engine/android-ndk.ini.example` to `android-ndk.ini` and set its paths, then:

```
meson setup build-ndk --cross-file android-ndk.ini \
  -Dbuildtype=release -Dplatforms= -Dgallium-drivers= -Dvulkan-drivers=freedreno \
  -Dfreedreno-kmds=kgsl -Dglx=disabled -Degl=disabled -Dgbm=disabled -Dopengl=false \
  -Dgles1=disabled -Dgles2=disabled -Dllvm=disabled -Dvideo-codecs= -Dtools= \
  -Dbuild-tests=false -Dandroid-libbacktrace=disabled -Dzstd=disabled -Dxmlconfig=disabled \
  -Dc_args="-I$MESA/include/android_stub" -Dcpp_args="-I$MESA/include/android_stub" \
  -Dc_link_args="-llog -lsync /path/to/ahb_stub.o /path/to/ndk_sync_wait.o" \
  -Dcpp_link_args="-llog -lsync /path/to/ahb_stub.o /path/to/ndk_sync_wait.o"
ninja -C build-ndk
```

The output, `build-ndk/src/freedreno/vulkan/libvulkan_freedreno.so`, is the app's `libturnip.so`. It needs `libc++_shared.so`; use the NDK's (`$TC/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so`) as `LIBCXX`. The Vulkan loader and the other files in `DRIVER_DIR` are as in section 2.

### Engine as a JNI library (the engine's own app process)

With `engine/yue2-jni.patch` applied too, configure as above plus `-DYUE_ENGINE_JNI=$PWD/../killa-yue/engine/jni/yue_jni.cpp` and build the `yue-engine` target, giving `libyueengine.so` (the server with its `main` renamed, called from `EngineHost` through JNI). Then give our Vulkan loader its own name, so an app process never confuses it with the system's `libvulkan.so`:

```
cp libvkloader.so libkvulkan.so && patchelf --set-soname libkvulkan.so libkvulkan.so
llvm-strip libyueengine.so && patchelf --replace-needed libvulkan.so libkvulkan.so libyueengine.so
```

Put both in one folder: `ENGINE_LIB_DIR` in `local.env`.

### Write with AI (the songwriter)

`engine/writer` builds llama.cpp's server as `libkillawriter.so`, a JNI library that `WriterHost` runs in the app's `:writer` process. Use llama.cpp (https://github.com/ggml-org/llama.cpp) at commit `a0e6dc7` or later:

```
cmake -S engine/writer -B build-writer -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared \
    -DLLAMA_DIR=/path/to/llama.cpp -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DGGML_CCACHE=OFF
cmake --build build-writer --target killawriter
```

Set `WRITER_LIB` to `build-writer/libkillawriter.so` (the build script strips it; about 13 MB). It is CPU only and needs nothing beyond `libc++_shared.so`. Models are `.gguf` files in `KillaYuE/writer`; the app offers Gemma 4 E4B abliterated (mradermacher's Q4_K_M) and checks it against its SHA-256.

### Research for the songwriter

`engine/scout` is a Rust library (`libkillascout.so`, about 3 MB) that searches the web and reads pages straight from the phone for Write with AI. It was built with nightly Rust 1.99 (2026-08-11) and the NDK's clang as the linker:

```
rustup target add aarch64-linux-android
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang
cd engine/scout && cargo build --release --lib --features android --target aarch64-linux-android
```

Set `SCOUT_LIB` to `engine/scout/target/aarch64-linux-android/release/libkillascout.so`. `cargo run --release --bin scout -- search "..."` and `-- read URL "topic"` try it on a computer.

### Lyric sync and GPU covers

- whisper.cpp (https://github.com/ggml-org/whisper.cpp): `cmake -S . -B build-android` with the NDK toolchain flags above, `-DBUILD_SHARED_LIBS=OFF -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`, target `whisper-cli`. Set `WHISPER_BIN`.
- stable-diffusion.cpp (https://github.com/leejet/stable-diffusion.cpp): NDK toolchain, `-DSD_VULKAN=ON -DGGML_OPENMP=OFF -DSD_WEBM=OFF`, the same Vulkan and SPIRV-Headers flags as the engine, target `sd-cli`. Set `SD_BIN`.

### The Studio's audio engine (coming in 1.4.0)

`studio/native` builds `libstudio.so`: the Studio's mixer, disk streaming, the Oboe audio device and the JNI bridge for `ai.jackknife.killayue.studio.NativeEngine`. It needs Oboe 1.11.0 (https://github.com/google/oboe, Apache-2.0; 1.10.0 does not compile against NDK 30's AAudio headers):

```
cmake -S studio/native -B build-studio -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared -DOBOE_DIR=/path/to/oboe-1.11.0
cmake --build build-studio --target studio
```

Set `STUDIO_LIB` to `build-studio/libstudio.so` (stripped, about 270 KB). `studio/native/test/run.sh` runs its tests on a computer: every test under AddressSanitizer and UBSan, the threaded ones under ThreadSanitizer, then the JNI bridge from Java. For the phone, `STUDIO_TEST=1` makes a test build (never a release) with a test screen that drives the real audio device; see `app/test-src/.../StudioTestActivity.java` for its `adb` commands.

### App

As in section 3, with the SDK's `d8` in place of `dalvik-exchange`: set `DX` to `app/dx-d8.sh` and `D8` to the build-tools `d8` in `local.env`, `ANDROID_JAR` to the API 35 `android.jar`, and `STRIP` to any strip that handles arm64 ELF (`llvm-strip` from the NDK, or `eu-strip`).

## On the phone (how 1.0.0 was built)

The sections below are the original on-device route. Apply `engine/yue2-gentle.patch` and `engine/turnip-low-priority.patch` as above to get gentle mode.

## 1. Engine (yue2.cpp for Android)

Source: https://github.com/ServeurpersoCom/yue2.cpp at commit `83b65f5d6bf4806557ef17fe763021ff4eead928` (ggml submodule `2eddaf94a8cdbbf1c30017d28313cd661a9da543`).

Built with Termux's clang, which targets Android (bionic):

```
cmake .. -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_COMPILER=$PREFIX/bin/clang -DCMAKE_CXX_COMPILER=$PREFIX/bin/clang++ \
  -DGGML_VULKAN=ON -DGGML_OPENMP=OFF -DBUILD_SHARED_LIBS=OFF \
  -DVulkan_INCLUDE_DIR=$PREFIX/include -DVulkan_LIBRARY=/system/lib64/libvulkan.so \
  -DVulkan_GLSLC_EXECUTABLE=/usr/bin/glslc \
  -DCMAKE_CXX_FLAGS="-isystem /path/to/spirv-include" -DCMAKE_C_FLAGS="-isystem /path/to/spirv-include"
ninja -j2 yue-server
```

`/path/to/spirv-include` is a folder containing only a `spirv` directory (the SPIRV-Headers), so the Android compiler does not pick up the host's glibc headers. `glslc` and the SPIRV headers are host tools (`apt install glslc spirv-headers`).

The result needs only `libc`, `libm`, `libdl`, `libvulkan.so` and `libc++_shared.so`.

## 2. GPU driver (Mesa Turnip for Android)

Source: https://gitlab.freedesktop.org/mesa/mesa at commit `bffd91947efc1932da99aca69fc540d6d4201b0c`, plus `engine/turnip-no-fault-tolerance.patch`.

```
meson setup build-bionic --native-file termux-native.ini \
  -Dbuildtype=release -Dplatforms= -Dgallium-drivers= -Dvulkan-drivers=freedreno \
  -Dfreedreno-kmds=kgsl -Dglx=disabled -Degl=disabled -Dgbm=disabled -Dopengl=false \
  -Dgles1=disabled -Dgles2=disabled -Dllvm=disabled -Dvideo-codecs= -Dtools= \
  -Dbuild-tests=false -Dandroid-libbacktrace=disabled \
  -Dc_args="-I$MESA/include/android_stub --target=aarch64-linux-android28" \
  -Dcpp_args="-I$MESA/include/android_stub --target=aarch64-linux-android28" \
  -Dc_link_args="--target=aarch64-linux-android28 -llog -lsync /path/to/ahb_stub.o" \
  -Dcpp_link_args="--target=aarch64-linux-android28 -llog -lsync /path/to/ahb_stub.o"
ninja -C build-bionic -j2
```

- `termux-native.ini` points `c`, `cpp`, `ar` and `strip` at Termux's clang toolchain and `pkg_config_path` at `$PREFIX/lib/pkgconfig`.
- `engine/ahb_stub.c` supplies one vendor-only symbol that Turnip references for Android hardware buffers; headless compute never calls it. Compile it with `clang --target=aarch64-linux-android28 -fPIC -O2 -c`.
- The output is `libvulkan_freedreno.so`; the app carries it as `libturnip.so`.

The Vulkan loader is the generic Khronos loader built for Android. We used Termux's `vulkan-loader-generic` package (1.4.365). `libz.so.1`, `libzstd.so.1` and `libexpat.so.1` come from Termux as well.

Put `libvkloader.so` (the loader), `libturnip.so`, `libz.so.1`, `libzstd.so.1` and `libexpat.so.1` in one folder; that is `DRIVER_DIR` below.

### What the patch does

Android's kernel GPU driver (KGSL) treats a GPU job that runs for several seconds as hung and kills it, and the app then sees `VK_ERROR_DEVICE_LOST`. Rendering a long song is one very long job. The patch adds `KGSL_CONTEXT_NO_FAULT_TOLERANCE` when Turnip creates its context, which turns that check off for the engine.

## 3. App

Needs `aapt2`, `javac`, `dalvik-exchange` (dx), `zipalign`, `apksigner` and an `android.jar` (API 35 or later; newer calls are made by reflection). On Debian or Ubuntu:

```
apt install aapt apksigner zipalign default-jdk-headless dalvik-exchange android-sdk-platform-23
```

Then:

```
cd app
cp local.env.example local.env    # set the paths and your signing key
./build.sh
```

The APK is written to `app/out/killa-yue.apk`.

## Why it is built this way

- **No Gradle, Java 8, no lambdas**: so the app builds with the tools that run on a phone.
- **Target SDK 28**: lets the app keep its files in a visible `KillaYuE` folder and run the engine as a plain child process. The cost is that it cannot go on the Play Store as is.
- **The engine as `lib*.so`**: Android only unpacks native files with that name pattern, and only from there may an app execute a binary.

## HAMMER native streaming and desktop companion
The app build requires `HAMMER_LIB`, the Android arm64 `libhammer.so`. `HAMMER_DESKTOP_LIB` adds a Linux x86-64 library to the downloadable companion; without it that bundle requires a native source build. Neither binary belongs in Git. Java uses the platform TLS implementation; native code handles master decoding, resampling, Opus and bounded caches.

With a JDK, CMake, C/C++ compiler, Python 3 and curl installed:

```sh
bash peer/fetch-opus.sh "$PWD/build-hammer-vendor"
cmake -S peer/native -B build-hammer-host -DCMAKE_BUILD_TYPE=Release \
  -DOPUS_DIR="$PWD/build-hammer-vendor/opus-1.6.1"
cmake --build build-hammer-host -j2
cmake -S peer/native -B build-hammer-android -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_shared -DOPUS_DIR="$PWD/build-hammer-vendor/opus-1.6.1"
cmake --build build-hammer-android -j2
HAMMER_DESKTOP_LIB="$PWD/build-hammer-host/libhammer.so" bash peer/build-companion.sh "$PWD/out"
bash peer/test/run.sh
```

Add the two library paths to `app/local.env`, then run the ordinary app build. The companion ZIP is rebuilt and included as an app asset. Its Java source shares the phone protocol; its native source includes the same pinned miniaudio and resampler. Opus downloads are pinned by SHA-256 in `peer/fetch-opus.sh`; miniaudio's pinned commit and hash are in `peer/native/vendor/VERSIONS.txt`.

`STUDIO_TEST=1` additionally includes `HammerTestActivity`, which runs the TLS/native integration suite in isolated fixture directories, dispatching cancellation, disconnect and host shutdown on Android's UI thread to catch TLS-close lifecycle regressions and writes `hammer-test-result.txt` to the app's external files directory. Test activities must not be shipped in a release. Desktop native portability beyond Linux x86-64 remains unvalidated.
