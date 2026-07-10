# Native on-device LLM for the keyboard: llama.cpp (pinned tag, vendored shallow clone)
# + a thin JNI completion bridge. arm64-only (the app ships a single ABI).
cmake_minimum_required(VERSION 3.22.1)
project(teclasllm CXX)

set(CMAKE_CXX_STANDARD 17)

# Trim llama.cpp to the library — no tools, tests, or network fetching.
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(teclasllm SHARED llama_jni.cpp)
target_link_libraries(teclasllm PRIVATE llama android log)
