cmake_minimum_required(VERSION 3.22.1)

project("lmmse_enhance")

set(CMAKE_CXX_STANDARD 17)

add_library(lmmse_enhance SHARED
    lmmse_enhance.cpp
    lmmse_enhance_jni.cpp
    lmmse_fusion_jni.cpp
)

target_link_libraries(lmmse_enhance
    android
    log
    jnigraphics
)

# Optimization: hot pixel loops benefit dramatically from -O3 + NEON auto-vec.
# Same rationale as raw_decoder target in lib/raw-native.
target_compile_options(lmmse_enhance PRIVATE
    $<$<CONFIG:Debug>:-O3 -DNDEBUG>
    $<$<CONFIG:Release>:-O3 -DNDEBUG>
    -fno-math-errno
    -ffp-contract=fast
)
