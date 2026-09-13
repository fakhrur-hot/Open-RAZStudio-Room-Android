# Magic Lantern dual-ISO blend kernel (GPL-2)
cmake_minimum_required(VERSION 3.22.1)
project(dualiso_blend)

set(CMAKE_CXX_STANDARD 17)

add_library(dualiso_blend SHARED
    dualiso_blend.cpp
    dualiso_jni.cpp
    # libdualiso — modernized port of cr2hdr.c. Added piece-by-piece;
    # each .cpp here is a faithful port of one part of cr2hdr that has
    # been restructured to take a BlendContext instead of reading globals.
    libdualiso/detect.cpp
    libdualiso/pipeline.cpp
    libdualiso/match_exposures.cpp
    libdualiso/ev_luts.cpp
    libdualiso/mix.cpp
    libdualiso/interpolate.cpp
    libdualiso/blend.cpp
)

target_include_directories(dualiso_blend PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/libdualiso
)

# -O3 for the hot loops; -ffast-math is fine here because we're not doing
# any IEEE-edge-case maths that would matter (all inputs are non-negative
# 14-bit raw values, all operations are stable in the working range).
target_compile_options(dualiso_blend PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
)

find_library(log-lib log)
target_link_libraries(dualiso_blend PRIVATE ${log-lib})
