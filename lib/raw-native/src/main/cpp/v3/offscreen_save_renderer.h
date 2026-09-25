/*
 * StudioRoom — RAW Pipeline v3 — Headless GL save renderer.
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Phase 3 of the editor/save unification effort: a dedicated GL renderer
 * that runs in a fresh pbuffer EGL context, separate from the live
 * GlesRenderer driving the SurfaceView. Goal is bit-parity (modulo
 * grain/dither RNG seeds) between editor preview and saved file by
 * sharing fragment shader source with the live renderer.
 *
 * Built incrementally in checkpoints:
 *   Checkpoint 1 — init + clearAndReadback (this file).
 *   Checkpoint 2 — compile shader, render UV pattern.
 *   Checkpoint 3 — source-texture upload + pass-through render.
 *   Checkpoint 4 — full uniform texture set + full kFragSrc render.
 *   Checkpoint 5 — tile harness for dims > GL_MAX_TEXTURE_SIZE.
 */

#pragma once

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <cstdint>
#include <string>

namespace raw_v3 {

/**
 * Headless GL renderer for the save path. One instance per save call:
 * the JNI entry constructs, inits, renders, reads back, destroys. EGL
 * resources are scoped to one save so they never race the live editor's
 * GL thread.
 */
class OffscreenSaveRenderer {
public:
    OffscreenSaveRenderer();
    ~OffscreenSaveRenderer();

    /**
     * Create a pbuffer EGL surface + GLES 3.0 context at [width]x[height].
     * Picks an RGBA8 config — the output framebuffer the save path needs.
     * Returns false on any EGL failure; caller falls back to the CPU
     * pipeline.
     */
    bool init(int width, int height);

    /**
     * Checkpoint 1 — clear the pbuffer to opaque red and glReadPixels
     * into [outRgba]. Used to prove the EGL pipeline reaches the save
     * buffer before we add shaders or textures. [outRgba] must be
     * width*height*4 bytes.
     */
    bool clearAndReadback(uint8_t* outRgba);

    /**
     * Checkpoint 2 — compile a minimal vertex+fragment program that
     * outputs `vec4(uv, 0.5, 1.0)` and draw it to the pbuffer, then
     * glReadPixels into [outRgba]. Proves the shader compile / VAO /
     * drawArrays / readback path on the headless context before we
     * wire the full kFragSrc. Expected: smooth UV gradient (top-right
     * corner ≈ (255,255,128), bottom-left ≈ (0,0,128)).
     */
    bool renderUvPattern(uint8_t* outRgba);

    /**
     * Run the same Karis pyramid bloom the live GL editor uses, against
     * a CPU-resident RGB float buffer, and return the resulting bloom
     * plane at the same dimensions. This is the core of editor↔save
     * bloom parity — both paths now drive identical GPU shaders.
     *
     * [srcRGB]   : input W*H*3 floats, interleaved RGB, [0,1] sRGB
     *              (post-CLAHE/NR/Detail, same as the editor sees).
     * [srcW/srcH]: source dimensions.
     * [thresholdLuma]: matches GlesRenderer::runKarisBloomPass — luma
     *              below which pixels contribute zero. 0.65 = current
     *              hardcoded value the editor uses.
     * [tentRadiusPx]: upsample radius. Matches
     *              `1.0 + bloomRadius * 0.22` the editor computes, then
     *              stretched to a soft vertical oval via bloomShape.
     * [outRGB]   : caller-allocated W*H*3 floats; written with the bloom
     *              result at source dimensions (post-upscale from mip 0).
     * [bloomShape]: slot [206] anamorphic ratio (1 = portrait-tall oval).
     *
     * Returns false on any GL failure; caller falls back to the legacy
     * Gaussian blur source for applyOrtonP.
     *
     * One-shot: init() must have been called with width/height that
     * fit the source. Single-call lifecycle — internally compiles
     * shaders + creates FBOs + tears them down on each invocation,
     * so concurrent saves don't share GL state.
     */
    bool computeKarisBloom(
        const float* srcRGB,
        int srcW, int srcH,
        float thresholdLuma,
        float tentRadiusPx,
        float* outRGB,
        float mistTightness = 0.55f,
        float bloomShape = 1.f,
        float highlightStart = 0.78f,
        float highlightEnd = 0.98f);

    /**
     * Checkpoint 3 — upload a Stage A band as the uber-shader's source texture.
     *
     * WHY THIS IS NEW CODE RATHER THAN A REUSED PATH
     * ----------------------------------------------
     * The live preview only ever obtains its source texture through
     * GlesRenderer::importAhbAsTexture (a zero-copy AHardwareBuffer import).
     * There is NO glTexImage2D path for the main input anywhere in the
     * renderer, and AHardwareBuffer does not exist off-Android — so a headless
     * export has to upload the pixels itself.
     *
     * [rgbaHalf] : w*h*4 IEEE binary16 samples, interleaved RGBA. This is
     *              exactly the layout Stage A writes into its FP16 cache
     *              (SamplesPerPixel=4, SampleFormat=3, BitsPerSample=16), so a
     *              band can be handed over with no conversion.
     *
     * DOMAIN NOTE: Stage A's FP16 cache holds GAMMA-ENCODED sRGB in [0,1] (see
     * the comment block in stage_a.cpp explaining why gamm[] is set to the sRGB
     * transfer rather than 1.0). The uber-shader is written to sample exactly
     * that, so uploading raw Stage A samples feeds it the domain it expects —
     * no linearisation here, or the grade would be applied in the wrong space.
     *
     * Sampler state deliberately mirrors importAhbAsTexture (GL_LINEAR min/mag,
     * GL_CLAMP_TO_EDGE both axes). The shader does neighbourhood taps via
     * textureSize(), so a different filter or wrap mode would change pixels at
     * frame edges relative to the preview.
     *
     * @return the texture name, or 0 on failure (dimensions over
     *         GL_MAX_TEXTURE_SIZE, no current context, or a GL error).
     *         Caller owns it — release with releaseTexture().
     */
    unsigned int uploadSourceFp16(const uint16_t* rgbaHalf, int w, int h);

    /** Delete a texture returned by uploadSourceFp16. No-op for 0. */
    void releaseTexture(unsigned int tex);

    /**
     * Checkpoint 4 — full uber-shader graded render (canvas-matched export).
     *
     * Creates a tiny pbuffer EGL context (if needed), uploads Stage A FP16,
     * runs Karis bloom + optional ambiance blur pre-passes matching
     * GlesRenderer::snapshotGradedToBitmap / renderFrame, pushes the shared
     * grading uniforms, draws kFragSrc into an FBO at [srcW]×[srcH], and
     * reads back RGBA8 into [outRgba] (srcW*srcH*4).
     *
     * Preferred SAVE path for JPG/WebP: Kotlin sizes [srcW]×[srcH] to the
     * export long-side (after crop accounting), subsampled from Stage A
     * before upload — grade once at working res. Spatial ops (USM via
     * textureSize, bloom tent via longSide/1080) scale to THIS buffer's
     * long side, matching a preview of an image at that resolution.
     * Falls back to CPU Stage C when this returns false.
     *
     * [params]/paramsCount] : ShaderParams float blob (same as Stage C).
     * [lutRgb]                : optional lutSize³×3 floats in [0,1], or null.
     * [toneCurve768]          : optional 256×3 RGB8 curve, or null.
     * [subjectMask]           : optional maskW×maskH floats in [0,1], or null.
     * [brushMaskLayers]       : optional up to 4 brush alphas ([0,1] floats),
     *   each brushMaskW×brushMaskH, concatenated (layer i @ i·W·H). Null /
     *   count 0 → uBrushMaskEnabled=0 (Mask-tab bitmap layers no-op). Must
     *   match Stage C / live preview or JPG/WebP GPU export drops masks.
     */
    bool renderGradedToRgba8(
        const uint16_t* srcFp16, int srcW, int srcH,
        const float* params, int paramsCount,
        const float* lutRgb, int lutSize,
        const float lutDomainMin[3], const float lutDomainMax[3],
        const uint8_t* toneCurve768,
        const float* subjectMask, int maskW, int maskH,
        const float subjectMaskRect[4],
        const float* brushMaskLayers, int brushMaskW, int brushMaskH,
        int brushMaskCount,
        const float* attenMask, int attenW, int attenH,
        const float* depthMap, int depthW, int depthH,
        float focusDepth01,
        uint8_t* outRgba);

    /** Tear down EGL display/context/surface. Safe to call multiple times. */
    void release();

    int width() const { return width_; }
    int height() const { return height_; }

private:
    /** Tiny pbuffer just to own an ES3 context; FBO carries the real dims. */
    bool ensureContext();

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    int width_  = 0;
    int height_ = 0;
};

/**
 * Bilinear resample contiguous RGBA_F16 (Stage A layout) to a smaller
 * buffer. Used so JPG/WebP export can upload/grade at targetLongSide
 * without loading a 40 MP texture. Never upscales (caller must pass
 * dw≤sw, dh≤sh). Returns false on bad args.
 */
bool downsampleRgbaFp16Bilinear(
    const uint16_t* src, int sw, int sh,
    uint16_t* dst, int dw, int dh);

/**
 * Area-average Stage A BigTIFF → smaller Stage A BigTIFF at exact [dw]×[dh]
 * (never upscales; clamps to source). CPU Stage C fallback for JPG/WebP
 * grades the reduced file so it does not process full-res then ignore
 * targetWidth/Height.
 */
bool downsampleStageATiffToSize(
    const std::string& srcPath,
    const std::string& dstPath,
    int dw, int dh);

}  // namespace raw_v3
