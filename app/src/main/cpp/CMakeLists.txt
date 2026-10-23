# The on-phone LLM: llama.cpp (pinned release) built from source, plus the app's JNI layer (llama_jni.cpp).
#
# llama.cpp is fetched at configure time from GitHub at LLAMA_CPP_TAG. To build from a local checkout instead
# (offline), pass -DFETCHCONTENT_SOURCE_DIR_LLAMA_CPP=/path/to/llama.cpp.
#
# CPU backend only, built as every ARM variant llama.cpp knows (GGML_CPU_ALL_VARIANTS): at start-up
# ggml_backend_load_all_from_path picks the best one this phone's CPU supports (dotprod, i8mm, SVE...), so the
# same APK never runs an instruction the phone lacks.
cmake_minimum_required(VERSION 3.22.1)
project(kalshi_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_CPP_TAG b11829)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
if(ANDROID)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
else()
    # Host builds (the desktop test harness): one CPU backend for this machine.
    set(GGML_NATIVE ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
endif()
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG ${LLAMA_CPP_TAG}
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(kalshi_llm SHARED llama_jni.cpp)
target_link_libraries(kalshi_llm PRIVATE llama ggml)
if(ANDROID)
    target_link_libraries(kalshi_llm PRIVATE log)
endif()
