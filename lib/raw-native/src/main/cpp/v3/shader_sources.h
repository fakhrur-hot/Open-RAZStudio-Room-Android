/*
 * shader_sources — the canonical GLSL for the v3 pipeline.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * These strings used to live inside gles_renderer.cpp's ANONYMOUS namespace,
 * which gave them internal linkage and made them unreachable from any other
 * translation unit. offscreen_save_renderer.cpp worked around that by
 * COPY-PASTING the shader source (see the admission in its own comment) — and
 * a duplicated shader is exactly how preview != export divergence gets
 * reintroduced, which is hard rule #1 in CLAUDE.md.
 *
 * Hoisting them here gives ONE canonical copy that both consumers share:
 *   - gles_renderer.cpp        (Android preview, GLES 3.0 on-screen + AHB)
 *   - offscreen_save_renderer  (headless FBO path, incl. desktop via ANGLE)
 *
 * This translation unit deliberately includes NO GL/EGL headers. It is pure
 * text, so it compiles identically on Android and on Windows under clang-cl,
 * which is what lets razbatch reuse the shader without dragging in the
 * AHardwareBuffer/ANativeWindow-coupled renderer.
 *
 * DO NOT copy any of these strings elsewhere. Include this header instead.
 */

#ifndef RAZ_V3_SHADER_SOURCES_H
#define RAZ_V3_SHADER_SOURCES_H

// Fullscreen-quad vertex shaders (gl_VertexID-driven; no VBO).
//   kVertSrcDisplay  — V-flipped, for the on-screen AHB path.
//   kVertSrcSnapshot — identity UV, for FBO/readback (incl. headless export).
extern const char* kVertSrcDisplay;
extern const char* kVertSrcSnapshot;
extern const char* kVertSrc;            // back-compat alias for kVertSrcDisplay

// The uber-shader: every per-pixel adjustment, one pass. Assembled from three
// adjacent raw-string sections that the compiler concatenates.
extern const char* kFragSrc;

// Bokeh separable Gaussian (run H then V).
extern const char* kBokehBlurVert;
extern const char* kBokehBlurFrag;

// Karis 6-mip bloom: 13-tap downsample, 9-tap tent upsample.
extern const char* kBloomDownVert;
extern const char* kBloomDownFrag;
extern const char* kBloomUpVert;
extern const char* kBloomUpFrag;

// Soft-diffusion → bloom composite (dedicated Gaussian plane; no extra
// main-pass sampler — GLES 16-unit cap). Run after Karis into a scratch
// FBO, then swap onto bloomTex_[0].
extern const char* kSoftDiffBloomVert;
extern const char* kSoftDiffBloomFrag;

#endif  // RAZ_V3_SHADER_SOURCES_H
