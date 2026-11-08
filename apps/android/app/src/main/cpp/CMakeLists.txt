cmake_minimum_required(VERSION 3.22)
project(murmur_whisper LANGUAGES C CXX)

# gradle builds debug variants with -O0, which makes ggml many times slower,
# and sideloaded debug APKs are what actually runs on phones
set(CMAKE_BUILD_TYPE Release)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    add_link_options(-Wl,-z,max-page-size=16384)
endif()
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/whisper.cpp whisper-build)

add_library(murmur_whisper SHARED whisper_jni.cpp)
target_link_libraries(murmur_whisper PRIVATE whisper parakeet)
target_include_directories(murmur_whisper PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/whisper.cpp/include)
