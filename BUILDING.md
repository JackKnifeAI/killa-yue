# Building Killa YuE

The project was built on the phone itself, inside a Termux and Ubuntu (proot) environment. Any Linux arm64 or cross-compiling setup with the same pieces should work, but only the on-device route has been exercised.

There are three parts: the engine, the GPU driver, and the app.

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

Needs `aapt2`, `javac`, `dalvik-exchange` (dx), `zipalign`, `apksigner` and an `android.jar` (API 23 or later; newer calls are made by reflection). On Debian or Ubuntu:

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
