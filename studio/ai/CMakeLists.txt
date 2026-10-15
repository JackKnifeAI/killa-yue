# libkillastem.so: the Studio's stems job (Demucs on ExecuTorch) for the app's ":ai" process. It links the
# ExecuTorch runtime (libexecutorch_ffi.so, ExecuTorch 1.3.1 with Vulkan), which is downloaded with the model,
# not shipped in the APK. Host tests do not use this file: run test/run.sh.
cmake_minimum_required(VERSION 3.22)
project(killastem CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT EXECUTORCH_LIB OR NOT EXECUTORCH_INCLUDE)
    message(FATAL_ERROR "Set EXECUTORCH_LIB (libexecutorch_ffi.so) and EXECUTORCH_INCLUDE (the folder with executorch_ffi.h)")
endif()
add_library(executorch_ffi SHARED IMPORTED)
set_target_properties(executorch_ffi PROPERTIES IMPORTED_LOCATION ${EXECUTORCH_LIB})
set(N ${CMAKE_CURRENT_SOURCE_DIR}/../native/src)
add_library(killastem SHARED src/stems.cpp src/et_model.cpp jni/stem_jni.cpp ${N}/wav.cpp ${N}/resample.cpp ${N}/import.cpp ${N}/peaks.cpp)
target_include_directories(killastem PRIVATE include src ../native/include ${EXECUTORCH_INCLUDE})
target_compile_options(killastem PRIVATE -Wall -Wextra -O2)
target_link_libraries(killastem PRIVATE executorch_ffi log)
target_link_options(killastem PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
