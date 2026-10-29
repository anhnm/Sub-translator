cmake_minimum_required(VERSION 3.22.1)
project(subwhisper CXX C)

include(FetchContent)

# Build whisper.cpp (and its bundled ggml) as static libraries linked into one JNI library.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Dot product + fp16 arithmetic: available on practically all phones since 2018.
    # The app checks /proc/cpuinfo before loading this library.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(subwhisper SHARED whisper_jni.cpp)
target_link_libraries(subwhisper PRIVATE whisper)
if(ANDROID)
    target_link_libraries(subwhisper PRIVATE log)
endif()
