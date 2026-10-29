cmake_minimum_required(VERSION 3.22.1)
project("astra_llama")

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# ARM dot-product instructions (every phone since about 2018). Without a -march flag ggml is built for plain ARMv8.0 and runs
# quantized models 2 to 3 times slower. llama_jni.cpp refuses to load on a CPU that lacks them (see nativeLoad).
set(GGML_CPU_ARM_ARCH    "armv8.2-a+dotprod" CACHE STRING "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

add_library(astra_llama SHARED llama_jni.cpp)
target_link_libraries(astra_llama llama android log)

# PTY helper for the embedded terminal (from the Termux terminal-emulator library, see THIRD_PARTY_NOTICES.md)
add_library(termux SHARED termux/termux.c)
target_compile_options(termux PRIVATE -std=c11 -Os -fno-stack-protector)
