# libkillawriter.so: llama.cpp's server as a JNI library for the songwriter's ":writer" app process.
# Configure with the NDK toolchain and LLAMA_DIR pointing at a llama.cpp checkout (see BUILDING.md).
# CPU only for now: on the Snapdragon 8 Elite the Adreno GPU reads prompts faster but writes no faster,
# and a GPU backend would need the vendor's libOpenCL.so on every phone.
cmake_minimum_required(VERSION 3.22)
project(killawriter CXX C)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama EXCLUDE_FROM_ALL)

add_library(killawriter SHARED ${CMAKE_CURRENT_SOURCE_DIR}/../jni/writer_jni.cpp)
target_link_libraries(killawriter PRIVATE llama-server-impl log)
target_link_options(killawriter PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
