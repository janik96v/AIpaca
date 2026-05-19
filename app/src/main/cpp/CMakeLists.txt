cmake_minimum_required(VERSION 3.22.1)
project("aipaca" CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# ---------------------------------------------------------------------------
# OpenCL setup for Android
#
# The Android NDK does not ship OpenCL headers or a stub library, so we
# supply both ourselves:
#
#   Headers : fetched from KhronosGroup/OpenCL-Headers via FetchContent.
#   Stub .so: opencl-stub/arm64-v8a/libOpenCL.so — built once from
#             KhronosGroup/OpenCL-ICD-Loader for arm64-v8a with the NDK.
#             The linker resolves symbols against this stub at build time;
#             at runtime Android's on-device libOpenCL.so is used instead.
#
# A thin FindOpenCL.cmake shim is written into the build directory so that
# llama.cpp's find_package(OpenCL REQUIRED) picks it up before CMake's own
# FindOpenCL module (which requires a real library to exist on the host).
# ---------------------------------------------------------------------------
include(FetchContent)
FetchContent_Declare(
    opencl_headers
    GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git
    GIT_TAG        v2024.05.08
)
FetchContent_MakeAvailable(opencl_headers)

# Stub library path — ABI-specific subdirectory matches ANDROID_ABI.
set(OPENCL_STUB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/opencl-stub/${ANDROID_ABI}")
set(OPENCL_STUB_LIB "${OPENCL_STUB_DIR}/libOpenCL.so")

if(NOT EXISTS "${OPENCL_STUB_LIB}")
    message(FATAL_ERROR
        "OpenCL stub library not found: ${OPENCL_STUB_LIB}\n"
        "Build it once from KhronosGroup/OpenCL-ICD-Loader for ${ANDROID_ABI}."
    )
endif()

# Write a FindOpenCL shim after FetchContent so the header path is known.
file(WRITE "${CMAKE_BINARY_DIR}/FindOpenCL.cmake"
"# FindOpenCL shim for Android — stub at configure/link time; on-device lib at runtime.\n\
if(NOT TARGET OpenCL::OpenCL)\n\
    add_library(OpenCL::OpenCL SHARED IMPORTED GLOBAL)\n\
    set_target_properties(OpenCL::OpenCL PROPERTIES\n\
        IMPORTED_LOCATION           \"${OPENCL_STUB_LIB}\"\n\
        INTERFACE_INCLUDE_DIRECTORIES \"${opencl_headers_SOURCE_DIR}\"\n\
    )\n\
endif()\n\
set(OpenCL_FOUND        TRUE)\n\
set(OpenCL_INCLUDE_DIRS \"${opencl_headers_SOURCE_DIR}\")\n\
set(OpenCL_INCLUDE_DIR  \"${opencl_headers_SOURCE_DIR}\")\n\
set(OpenCL_LIBRARY      \"${OPENCL_STUB_LIB}\")\n\
set(OpenCL_LIBRARIES    \"${OPENCL_STUB_LIB}\")\n"
)

# Prepend build dir so our shim takes priority over CMake's FindOpenCL module.
list(PREPEND CMAKE_MODULE_PATH "${CMAKE_BINARY_DIR}")

# ---------------------------------------------------------------------------
# llama.cpp build options — optimized for Android ARM64
# ---------------------------------------------------------------------------
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)  # Not available on Android NDK
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL          ON  CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)

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

# Our LLM JNI bridge library
add_library(aipaca SHARED llama_jni.cpp)

find_library(log-lib     log)
find_library(android-lib android)

target_link_libraries(aipaca
    llama
    llama-common
    ggml
    ${log-lib}
    ${android-lib}
    OpenCL::OpenCL
)

target_include_directories(aipaca PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
    llama.cpp/common
)

# ---------------------------------------------------------------------------
# stable-diffusion.cpp — on-device image generation engine
#
# Reuses llama.cpp's ggml via a CMake target guard in stable-diffusion.cpp:
#   if (NOT TARGET ggml) → skips its own ggml subdirectory
# Both libraries share the same ggml build (same Adreno OpenCL backend).
# ---------------------------------------------------------------------------
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/stable-diffusion.cpp/CMakeLists.txt")
    message(WARNING
        "stable-diffusion.cpp submodule missing — image generation disabled.\n"
        "Run: git submodule update --init --recursive"
    )
else()
    set(SD_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
    set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(SD_VULKAN            OFF CACHE BOOL "" FORCE)
    set(SD_CUDA              OFF CACHE BOOL "" FORCE)
    set(SD_METAL             OFF CACHE BOOL "" FORCE)
    # SD_OPENCL=OFF: OpenCL is provided by llama.cpp's shared ggml above
    set(SD_OPENCL            OFF CACHE BOOL "" FORCE)

    add_subdirectory(stable-diffusion.cpp)

    add_library(aipaca_sd SHARED stable_diffusion_jni.cpp)

    target_link_libraries(aipaca_sd
        stable-diffusion
        ${log-lib}
        ${android-lib}
    )

    target_include_directories(aipaca_sd PRIVATE
        stable-diffusion.cpp/include
        stable-diffusion.cpp
        llama.cpp/ggml/include
    )

    # Hide all symbols except JNI entry points to avoid conflicts with
    # libaipaca.so when both are loaded in the same JVM process.
    target_compile_options(aipaca_sd PRIVATE -fvisibility=hidden)
endif()
