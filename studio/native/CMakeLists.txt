# libstudio.so: the Studio's audio engine for Android, with its Oboe device layer and JNI bridge.
# Host tests do not use this file: run test/run.sh. See BUILDING.md for the Android build.
cmake_minimum_required(VERSION 3.22)
project(studio CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT OBOE_DIR)
    message(FATAL_ERROR "Set OBOE_DIR to an Oboe 1.11.0 source tree (https://github.com/google/oboe)")
endif()
add_subdirectory(${OBOE_DIR} oboe EXCLUDE_FROM_ALL)

file(GLOB STUDIO_SRC ${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp ${CMAKE_CURRENT_SOURCE_DIR}/jni/*.cpp)
add_library(studio SHARED ${STUDIO_SRC})
target_include_directories(studio PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
# no -ffast-math: the mixer's NaN guards and the limiter's exact ceiling depend on IEEE arithmetic
target_compile_options(studio PRIVATE -Wall -Wextra -Werror -O2)
target_link_libraries(studio PRIVATE oboe log)
target_link_options(studio PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
