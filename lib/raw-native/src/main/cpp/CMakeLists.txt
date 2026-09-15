cmake_minimum_required(VERSION 3.22.1)

project("raw_decoder")

set(LIBRAW_PATH "${CMAKE_CURRENT_SOURCE_DIR}/../../../libraw-native")

add_library(raw_decoder SHARED
        raw_decoder.cpp
        raw_decoder_v2.cpp
        lens_corrector.cpp
        lensfun_android.cpp
        # ── v3 sources (Plan.md @ .kiro/specs/raw-pipeline-v3-rebuild) ────────
        v3/tiff_mmap_io.cpp
        v3/stage_a.cpp
        v3/dng_gainmap.cpp
        v3/rcd_demosaic.cpp
        v3/stage_b_downsample.cpp
        v3/stage_b_serialize.cpp
        v3/gles_renderer.cpp
        # Canonical GLSL text, hoisted out of gles_renderer.cpp's anonymous
        # namespace so the headless/desktop export path can share it instead of
        # copy-pasting. Pure strings — no GL/EGL headers.
        v3/shader_sources.cpp
        # ShaderParams -> uniform mapping (133 glUniform calls), context-free so
        # the headless/ANGLE export path pushes the same uniforms in the same
        # order as the live preview. No EGL/Android deps.
        v3/grading_uniforms.cpp
        # ShaderParams float-blob decoder, hoisted from gles_renderer.cpp so the
        # desktop parity harness / headless export decode the SAME mapping.
        v3/shader_params_from_array.cpp
        v3/lut3d.cpp
        v3/adobe_xmp_parser.cpp
        v3/apply_macro.cpp
        v3/raw_v3_clahe.cpp
        v3/raw_v3_nr.cpp
        v3/raw_v3_detail.cpp
        v3/raw_v3_highlight_recovery.cpp
        v3/raw_hdr_recovery.cpp
        v3/raw_shadow_recovery.cpp
        v3/guided_filter.cpp
        v3/rayxie_defringe.cpp
        v3/lmmse_demosaic.cpp
        v3/dual_blend.cpp
        v3/stage_c_export.cpp
        v3/selective_bokeh_stage_c.cpp
        v3/offscreen_save_renderer.cpp
        v3/film_sim.cpp
        v3/v3_debug_log.cpp
        v3/v3_jni.cpp
        # ── ai-enhance: shared LMMSE+USM math (FP16 overload used by Stage A) ─
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../feature/ai-enhance/src/main/cpp/lmmse_enhance.cpp
        ${LIBRAW_PATH}/src/libraw_c_api.cpp
        ${LIBRAW_PATH}/src/libraw_datastream.cpp
        ${LIBRAW_PATH}/src/decoders/canon_600.cpp
        ${LIBRAW_PATH}/src/decoders/crx.cpp
        ${LIBRAW_PATH}/src/decoders/pana8.cpp
        ${LIBRAW_PATH}/src/decoders/decoders_dcraw.cpp
        ${LIBRAW_PATH}/src/decoders/sonycc.cpp
        ${LIBRAW_PATH}/src/decompressors/losslessjpeg.cpp
        ${LIBRAW_PATH}/src/decoders/decoders_libraw_dcrdefs.cpp
        ${LIBRAW_PATH}/src/decoders/olympus14.cpp
        ${LIBRAW_PATH}/src/decoders/decoders_libraw.cpp
        ${LIBRAW_PATH}/src/decoders/dng.cpp
        ${LIBRAW_PATH}/src/decoders/fp_dng.cpp
        ${LIBRAW_PATH}/src/decoders/fuji_compressed.cpp
        ${LIBRAW_PATH}/src/decoders/generic.cpp
        ${LIBRAW_PATH}/src/decoders/kodak_decoders.cpp
        ${LIBRAW_PATH}/src/decoders/load_mfbacks.cpp
        ${LIBRAW_PATH}/src/decoders/smal.cpp
        ${LIBRAW_PATH}/src/decoders/unpack_thumb.cpp
        ${LIBRAW_PATH}/src/decoders/unpack.cpp
        ${LIBRAW_PATH}/src/demosaic/aahd_demosaic.cpp
        ${LIBRAW_PATH}/src/demosaic/ahd_demosaic.cpp
        ${LIBRAW_PATH}/src/demosaic/amaze_demosaic_RT.cc
        ${LIBRAW_PATH}/src/demosaic/dcb_demosaic.cpp
        ${LIBRAW_PATH}/src/demosaic/dht_demosaic.cpp
        ${LIBRAW_PATH}/src/demosaic/misc_demosaic.cpp
        ${LIBRAW_PATH}/src/demosaic/xtrans_demosaic.cpp
        ${LIBRAW_PATH}/src/metadata/adobepano.cpp
        ${LIBRAW_PATH}/src/metadata/canon.cpp
        ${LIBRAW_PATH}/src/metadata/ciff.cpp
        ${LIBRAW_PATH}/src/metadata/cr3_parser.cpp
        ${LIBRAW_PATH}/src/metadata/epson.cpp
        ${LIBRAW_PATH}/src/metadata/exif_gps.cpp
        ${LIBRAW_PATH}/src/metadata/fuji.cpp
        ${LIBRAW_PATH}/src/metadata/identify_tools.cpp
        ${LIBRAW_PATH}/src/metadata/identify.cpp
        ${LIBRAW_PATH}/src/metadata/kodak.cpp
        ${LIBRAW_PATH}/src/metadata/leica.cpp
        ${LIBRAW_PATH}/src/metadata/makernotes.cpp
        ${LIBRAW_PATH}/src/metadata/mediumformat.cpp
        ${LIBRAW_PATH}/src/metadata/minolta.cpp
        ${LIBRAW_PATH}/src/metadata/misc_parsers.cpp
        ${LIBRAW_PATH}/src/metadata/nikon.cpp
        ${LIBRAW_PATH}/src/metadata/normalize_model.cpp
        ${LIBRAW_PATH}/src/metadata/olympus.cpp
        ${LIBRAW_PATH}/src/metadata/hasselblad_model.cpp
        ${LIBRAW_PATH}/src/metadata/p1.cpp
        ${LIBRAW_PATH}/src/metadata/pentax.cpp
        ${LIBRAW_PATH}/src/metadata/samsung.cpp
        ${LIBRAW_PATH}/src/metadata/sony.cpp
        ${LIBRAW_PATH}/src/metadata/tiff.cpp
        ${LIBRAW_PATH}/src/postprocessing/aspect_ratio.cpp
        ${LIBRAW_PATH}/src/postprocessing/dcraw_process.cpp
        ${LIBRAW_PATH}/src/postprocessing/mem_image.cpp
        ${LIBRAW_PATH}/src/postprocessing/postprocessing_aux.cpp
        ${LIBRAW_PATH}/src/postprocessing/postprocessing_utils_dcrdefs.cpp
        ${LIBRAW_PATH}/src/postprocessing/postprocessing_utils.cpp
        ${LIBRAW_PATH}/src/preprocessing/ext_preprocess.cpp
        ${LIBRAW_PATH}/src/preprocessing/raw2image.cpp
        ${LIBRAW_PATH}/src/preprocessing/subtract_black.cpp
        ${LIBRAW_PATH}/src/tables/cameralist.cpp
        ${LIBRAW_PATH}/src/tables/colorconst.cpp
        ${LIBRAW_PATH}/src/tables/colordata.cpp
        ${LIBRAW_PATH}/src/tables/wblists.cpp
        ${LIBRAW_PATH}/src/utils/curves.cpp
        ${LIBRAW_PATH}/src/utils/decoder_info.cpp
        ${LIBRAW_PATH}/src/utils/init_close_utils.cpp
        ${LIBRAW_PATH}/src/utils/open.cpp
        ${LIBRAW_PATH}/src/utils/phaseone_processing.cpp
        ${LIBRAW_PATH}/src/utils/read_utils.cpp
        ${LIBRAW_PATH}/src/utils/thumb_utils.cpp
        ${LIBRAW_PATH}/src/utils/utils_dcraw.cpp
        ${LIBRAW_PATH}/src/utils/utils_libraw.cpp
        ${LIBRAW_PATH}/src/write/apply_profile.cpp
        ${LIBRAW_PATH}/src/write/file_write.cpp
        ${LIBRAW_PATH}/src/write/tiff_writer.cpp
        ${LIBRAW_PATH}/src/x3f/x3f_parse_process.cpp
        ${LIBRAW_PATH}/src/x3f/x3f_utils_patched.cpp
        expat.cpp
)

# Add libxml2 dependency for the expat shim
set(LIBXML2_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../../../libxml2-native)
set(LIBXML2_WITH_ICONV OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_ICU OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_ZLIB OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_CATALOG OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_HTML OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_XPATH OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_PROGRAMS OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_TESTS OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_MODULES OFF CACHE BOOL "" FORCE)
add_subdirectory(${LIBXML2_PATH} libxml2_build)

target_include_directories(raw_decoder PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${LIBRAW_PATH}
        ${LIBRAW_PATH}/libraw
        ${LIBXML2_PATH}/include
)

target_link_libraries(raw_decoder
        android
        log
        jnigraphics
        # v3 — GLES 3.0 renderer + AHardwareBuffer↔EGLImage bridge
        EGL
        GLESv3
        nativewindow      # AHardwareBuffer_fromHardwareBuffer, ANativeWindow_fromSurface
        LibXml2
)

target_compile_options(raw_decoder PRIVATE -DLIBRAW_NOTHREADS -w)

# ── Optimization flags ────────────────────────────────────────────────────────
# Android NDK Gradle plugin defaults debug builds to -O0, which produces ~10-30×
# slower pixel loops than -O3 on the same hardware. Verified adb 2026-05-25:
# parallelized Rayxie CA on a 5496×3669 image took >55s at -O0 and tripped the
# 60s watchdog. -O0 was the actual bottleneck, not the algorithm.
#
# We explicitly force -O3 for both Debug and Release of THIS target only — the
# raw decoder's hot loops (RCD demosaic, Rayxie CA correction, FP16 conversion,
# NR box blur) are CPU-bound and benefit dramatically from inlining, loop
# unrolling, auto-vectorization, and constant folding. LibRaw's metadata/parse
# code in the same target gets the same flags; that's fine — those paths are
# not bottlenecks and benefit equally from optimization.
#
# `-fno-math-errno` and `-ffp-contract=fast` are safe for image pipelines (no
# code in here checks errno on math.h calls, and FMA fusion is welcome).
target_compile_options(raw_decoder PRIVATE
    $<$<CONFIG:Debug>:-O3 -DNDEBUG>
    $<$<CONFIG:Release>:-O3 -DNDEBUG>
    -fno-math-errno
    -ffp-contract=fast
)

# ── Release symbol hardening (anti-reverse-engineering) ───────────────────────
# The proprietary RAW math lives in THIS .so (Stage A decode, apply_macro,
# gles_renderer, tone/color/NR formulas). In release, hide every symbol that
# isn't an explicit JNI export so a decompiler (Ghidra/IDA) sees nameless
# `sub_xxxx` functions instead of `raw_v3::applyMacroPixelImpl`, etc.:
#   • -fvisibility=hidden          → non-JNIEXPORT functions get local linkage.
#     JNI entry points use JNIEXPORT (= visibility("default")) so they stay
#     callable — nothing else is exported.
#   • -ffunction-sections/-fdata-sections + --gc-sections → drop unreferenced
#     code/data so dead branches don't leak intent.
#   • --exclude-libs,ALL           → strip the static libs' (LibRaw) symbol names
#     out of the dynamic table.
# Debug keeps full symbols for profiling/symbolication. These do NOT change
# behaviour — only what names survive in the binary.
target_compile_options(raw_decoder PRIVATE
    $<$<CONFIG:Release>:-fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections>
)
target_link_options(raw_decoder PRIVATE
    $<$<CONFIG:Release>:-Wl,--gc-sections -Wl,--exclude-libs,ALL>
)

# ── Rayxie vertical pass disabled ─────────────────────────────────────────────
# Verified adb 2026-05-25: V pass on 5496×3669 timed out the 60s watchdog while
# the H pass on the same image completed in 26 ms — same algorithm, just
# column-major instead of row-major. Tiled gather/scatter (64-col chunks into
# L2-friendly contiguous scratch) didn't fix it. Skipping V matches Darktable's
# RCD-CA which also applies horizontal only. CA in real lenses is overwhelmingly
# horizontal (sagittal dispersion); the rare residual vertical fringe is not
# worth the watchdog kill.
target_compile_definitions(raw_decoder PRIVATE RAYXIE_SKIP_VERTICAL)
