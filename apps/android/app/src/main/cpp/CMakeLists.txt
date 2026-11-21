cmake_minimum_required(VERSION 3.22)
project(murmur_whisper LANGUAGES C CXX)

# gradle builds debug variants with -O0, which makes ggml many times slower,
# and sideloaded debug APKs are what actually runs on phones
set(CMAKE_BUILD_TYPE Release)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# without this ggml builds for the 2011 ARMv8.0 baseline and leaves out the dot
# product kernels the quantised models run on. every 64 bit core since the
# Cortex-A55 and A75 has both features, and LocalSpeechModels.cpuSupported keeps
# older phones away from the models instead of letting them crash
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    add_link_options(-Wl,-z,max-page-size=16384)
endif()
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/whisper.cpp whisper-build)

add_library(murmur_whisper SHARED whisper_jni.cpp)
target_link_libraries(murmur_whisper PRIVATE whisper parakeet)
target_include_directories(murmur_whisper PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/whisper.cpp/include)
