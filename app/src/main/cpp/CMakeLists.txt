cmake_minimum_required(VERSION 3.22.1)
project(llama_jni)

set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)
option(
    LLAMA_JNI_ENABLE_VULKAN
    "Build ggml Vulkan backend. Requires SPIRV-Headers/SPIRV-Tools CMake packages."
    OFF
)

if(LLAMA_JNI_ENABLE_VULKAN)
    set(LLAMA_JNI_HAS_VULKAN 1)
else()
    set(LLAMA_JNI_HAS_VULKAN 0)
endif()

if(EXISTS ${LLAMA_CPP_DIR}/CMakeLists.txt)
    # ---------------------------------------------------------------------------
    # llama.cpp build options
    # ---------------------------------------------------------------------------
    set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_UI        OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL            OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
    set(MTMD_VIDEO            OFF CACHE BOOL "" FORCE)

    # Vulkan GPU backend. Disabled by default because recent llama.cpp requires
    # external SPIRV-Headers/SPIRV-Tools packages that are not shipped with the
    # Android SDK/NDK.
    set(GGML_VULKAN            ${LLAMA_JNI_ENABLE_VULKAN}  CACHE BOOL "" FORCE)

    add_subdirectory(${LLAMA_CPP_DIR}
                     ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

    set(LLAMA_JNI_HAS_LEGACY_LLAVA 0)
    if(EXISTS ${LLAMA_CPP_DIR}/examples/llava/llava.cpp AND
       EXISTS ${LLAMA_CPP_DIR}/examples/llava/clip.cpp)
        set(LLAMA_JNI_HAS_LEGACY_LLAVA 1)

        # -----------------------------------------------------------------------
        # Legacy llava - vision encoder / multimodal support
        # -----------------------------------------------------------------------
        add_library(llava STATIC
            ${LLAMA_CPP_DIR}/examples/llava/llava.cpp
            ${LLAMA_CPP_DIR}/examples/llava/clip.cpp
        )

        target_include_directories(llava PUBLIC
            ${LLAMA_CPP_DIR}/examples/llava
            ${LLAMA_CPP_DIR}/examples
            ${LLAMA_CPP_DIR}/include
            ${LLAMA_CPP_DIR}/ggml/include
        )

        target_compile_features(llava PRIVATE cxx_std_17)
        set_target_properties(llava PROPERTIES POSITION_INDEPENDENT_CODE ON)
        target_link_libraries(llava PRIVATE llama ggml log)
    endif()

    set(LLAMA_JNI_MODERN_API 0)
    if(EXISTS ${LLAMA_CPP_DIR}/tools/mtmd/mtmd.h)
        set(LLAMA_JNI_MODERN_API 1)

        find_package(Threads REQUIRED)
        file(GLOB MTMD_MODEL_SOURCES CONFIGURE_DEPENDS
            ${LLAMA_CPP_DIR}/tools/mtmd/models/*.cpp
        )
        add_library(mtmd STATIC
            ${LLAMA_CPP_DIR}/tools/mtmd/mtmd.cpp
            ${LLAMA_CPP_DIR}/tools/mtmd/mtmd-audio.cpp
            ${LLAMA_CPP_DIR}/tools/mtmd/mtmd-image.cpp
            ${LLAMA_CPP_DIR}/tools/mtmd/mtmd-helper.cpp
            ${LLAMA_CPP_DIR}/tools/mtmd/clip.cpp
            ${MTMD_MODEL_SOURCES}
        )
        target_link_libraries(mtmd PUBLIC ggml llama)
        target_link_libraries(mtmd PRIVATE Threads::Threads)
        target_include_directories(mtmd PUBLIC
            ${LLAMA_CPP_DIR}/tools/mtmd
        )
        target_include_directories(mtmd PRIVATE
            ${LLAMA_CPP_DIR}
            ${LLAMA_CPP_DIR}/vendor
        )
        target_compile_features(mtmd PRIVATE cxx_std_17)
        set_target_properties(mtmd PROPERTIES POSITION_INDEPENDENT_CODE ON)
        target_compile_options(mtmd PRIVATE
            $<$<CXX_COMPILER_ID:Clang,GNU>:-Wno-cast-qual>
            $<$<BOOL:${ANDROID}>:-Wno-missing-prototypes>
        )
    endif()

    # ---------------------------------------------------------------------------
    # Real JNI shared library
    # ---------------------------------------------------------------------------
    add_library(
        llama_jni
        SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/llama_jni.cpp
    )

    target_include_directories(llama_jni PRIVATE
        ${LLAMA_CPP_DIR}/include
        ${LLAMA_CPP_DIR}/ggml/include
        ${LLAMA_CPP_DIR}/examples
    )

    if(LLAMA_JNI_HAS_LEGACY_LLAVA)
        target_include_directories(llama_jni PRIVATE
            ${LLAMA_CPP_DIR}/examples/llava
        )
    endif()
    if(LLAMA_JNI_MODERN_API)
        target_include_directories(llama_jni PRIVATE
            ${LLAMA_CPP_DIR}/tools/mtmd
        )
    endif()

    target_compile_definitions(llama_jni PRIVATE
        LLAMA_JNI_HAS_LEGACY_LLAVA=${LLAMA_JNI_HAS_LEGACY_LLAVA}
        LLAMA_JNI_MODERN_API=${LLAMA_JNI_MODERN_API}
        LLAMA_JNI_HAS_VULKAN=${LLAMA_JNI_HAS_VULKAN}
    )

    target_link_libraries(llama_jni llama ggml log android)
    if(LLAMA_JNI_ENABLE_VULKAN)
        find_library(VULKAN_LIBRARY vulkan)
        target_link_libraries(llama_jni ${VULKAN_LIBRARY})
    endif()
    if(LLAMA_JNI_HAS_LEGACY_LLAVA)
        target_link_libraries(llama_jni llava)
    endif()
    if(LLAMA_JNI_MODERN_API)
        target_link_libraries(llama_jni mtmd)
    endif()
else()
    message(WARNING
        "llama.cpp was not found at ${LLAMA_CPP_DIR}; building a stub llama_jni. "
        "LiteRT will still build, but the llama.cpp backend will report unavailable."
    )

    add_library(
        llama_jni
        SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/llama_jni_stub.cpp
    )

    target_link_libraries(llama_jni log)
endif()

target_compile_features(llama_jni PRIVATE cxx_std_17)

target_compile_options(llama_jni PRIVATE
    $<$<CONFIG:Release>:-O3 -ffast-math>
    $<$<AND:$<CONFIG:Release>,$<STREQUAL:${CMAKE_ANDROID_ARCH_ABI},arm64-v8a>>:
        -march=armv8.2-a+dotprod+i8mm>
)
