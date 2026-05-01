cmake_minimum_required(VERSION 3.22.1)
project("lamaphone" CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# llama.cpp build options — optimized for Android ARM64
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)         # Not available on Android NDK
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

# Guard: check that the llama.cpp submodule has been initialized.
# Run: git submodule update --init --recursive
# from the repo root if this path is empty.
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp submodule is missing!\n"
        "Please run: git submodule update --init --recursive\n"
        "from the repository root."
    )
endif()

# Add llama.cpp as subdirectory
add_subdirectory(llama.cpp)

# Our JNI bridge library
add_library(lamaphone SHARED llama_jni.cpp)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(lamaphone
    llama
    ggml
    ${log-lib}
    ${android-lib}
)

target_include_directories(lamaphone PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
)
