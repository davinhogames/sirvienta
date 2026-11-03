cmake_minimum_required(VERSION 3.31.6)

project(sirvienta_llm LANGUAGES C CXX)

include(FetchContent)

set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/2a667032dc788c3a071d538e0b572e45a3f12731.zip
    URL_HASH SHA256=52da936921381b09c8a248aaE03f4597e4a505a3967ed09a7170b4849c79ec2e
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(sirvienta_llm SHARED local_llm.cpp)
target_compile_features(sirvienta_llm PRIVATE cxx_std_17)
target_include_directories(sirvienta_llm PRIVATE
    "${llama_cpp_SOURCE_DIR}/include"
    "${llama_cpp_SOURCE_DIR}/ggml/include"
)
target_link_libraries(sirvienta_llm PRIVATE llama android log)
