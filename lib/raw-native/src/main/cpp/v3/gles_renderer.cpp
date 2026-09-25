/*
 * StudioRoom — RAW Pipeline v3 — GLES 3.0 renderer.
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Single pass-through fragment shader for M3. The full uber-shader replaces
 * the fragment program in M4–M5.
 */

#include "gles_renderer.h"
#include "lut3d.h"
#include "shader_bilateral.h"
#include "shader_blur_h.h"
#include "shader_blur_v.h"
#include "shader_sharpen.h"
#include "v3_debug_log.h"

#include <android/log.h>
#include <algorithm>
#include <cinttypes>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <unistd.h>   // gettid, dup, close — GL-thread guard + fence fd handling
#include <cstdint>
#include <string>
#include <vector>
#include "grading_uniforms.h"
#include "shader_sources.h"
#include "bloom_filmic.h"
#include "sampler_layout.h"

#define LOG_TAG "RawV3.Gles"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace raw_v3 {

// Unqualified kMaskLayers used to resolve to GlesRenderer::kMaskLayers; the
// constant now lives on ShaderParams. Alias it so member-function bodies keep
// compiling without rewriting every array bound.
constexpr int kMaskLayers = ShaderParams::kMaskLayers;

// Fixed film-grain seed (Z axis of the 3D noise). Constant so the preview and
// the Stage C export produce the identical grain pattern. Stage C uses the
// same literal value (see stage_c_export.cpp).
// kGrainSeed now lives in grading_uniforms.h — both this file's cached-location
// fast path and the shared uniform push send it, so it has one definition.

namespace {

// Function pointers for the EGL Android-extension entry points. EGL 1.4 ships
// these as extensions; we look them up at first use.
PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC fn_eglGetNativeClientBufferANDROID = nullptr;
PFNEGLCREATEIMAGEKHRPROC               fn_eglCreateImageKHR               = nullptr;
PFNEGLDESTROYIMAGEKHRPROC              fn_eglDestroyImageKHR              = nullptr;
PFNGLEGLIMAGETARGETTEXTURE2DOESPROC    fn_glEGLImageTargetTexture2DOES    = nullptr;
// EGL_ANDROID_native_fence_sync — optional; the AHB producer-fence wait is
// skipped (with a log) when these don't resolve.
PFNEGLCREATESYNCKHRPROC                fn_eglCreateSyncKHR                = nullptr;
PFNEGLWAITSYNCKHRPROC                  fn_eglWaitSyncKHR                  = nullptr;
PFNEGLDESTROYSYNCKHRPROC               fn_eglDestroySyncKHR               = nullptr;

bool resolveFenceExtensions() {
    if (fn_eglCreateSyncKHR) return true;
    fn_eglCreateSyncKHR =
        (PFNEGLCREATESYNCKHRPROC) eglGetProcAddress("eglCreateSyncKHR");
    fn_eglWaitSyncKHR =
        (PFNEGLWAITSYNCKHRPROC) eglGetProcAddress("eglWaitSyncKHR");
    fn_eglDestroySyncKHR =
        (PFNEGLDESTROYSYNCKHRPROC) eglGetProcAddress("eglDestroySyncKHR");
    return fn_eglCreateSyncKHR && fn_eglWaitSyncKHR && fn_eglDestroySyncKHR;
}

bool resolveExtensions() {
    if (fn_eglGetNativeClientBufferANDROID) return true;
    fn_eglGetNativeClientBufferANDROID =
        (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC) eglGetProcAddress("eglGetNativeClientBufferANDROID");
    fn_eglCreateImageKHR =
        (PFNEGLCREATEIMAGEKHRPROC) eglGetProcAddress("eglCreateImageKHR");
    fn_eglDestroyImageKHR =
        (PFNEGLDESTROYIMAGEKHRPROC) eglGetProcAddress("eglDestroyImageKHR");
    fn_glEGLImageTargetTexture2DOES =
        (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC) eglGetProcAddress("glEGLImageTargetTexture2DOES");
    bool ok = fn_eglGetNativeClientBufferANDROID && fn_eglCreateImageKHR &&
              fn_eglDestroyImageKHR && fn_glEGLImageTargetTexture2DOES;
    if (!ok) {
        LOGE("resolveExtensions: missing required EGL/GLES extensions "
             "(eglGetNativeClientBufferANDROID=%p eglCreateImageKHR=%p "
             "eglDestroyImageKHR=%p glEGLImageTargetTexture2DOES=%p)",
             (void*) fn_eglGetNativeClientBufferANDROID,
             (void*) fn_eglCreateImageKHR,
             (void*) fn_eglDestroyImageKHR,
             (void*) fn_glEGLImageTargetTexture2DOES);
    }
    return ok;
}

// ── GLSL sources ────────────────────────────────────────────────────────────
// The shader text lived here, inside the anonymous namespace above, which made
// it invisible to every other translation unit and forced
// offscreen_save_renderer.cpp to copy-paste it. It now lives in
// v3/shader_sources.cpp (declared in shader_sources.h, included at the top of
// this file) so the preview and the headless/desktop export paths share ONE
// canonical copy. Do not re-inline it here.


GLuint compileShader(GLenum type, const char* src) {
    GLuint sh = glCreateShader(type);
    if (!sh) return 0;
    glShaderSource(sh, 1, &src, nullptr);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        GLint logLen = 0;
        glGetShaderiv(sh, GL_INFO_LOG_LENGTH, &logLen);
        if (logLen > 0) {
            char* log = new char[logLen];
            glGetShaderInfoLog(sh, logLen, nullptr, log);
            LOGE("compileShader(type=%u) failed: %s", type, log);
            delete[] log;
        }
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

}  // anonymous namespace

// ShaderParams::fromFloatArray moved VERBATIM to shader_params_from_array.cpp
// (single source of truth — do not re-inline here).

GlesRenderer::GlesRenderer() {
    pthread_mutex_init(&ahbWriteLock_, nullptr);
}
GlesRenderer::~GlesRenderer() { release(); }

// EGL_KHR_gl_colorspace constants, defined locally so we don't depend on a
// particular eglext.h being on the include path.
#ifndef EGL_GL_COLORSPACE_KHR
#define EGL_GL_COLORSPACE_KHR 0x309D
#endif
#ifndef EGL_GL_COLORSPACE_SRGB_KHR
#define EGL_GL_COLORSPACE_SRGB_KHR 0x3089
#endif

bool GlesRenderer::initEgl(ANativeWindow* window) {
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) {
        LOGE("initEgl: eglGetDisplay failed");
        return false;
    }
    EGLint major, minor;
    if (!eglInitialize(display_, &major, &minor)) {
        LOGE("initEgl: eglInitialize failed");
        return false;
    }
    LOGI("initEgl: EGL %d.%d", major, minor);

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE,   8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE,  8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 0,
        EGL_STENCIL_SIZE, 0,
        EGL_NONE,
    };
    EGLConfig config;
    EGLint    numConfigs = 0;
    if (!eglChooseConfig(display_, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
        LOGE("initEgl: eglChooseConfig failed");
        return false;
    }

    const EGLint ctxAttribs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE };
    context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, ctxAttribs);
    if (context_ == EGL_NO_CONTEXT) {
        LOGE("initEgl: eglCreateContext failed err=0x%x", eglGetError());
        return false;
    }

    // Tag the window surface sRGB so SurfaceFlinger colour-manages this
    // ZOrderOnTop overlay (sRGB → panel gamut) instead of pushing our sRGB
    // pixels straight onto a wide-gamut/vivid panel — which over-saturated the
    // live preview vs the colour-managed saved JPG (the pixels were identical;
    // only the display path differed). GL_FRAMEBUFFER_SRGB stays disabled so the
    // stored pixels are unchanged — this only adds the colour-space TAG.
    // Requires EGL_KHR_gl_colorspace; falls back to an untagged surface if the
    // extension is absent or the tagged surface fails to create.
    surface_ = EGL_NO_SURFACE;
    {
        const char* eglExts = eglQueryString(display_, EGL_EXTENSIONS);
        if (eglExts && strstr(eglExts, "EGL_KHR_gl_colorspace")) {
            const EGLint srgbAttribs[] = {
                EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_SRGB_KHR, EGL_NONE,
            };
            surface_ = eglCreateWindowSurface(display_, config, window, srgbAttribs);
            if (surface_ == EGL_NO_SURFACE) {
                LOGE("initEgl: sRGB-colorspace surface failed err=0x%x → untagged fallback",
                     eglGetError());
            } else {
                LOGI("initEgl: window surface tagged sRGB (EGL_KHR_gl_colorspace)");
            }
        }
        if (surface_ == EGL_NO_SURFACE) {
            surface_ = eglCreateWindowSurface(display_, config, window, nullptr);
        }
    }
    if (surface_ == EGL_NO_SURFACE) {
        LOGE("initEgl: eglCreateWindowSurface failed err=0x%x", eglGetError());
        return false;
    }

    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("initEgl: eglMakeCurrent failed err=0x%x", eglGetError());
        return false;
    }

    // The sRGB-colorspace surface above makes the default framebuffer sRGB, and
    // most GLES drivers then auto-enable sRGB ENCODE-on-write. Our shader already
    // writes sRGB-encoded pixels, so that would double-encode and lift shadows/
    // mids (washed-out preview). Disable the write conversion — the surface keeps
    // its sRGB dataspace TAG (which is what makes the compositor colour-manage
    // it), we just stop the redundant encode so stored pixels are verbatim.
#ifndef GL_FRAMEBUFFER_SRGB_EXT
#define GL_FRAMEBUFFER_SRGB_EXT 0x8DB9
#endif
    {
        const char* glExts = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
        if (glExts && strstr(glExts, "GL_EXT_sRGB_write_control")) {
            glDisable(GL_FRAMEBUFFER_SRGB_EXT);
            LOGI("initEgl: GL_FRAMEBUFFER_SRGB disabled (EXT_sRGB_write_control) — no double-encode");
        } else {
            LOGE("initEgl: GL_EXT_sRGB_write_control ABSENT — sRGB surface may double-encode shadows/mids");
        }
    }

    eglQuerySurface(display_, surface_, EGL_WIDTH,  &surfaceW_);
    eglQuerySurface(display_, surface_, EGL_HEIGHT, &surfaceH_);
    LOGI("initEgl: surface=%dx%d", surfaceW_, surfaceH_);
    return true;
}

bool GlesRenderer::createProgram() {
    // Diagnostic: probe the device's fragment-uniform capacity BEFORE compile.
    // The fragment shader declares ~90+ uniforms (per-segment AE outputs, 12
    // HSL anchors, gradient/mask blocks, LUT size, etc.). Most modern mobile
    // GPUs ship 256+ vectors, but some older Mali / Adreno parts cap at 64.
    // Compile would fail with a cryptic linker error and the editor would
    // show a black screen. Log loud and bail early so a bug report is
    // actionable instead of mysterious.
    {
        GLint maxFragVecs = 0;
        glGetIntegerv(GL_MAX_FRAGMENT_UNIFORM_VECTORS, &maxFragVecs);
        constexpr GLint kRequiredVecs = 96;   // ≈ our declared count w/ headroom
        LOGI("createProgram: GL_MAX_FRAGMENT_UNIFORM_VECTORS=%d (need >=%d)",
             maxFragVecs, kRequiredVecs);
        if (maxFragVecs > 0 && maxFragVecs < kRequiredVecs) {
            LOGE("createProgram: device fragment-uniform cap (%d) below required (%d). "
                 "Shader will fail to compile. Aborting renderer init so the editor "
                 "reports a clean error instead of a silent black frame.",
                 maxFragVecs, kRequiredVecs);
            return false;
        }
    }
    // Fragment-shader capability configs, richest first. The uber shader
    // declares 18 samplers at full config; many mobile GPUs cap fragment
    // texture units at 16 (the GLES3 minimum) and the link then fails with
    // "number of fragment samplers is greater than the maximum" — the editor
    // canvas ends up blank/white (observed on a Transsion MTK device).
    // Drop optional sampler blocks (extra brush-mask layers first, then the
    // vintage-FX overlays) until the program links. Layers compiled out
    // report mask alpha 0 in the shader, so edits simply skip them.
    GLint maxTexUnits = 0;
    glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, &maxTexUnits);
    LOGI("createProgram: GL_MAX_TEXTURE_IMAGE_UNITS=%d", (int)maxTexUnits);

    auto buildProg = [&](const char* vsSrc, const char* fragPrefix, const char* tag) -> GLuint {
        std::string fsSrc;
        if (fragPrefix && *fragPrefix) {
            // GLSL ES requires #version to be the program's FIRST directive —
            // splice the capability defines in after the #version line
            // instead of prepending them.
            const char* nl = strchr(kFragSrc, '\n');
            fsSrc.assign(kFragSrc, nl + 1);
            fsSrc += fragPrefix;
            fsSrc += nl + 1;
        } else {
            fsSrc = kFragSrc;
        }
        GLuint vs = compileShader(GL_VERTEX_SHADER,   vsSrc);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, fsSrc.c_str());
        if (!vs || !fs) return 0;
        GLuint p = glCreateProgram();
        glAttachShader(p, vs);
        glAttachShader(p, fs);
        glLinkProgram(p);
        GLint ok = 0;
        glGetProgramiv(p, GL_LINK_STATUS, &ok);
        if (!ok) {
            GLint logLen = 0;
            glGetProgramiv(p, GL_INFO_LOG_LENGTH, &logLen);
            if (logLen > 0) {
                char* log = new char[logLen];
                glGetProgramInfoLog(p, logLen, nullptr, log);
                LOGE("createProgram(%s): link failed: %s", tag, log);
                delete[] log;
            }
            glDeleteProgram(p);
            glDeleteShader(vs); glDeleteShader(fs);
            return 0;
        }
        glDeleteShader(vs); glDeleteShader(fs);
        return p;
    };

    auto fragPrefixFor = [](const SamplerLayout& cfg) -> std::string {
        char prefix[160];
        snprintf(prefix, sizeof(prefix),
                 "#define RAZ_GLES_EXTRA_MASKS %d\n#define RAZ_GLES_FX_VINTAGE %d\n",
                 cfg.extraMasks, cfg.fxVintage);
        return std::string(prefix);
    };

    // Start from the richest config the advertised unit count can hold,
    // then ladder down on link failure — the link is the ground truth,
    // since drivers count optional samplers differently.
    const int startCfg = firstSamplerLayoutFor(maxTexUnits);
    int chosenCfg = -1;
    std::string chosenPrefix;
    for (int i = startCfg; i < kSamplerLayoutCount && i >= 0; ++i) {
        std::string prefix = fragPrefixFor(kSamplerLayouts[i]);
        GLuint p = buildProg(kVertSrcDisplay, prefix.c_str(), kSamplerLayouts[i].name);
        if (p) {
            program_ = p;
            chosenCfg = i;
            chosenPrefix = std::move(prefix);
            break;
        }
    }
    if (chosenCfg < 0) return false;
        const SamplerLayout& layout = kSamplerLayouts[chosenCfg];
        fxVintageTextureUnitBase_ = layout.vintageUnitBase;
        LOGI("createProgram: fragment config '%s' (units=%d) samplers="
            "uTex=0,uLutTex=1,uSubjectMask=2,uBrushMask=3,uSobelEdgeMask=4,"
             "uBrushMask1=5,uBrushMask2=%d,uBrushMask3=%d,"
             "uVintageMist=%d,uVintageFilm=%d,uBlurTex=8,"
            "uToneCurveTex=9,uBokehAttenuation=10,uBloomTex=11,"
            "uCurveMasterTex=12,uCurveRTex=13,uCurveGTex=14,uCurveBTex=15",
            layout.name, (int)maxTexUnits,
             layout.extraMasks >= 2 ? 6 : -1,
             layout.extraMasks >= 3 ? 7 : -1,
            layout.fxVintage ? layout.vintageUnitBase : -1,
            layout.fxVintage ? layout.vintageUnitBase + 1 : -1);

    programSnap_ = buildProg(kVertSrcSnapshot, chosenPrefix.c_str(), "snapshot");
    if (!programSnap_) return false;

    // Bokeh blur program (separable Gaussian, identity vert). Non-fatal if it
    // fails to link — bokeh just stays inert, the rest of the editor works.
    {
        GLuint vs = compileShader(GL_VERTEX_SHADER,   kBokehBlurVert);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, kBokehBlurFrag);
        if (vs && fs) {
            blurProg_ = glCreateProgram();
            glAttachShader(blurProg_, vs);
            glAttachShader(blurProg_, fs);
            glLinkProgram(blurProg_);
            GLint ok = 0;
            glGetProgramiv(blurProg_, GL_LINK_STATUS, &ok);
            if (!ok) {
                LOGE("createProgram(bokehBlur): link failed");
                glDeleteProgram(blurProg_);
                blurProg_ = 0;
            } else {
                uBlurSrcLoc_    = glGetUniformLocation(blurProg_, "uBlurSrc");
                uBlurDirLoc_    = glGetUniformLocation(blurProg_, "uBlurDir");
                uBlurRadiusLoc_ = glGetUniformLocation(blurProg_, "uBlurRadius");
            }
        }
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
    }

    // Soft-diff → bloom composite (non-fatal if link fails).
    {
        GLuint vs = compileShader(GL_VERTEX_SHADER,   kSoftDiffBloomVert);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, kSoftDiffBloomFrag);
        if (vs && fs) {
            softDiffBloomProg_ = glCreateProgram();
            glAttachShader(softDiffBloomProg_, vs);
            glAttachShader(softDiffBloomProg_, fs);
            glLinkProgram(softDiffBloomProg_);
            GLint ok = 0;
            glGetProgramiv(softDiffBloomProg_, GL_LINK_STATUS, &ok);
            if (!ok) {
                LOGE("createProgram(softDiffBloom): link failed");
                glDeleteProgram(softDiffBloomProg_);
                softDiffBloomProg_ = 0;
            } else {
                uSoftDiffBloomSrcLoc_ = glGetUniformLocation(softDiffBloomProg_, "uBloomSrc");
                uSoftDiffSrcLoc_      = glGetUniformLocation(softDiffBloomProg_, "uSoftDiffSrc");
                uSoftDiffOrtonLoc_    = glGetUniformLocation(softDiffBloomProg_, "uOrtonStrength");
                uSoftDiffGlowLoc_     = glGetUniformLocation(softDiffBloomProg_, "uGlowStrength");
            }
        }
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
    }

    // Karis 6-mip bloom programs. Same pattern — link failure is non-
    // fatal; the bloom path just falls through to "no bloom" if these
    // didn't compile.
    {
        GLuint vs = compileShader(GL_VERTEX_SHADER,   kBloomDownVert);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, kBloomDownFrag);
        if (vs && fs) {
            bloomDownProg_ = glCreateProgram();
            glAttachShader(bloomDownProg_, vs);
            glAttachShader(bloomDownProg_, fs);
            glLinkProgram(bloomDownProg_);
            GLint ok = 0;
            glGetProgramiv(bloomDownProg_, GL_LINK_STATUS, &ok);
            if (!ok) {
                LOGE("createProgram(bloomDown): link failed");
                glDeleteProgram(bloomDownProg_);
                bloomDownProg_ = 0;
            } else {
                uBloomDownSrcLoc_       = glGetUniformLocation(bloomDownProg_, "uBloomDownSrc");
                uBloomDownThresholdLoc_ = glGetUniformLocation(bloomDownProg_, "uBloomDownThreshold");
                uBloomHighlightStartLoc_ = glGetUniformLocation(bloomDownProg_, "uHighlightStart");
                uBloomHighlightEndLoc_ = glGetUniformLocation(bloomDownProg_, "uHighlightEnd");
            }
        }
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
    }
    {
        GLuint vs = compileShader(GL_VERTEX_SHADER,   kBloomUpVert);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, kBloomUpFrag);
        if (vs && fs) {
            bloomUpProg_ = glCreateProgram();
            glAttachShader(bloomUpProg_, vs);
            glAttachShader(bloomUpProg_, fs);
            glLinkProgram(bloomUpProg_);
            GLint ok = 0;
            glGetProgramiv(bloomUpProg_, GL_LINK_STATUS, &ok);
            if (!ok) {
                LOGE("createProgram(bloomUp): link failed");
                glDeleteProgram(bloomUpProg_);
                bloomUpProg_ = 0;
            } else {
                uBloomUpSrcLoc_    = glGetUniformLocation(bloomUpProg_, "uBloomUpSrc");
                uBloomUpRadiusLoc_ = glGetUniformLocation(bloomUpProg_, "uBloomUpRadius");
                uBloomUpWeightLoc_ = glGetUniformLocation(bloomUpProg_, "uBloomUpWeight");
            }
        }
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
    }

    // Bilateral denoise program (spatial pre-pass). Non-fatal if it fails —
    // the NR pass is skipped and the uber-shader reads the source directly.
    {
        GLuint vs = compileShader(GL_VERTEX_SHADER,   kVertSrcSnapshot);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, kBilateralFrag);
        if (vs && fs) {
            nrProg_ = glCreateProgram();
            glAttachShader(nrProg_, vs);
            glAttachShader(nrProg_, fs);
            glLinkProgram(nrProg_);
            GLint ok = 0;
            glGetProgramiv(nrProg_, GL_LINK_STATUS, &ok);
            if (!ok) {
                LOGE("createProgram(bilateral): link failed");
                glDeleteProgram(nrProg_);
                nrProg_ = 0;
            } else {
                nrLocTex_       = glGetUniformLocation(nrProg_, "uTex");
                nrLocSigma_     = glGetUniformLocation(nrProg_, "uSigma");
                nrLocKSigma_    = glGetUniformLocation(nrProg_, "uKSigma");
                nrLocThreshold_ = glGetUniformLocation(nrProg_, "uThreshold");
            }
        }
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
    }

    // 9-tap separable Gaussian blur H program (Orton small-radius alternative).
    // Non-fatal if it fails — Orton falls back to kBokehBlurFrag.
    {
        GLuint vs = compileShader(GL_VERTEX_SHADER,   kVertSrcSnapshot);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, kBlurHFrag);
        if (vs && fs) {
            gaussBlurHProg_ = glCreateProgram();
            glAttachShader(gaussBlurHProg_, vs);
            glAttachShader(gaussBlurHProg_, fs);
            glLinkProgram(gaussBlurHProg_);
            GLint ok = 0;
            glGetProgramiv(gaussBlurHProg_, GL_LINK_STATUS, &ok);
            if (!ok) {
                LOGE("createProgram(gaussBlurH): link failed");
                glDeleteProgram(gaussBlurHProg_);
                gaussBlurHProg_ = 0;
            } else {
                gaussBlurHLocTex_   = glGetUniformLocation(gaussBlurHProg_, "uTex");
                gaussBlurHLocH_     = glGetUniformLocation(gaussBlurHProg_, "h");
                gaussBlurHLocScale_ = glGetUniformLocation(gaussBlurHProg_, "blurScale");
            }
        }
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
    }

    // 9-tap separable Gaussian blur V program (Orton small-radius alternative).
    // Non-fatal if it fails — Orton falls back to kBokehBlurFrag.
    {
        GLuint vs = compileShader(GL_VERTEX_SHADER,   kVertSrcSnapshot);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, kBlurVFrag);
        if (vs && fs) {
            gaussBlurVProg_ = glCreateProgram();
            glAttachShader(gaussBlurVProg_, vs);
            glAttachShader(gaussBlurVProg_, fs);
            glLinkProgram(gaussBlurVProg_);
            GLint ok = 0;
            glGetProgramiv(gaussBlurVProg_, GL_LINK_STATUS, &ok);
            if (!ok) {
                LOGE("createProgram(gaussBlurV): link failed");
                glDeleteProgram(gaussBlurVProg_);
                gaussBlurVProg_ = 0;
            } else {
                gaussBlurVLocTex_   = glGetUniformLocation(gaussBlurVProg_, "uTex");
                gaussBlurVLocV_     = glGetUniformLocation(gaussBlurVProg_, "v");
                gaussBlurVLocScale_ = glGetUniformLocation(gaussBlurVProg_, "blurScale");
            }
        }
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
    }

    // Laplacian sharpen program (post-uber pass, Req 6, slot 379).
    // Non-fatal if it fails — sharpen pass is skipped.
    {
        GLuint vs = compileShader(GL_VERTEX_SHADER,   kVertSrcSnapshot);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, kSharpenFrag);
        if (vs && fs) {
            sharpenProg_ = glCreateProgram();
            glAttachShader(sharpenProg_, vs);
            glAttachShader(sharpenProg_, fs);
            glLinkProgram(sharpenProg_);
            GLint ok = 0;
            glGetProgramiv(sharpenProg_, GL_LINK_STATUS, &ok);
            if (!ok) {
                LOGE("createProgram(sharpen): link failed");
                glDeleteProgram(sharpenProg_);
                sharpenProg_ = 0;
            } else {
                sharpenLocTex_       = glGetUniformLocation(sharpenProg_, "uTex");
                sharpenLocSharpness_ = glGetUniformLocation(sharpenProg_, "uSharpness");
                sharpenLocTexelSize_ = glGetUniformLocation(sharpenProg_, "uTexelSize");
                sharpenLocSubjectMask_ = glGetUniformLocation(sharpenProg_, "uSubjectMask");
                sharpenLocSubjectEn_   = glGetUniformLocation(sharpenProg_, "uSubjectMaskEnabled");
                sharpenLocSubjectRect_ = glGetUniformLocation(sharpenProg_, "uSubjectMaskRect");
                sharpenLocSubjectOnly_ = glGetUniformLocation(sharpenProg_, "uSubjectOnly");
            }
        }
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
    }

    cacheUniformLocations();
    logLiveGradingUniformParity(program_);

    glGenVertexArrays(1, &vao_);  // empty VAO, draw 4 verts via glDrawArrays
    return true;
}

// Allocate (or reallocate on resize) the two ping-pong blur FBOs at 1/4 the
// source resolution. Returns false if FBO setup fails (bokeh stays inert).
bool GlesRenderer::ensureBlurTargets(int srcW, int srcH) {
    int w = srcW / 4; if (w < 16) w = 16;
    int h = srcH / 4; if (h < 16) h = 16;
    if (blurFboA_ && blurFboB_ && w == blurW_ && h == blurH_) return true;
    blurW_ = w; blurH_ = h;

    auto makeTarget = [&](GLuint& fbo, GLuint& tex) -> bool {
        if (!tex) glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (!fbo) glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    };
    bool ok = makeTarget(blurFboA_, blurTexA_) && makeTarget(blurFboB_, blurTexB_);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (!ok) LOGE("ensureBlurTargets: FBO incomplete");
    return ok;
}

// ── Bilateral denoise FBO (full-resolution RGBA8) ───────────────────────────
// Allocates (or reallocates on resize) the single NR target at the full source
// resolution. Returns false on allocation failure — caller skips the NR pass
// and the uber-shader reads the source texture directly (no crash).
bool GlesRenderer::ensureNrTargets(int srcW, int srcH) {
    if (srcW <= 0 || srcH <= 0) return false;
    if (nrFboA_ && nrTexA_ && srcW == nrW_ && srcH == nrH_) return true;

    // OOM guard: reject allocations > 32 MP (128 MB at RGBA8).
    const int64_t pixels = int64_t(srcW) * int64_t(srcH);
    if (pixels > 32 * 1024 * 1024) {
        LOGE("ensureNrTargets: source too large (%dx%d = %" PRId64 " MP), skipping NR",
             srcW, srcH, pixels / (1024 * 1024));
        return false;
    }

    nrW_ = srcW; nrH_ = srcH;

    if (!nrTexA_) glGenTextures(1, &nrTexA_);
    glBindTexture(GL_TEXTURE_2D, nrTexA_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, nrW_, nrH_, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);

    // Check for GL_OUT_OF_MEMORY after texture allocation.
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("ensureNrTargets: glTexImage2D failed (GL error 0x%04X, %dx%d), skipping NR pass",
             err, nrW_, nrH_);
        glDeleteTextures(1, &nrTexA_); nrTexA_ = 0;
        if (nrFboA_) { glDeleteFramebuffers(1, &nrFboA_); nrFboA_ = 0; }
        nrW_ = 0; nrH_ = 0;
        return false;
    }

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    if (!nrFboA_) glGenFramebuffers(1, &nrFboA_);
    glBindFramebuffer(GL_FRAMEBUFFER, nrFboA_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, nrTexA_, 0);
    bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (!ok) {
        LOGE("ensureNrTargets: FBO incomplete (%dx%d), skipping NR pass", nrW_, nrH_);
        // Clean up so the uber-shader doesn't try to use a broken texture.
        glDeleteTextures(1, &nrTexA_); nrTexA_ = 0;
        glDeleteFramebuffers(1, &nrFboA_); nrFboA_ = 0;
        nrW_ = 0; nrH_ = 0;
    }
    return ok;
}

// ── Laplacian sharpen FBO (viewport-resolution RGBA8) ───────────────────────
// Allocates (or reallocates on resize) the sharpen render target at the
// current viewport dimensions. Returns false on failure — sharpen pass is
// skipped and the uber-shader renders directly to the window surface.
bool GlesRenderer::ensureSharpenTargets(int w, int h) {
    if (w <= 0 || h <= 0) return false;
    if (sharpenFbo_ && sharpenTex_ && w == sharpenW_ && h == sharpenH_) return true;
    sharpenW_ = w; sharpenH_ = h;

    if (!sharpenTex_) glGenTextures(1, &sharpenTex_);
    glBindTexture(GL_TEXTURE_2D, sharpenTex_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, sharpenW_, sharpenH_, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);

    // Check for GL_OUT_OF_MEMORY after texture allocation.
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("ensureSharpenTargets: glTexImage2D failed (GL error 0x%04X, %dx%d), skipping sharpen pass",
             err, sharpenW_, sharpenH_);
        glDeleteTextures(1, &sharpenTex_); sharpenTex_ = 0;
        if (sharpenFbo_) { glDeleteFramebuffers(1, &sharpenFbo_); sharpenFbo_ = 0; }
        sharpenW_ = 0; sharpenH_ = 0;
        return false;
    }

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    if (!sharpenFbo_) glGenFramebuffers(1, &sharpenFbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, sharpenFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, sharpenTex_, 0);
    bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (!ok) {
        LOGE("ensureSharpenTargets: FBO incomplete (%dx%d), skipping sharpen pass", sharpenW_, sharpenH_);
        // Clean up so the sharpen post-pass doesn't try to read a broken FBO.
        glDeleteTextures(1, &sharpenTex_); sharpenTex_ = 0;
        glDeleteFramebuffers(1, &sharpenFbo_); sharpenFbo_ = 0;
        sharpenW_ = 0; sharpenH_ = 0;
    }
    return ok;
}

// Render uTex → blurTexA_ (horizontal) → blurTexB_ (vertical). Leaves the
// final blurred image in blurTexB_ for the main pass to bind on unit 8.
void GlesRenderer::runBokehBlurPass(float radiusPx) {
    if (!blurProg_ || !ensureBlurTargets(texW_, texH_)) return;
    glUseProgram(blurProg_);
    glBindVertexArray(vao_);
    glViewport(0, 0, blurW_, blurH_);

    // Pass 1 — horizontal: source = full-res uTex → blurTexA_.
    glBindFramebuffer(GL_FRAMEBUFFER, blurFboA_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture_);
    if (uBlurSrcLoc_    >= 0) glUniform1i(uBlurSrcLoc_, 0);
    if (uBlurDirLoc_    >= 0) glUniform2f(uBlurDirLoc_, 1.f, 0.f);
    if (uBlurRadiusLoc_ >= 0) glUniform1f(uBlurRadiusLoc_, radiusPx);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // Pass 2 — vertical: source = blurTexA_ → blurTexB_.
    glBindFramebuffer(GL_FRAMEBUFFER, blurFboB_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, blurTexA_);
    if (uBlurSrcLoc_    >= 0) glUniform1i(uBlurSrcLoc_, 0);
    if (uBlurDirLoc_    >= 0) glUniform2f(uBlurDirLoc_, 0.f, 1.f);
    if (uBlurRadiusLoc_ >= 0) glUniform1f(uBlurRadiusLoc_, radiusPx);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glBindVertexArray(0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

bool GlesRenderer::ensureSoftDiffTarget(int srcW, int srcH) {
    if (!ensureBlurTargets(srcW, srcH)) return false;
    int w = blurW_, h = blurH_;
    if (softDiffFbo_ && softDiffTex_ && w == softDiffW_ && h == softDiffH_) return true;
    softDiffW_ = w; softDiffH_ = h;
    if (!softDiffTex_) glGenTextures(1, &softDiffTex_);
    glBindTexture(GL_TEXTURE_2D, softDiffTex_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (!softDiffFbo_) glGenFramebuffers(1, &softDiffFbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, softDiffFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, softDiffTex_, 0);
    bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (!ok) LOGE("ensureSoftDiffTarget: FBO incomplete");
    return ok;
}

bool GlesRenderer::ensureSoftDiffBloomScratch(int w, int h) {
    if (w <= 0 || h <= 0) return false;
    if (softDiffBloomScratchFbo_ && softDiffBloomScratchTex_ &&
        w == softDiffBloomScratchW_ && h == softDiffBloomScratchH_) return true;
    softDiffBloomScratchW_ = w;
    softDiffBloomScratchH_ = h;
    if (!softDiffBloomScratchTex_) glGenTextures(1, &softDiffBloomScratchTex_);
    glBindTexture(GL_TEXTURE_2D, softDiffBloomScratchTex_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_HALF_FLOAT, nullptr);
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        // Fallback RGBA8 if 16F unsupported for this path.
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (!softDiffBloomScratchFbo_) glGenFramebuffers(1, &softDiffBloomScratchFbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, softDiffBloomScratchFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, softDiffBloomScratchTex_, 0);
    bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (!ok) LOGE("ensureSoftDiffBloomScratch: FBO incomplete");
    return ok;
}

// H into blurTexA_ (scratch), V into softDiffTex_ — leaves bokeh's blurTexB_ alone.
void GlesRenderer::runSoftDiffBlurPass(float radiusPx) {
    if (!blurProg_ || !ensureSoftDiffTarget(texW_, texH_)) return;
    glUseProgram(blurProg_);
    glBindVertexArray(vao_);
    glViewport(0, 0, softDiffW_, softDiffH_);

    glBindFramebuffer(GL_FRAMEBUFFER, blurFboA_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture_);
    if (uBlurSrcLoc_    >= 0) glUniform1i(uBlurSrcLoc_, 0);
    if (uBlurDirLoc_    >= 0) glUniform2f(uBlurDirLoc_, 1.f, 0.f);
    if (uBlurRadiusLoc_ >= 0) glUniform1f(uBlurRadiusLoc_, radiusPx);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glBindFramebuffer(GL_FRAMEBUFFER, softDiffFbo_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, blurTexA_);
    if (uBlurSrcLoc_    >= 0) glUniform1i(uBlurSrcLoc_, 0);
    if (uBlurDirLoc_    >= 0) glUniform2f(uBlurDirLoc_, 0.f, 1.f);
    if (uBlurRadiusLoc_ >= 0) glUniform1f(uBlurRadiusLoc_, radiusPx);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glBindVertexArray(0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

void GlesRenderer::runSoftDiffBloomComposite(float ortonStrength, float glowStrength) {
    if (!softDiffBloomProg_ || !softDiffTex_ || !bloomTex_[0] || !bloomFbo_[0]) return;
    const int w = bloomW_[0], h = bloomH_[0];
    if (!ensureSoftDiffBloomScratch(w, h)) return;

    glUseProgram(softDiffBloomProg_);
    glBindVertexArray(vao_);
    glViewport(0, 0, w, h);
    glDisable(GL_BLEND);
    glBindFramebuffer(GL_FRAMEBUFFER, softDiffBloomScratchFbo_);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, bloomTex_[0]);
    if (uSoftDiffBloomSrcLoc_ >= 0) glUniform1i(uSoftDiffBloomSrcLoc_, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, softDiffTex_);
    if (uSoftDiffSrcLoc_ >= 0) glUniform1i(uSoftDiffSrcLoc_, 1);
    if (uSoftDiffOrtonLoc_ >= 0) glUniform1f(uSoftDiffOrtonLoc_, ortonStrength);
    if (uSoftDiffGlowLoc_  >= 0) glUniform1f(uSoftDiffGlowLoc_,  glowStrength);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // Swap so bloomTex_[0] is the composited result (Karis FBO reattached).
    std::swap(bloomTex_[0], softDiffBloomScratchTex_);
    glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo_[0]);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, bloomTex_[0], 0);
    glBindFramebuffer(GL_FRAMEBUFFER, softDiffBloomScratchFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, softDiffBloomScratchTex_, 0);

    glBindVertexArray(0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

// ── Karis bloom pyramid (6 mips, downsample chain + upsample composite) ──
//   Allocates 6 mip targets sized 1/2, 1/4, 1/8, 1/16, 1/32, 1/64 of the
//   preview resolution. Each mip is a separate FBO + texture (not a real
//   mipmapped texture) because we additively blend up the chain and want
//   independent framebuffer attachments.
//
//   Total memory: ~5.3 MB at 1080p preview (1/2 mip dominates at ~4 MB).
//   Resize triggers when preview dimensions change.
bool GlesRenderer::ensureBloomTargets(int previewW, int previewH) {
    if (previewW <= 0 || previewH <= 0) return false;
    if (bloomBaseW_ == previewW && bloomBaseH_ == previewH && bloomFbo_[0])
        return true;
    bloomBaseW_ = previewW;
    bloomBaseH_ = previewH;

    // ── Tier 1: Karis 6-mip pyramid via GL_RGBA16F FBOs ─────────────────
    {
        bool ok = true;
        for (int i = 0; i < kBloomMipCount; ++i) {
            int w = previewW >> (i + 1);
            int h = previewH >> (i + 1);
            if (w < 4) w = 4;
            if (h < 4) h = 4;
            bloomW_[i] = w;
            bloomH_[i] = h;

            if (!bloomTex_[i]) glGenTextures(1, &bloomTex_[i]);
            glBindTexture(GL_TEXTURE_2D, bloomTex_[i]);
            // RGBA16F so we can carry HDR-like highlight values without
            // banding through the additive upsample chain.
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0,
                         GL_RGBA, GL_HALF_FLOAT, nullptr);
            GLenum texErr = glGetError();
            if (texErr != GL_NO_ERROR) {
                LOGE("ensureBloomTargets(tier1): glTexImage2D mip %d failed (0x%04X), falling to tier 2", i, texErr);
                ok = false;
                break;
            }
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

            if (!bloomFbo_[i]) glGenFramebuffers(1, &bloomFbo_[i]);
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo_[i]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                   GL_TEXTURE_2D, bloomTex_[i], 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                LOGE("ensureBloomTargets(tier1): mip %d FBO incomplete (%dx%d), falling to tier 2", i, w, h);
                ok = false;
                break;
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (ok) {
            bloomMode_    = BLOOM_KARIS;
            bloomEnabled_ = true;
            return true;
        }
        // Clean up any partially-allocated tier-1 resources.
        for (int i = 0; i < kBloomMipCount; ++i) {
            if (bloomTex_[i]) { glDeleteTextures(1,     &bloomTex_[i]); bloomTex_[i] = 0; }
            if (bloomFbo_[i]) { glDeleteFramebuffers(1, &bloomFbo_[i]); bloomFbo_[i] = 0; }
        }
    }

    // ── Tier 2: single separable Gaussian FBO at 1/4 resolution ─────────
    //   Reuses blurFboA_/blurFboB_. If ensureBlurTargets succeeds we can
    //   still run a pass; the main shader just sees a lower-quality bloom.
    LOGE("ensureBloomTargets: tier 1 failed — trying tier 2 (single Gaussian FBO)");
    if (ensureBlurTargets(previewW, previewH)) {
        // Populate bloomTex_[0] with blurTexB_ so the main shader's unit-11
        // bind still has something valid to sample (the blur output).
        // We repurpose bloomTex_[0]/bloomFbo_[0] at blurW_×blurH_ with RGBA8.
        int w = blurW_; int h = blurH_;
        bloomW_[0] = w; bloomH_[0] = h;
        if (!bloomTex_[0]) glGenTextures(1, &bloomTex_[0]);
        glBindTexture(GL_TEXTURE_2D, bloomTex_[0]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        GLenum texErr = glGetError();
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (!bloomFbo_[0]) glGenFramebuffers(1, &bloomFbo_[0]);
        glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo_[0]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, bloomTex_[0], 0);
        bool fboOk = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (texErr == GL_NO_ERROR && fboOk) {
            bloomMode_    = BLOOM_GAUSSIAN;
            bloomEnabled_ = true;
            LOGI("ensureBloomTargets: tier 2 (Gaussian) OK (%dx%d)", w, h);
            return true;
        }
        if (bloomTex_[0]) { glDeleteTextures(1,     &bloomTex_[0]); bloomTex_[0] = 0; }
        if (bloomFbo_[0]) { glDeleteFramebuffers(1, &bloomFbo_[0]); bloomFbo_[0] = 0; }
    }

    // ── Tier 3: bloom disabled — always safe, never crashes ──────────────
    LOGE("ensureBloomTargets: tiers 1+2 failed — bloom disabled (tier 3)");
    bloomEnabled_ = false;
    bloomMode_    = BLOOM_DISABLED;
    return true;   // not an error: tier 3 is the guaranteed safe fallback
}

// Threshold-extract bright pixels from uTex into mip 0 (half-res), then
// downsample each subsequent mip with the 13-tap Karis kernel, then walk
// back up with the 9-tap tent additively blending each smaller mip into
// the next larger. Final mip 0 holds the bloom for the main shader.
//
// thresholdLuma : luma below which pixels contribute zero (soft knee).
// upsampleRadiusPx : controls the spread radius during the upsample tent.
void GlesRenderer::runKarisBloomPass(float thresholdLuma,
                                      float upsampleRadiusPx,
                                      float mistTightness) {
    if (!bloomEnabled_ || bloomMode_ == BLOOM_DISABLED) return;
    if (bloomMode_ == BLOOM_GAUSSIAN) {
        // Tier 2: Gaussian FBO already populated by runBokehBlurPass;
        // copy blurTexB_ into bloomTex_[0] so the main shader's unit-11
        // bind samples the Gaussian blur as a bloom approximation.
        // (blurTexB_ IS bloomTex_[0] in the tier-2 allocation path — same
        // texture object — so no copy is needed; just return.)
        return;
    }
    if (!bloomDownProg_ || !bloomUpProg_) return;
    if (!ensureBloomTargets(texW_, texH_)) return;

    glBindVertexArray(vao_);
    glDisable(GL_BLEND);

    // ── Pass 1: source uTex → mip 0 with highlight threshold ────────
    glUseProgram(bloomDownProg_);
    glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo_[0]);
    glViewport(0, 0, bloomW_[0], bloomH_[0]);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture_);
    if (uBloomDownSrcLoc_       >= 0) glUniform1i(uBloomDownSrcLoc_, 0);
    if (uBloomDownThresholdLoc_ >= 0) glUniform1f(uBloomDownThresholdLoc_, thresholdLuma);
    if (uBloomHighlightStartLoc_ >= 0) glUniform1f(uBloomHighlightStartLoc_, params_.highlightStart);
    if (uBloomHighlightEndLoc_ >= 0) glUniform1f(uBloomHighlightEndLoc_, params_.highlightEnd);
    // Subject-exclusion plumbing — only meaningful on the mip-0 threshold
    // pass. Bind the U2Net subject mask on unit 1 and the Sobel edge mask
    // on unit 2 so the threshold shader can apply (1 - subject) gating
    // BEFORE the downsample chain carries the data into smaller mips.
    {
        GLint subjMaskLoc   = glGetUniformLocation(bloomDownProg_, "uBloomDownSubjectMask");
        GLint subjRectLoc   = glGetUniformLocation(bloomDownProg_, "uBloomDownSubjectRect");
        GLint subjEnLoc     = glGetUniformLocation(bloomDownProg_, "uBloomDownSubjectEnabled");
        GLint subjExLoc     = glGetUniformLocation(bloomDownProg_, "uBloomDownExcludeSubject");
        GLint sobelMaskLoc  = glGetUniformLocation(bloomDownProg_, "uBloomDownSobelMask");
        GLint edgeSnapLoc   = glGetUniformLocation(bloomDownProg_, "uBloomDownEdgeSnapThreshold");
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, subjectMaskTex_);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, sobelEdgeTex_);
        if (subjMaskLoc  >= 0) glUniform1i(subjMaskLoc, 1);
        if (sobelMaskLoc >= 0) glUniform1i(sobelMaskLoc, 2);
        if (subjRectLoc  >= 0) glUniform4fv(subjRectLoc, 1, subjectMaskRect_);
        if (subjEnLoc    >= 0) glUniform1i(subjEnLoc, subjectMaskReady_ ? 1 : 0);
        if (subjExLoc    >= 0) glUniform1f(subjExLoc, params_.bloomExcludeSubject);
        if (edgeSnapLoc  >= 0) glUniform1f(edgeSnapLoc, edgeSnapThreshold_);
        // Reset to unit 0 for downstream passes.
        glActiveTexture(GL_TEXTURE0);
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // ── Passes 2..6: mip[i-1] → mip[i], no threshold ───────────────
    if (uBloomDownThresholdLoc_ >= 0) glUniform1f(uBloomDownThresholdLoc_, -1.0f);
    // The threshold branch in the shader is what carries the subject-
    // exclusion logic. Setting threshold = -1 skips that branch
    // entirely, so the subject gate is also implicitly disabled for
    // downstream mips — no extra uniform reset needed.
    for (int i = 1; i < kBloomMipCount; ++i) {
        glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo_[i]);
        glViewport(0, 0, bloomW_[i], bloomH_[i]);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, bloomTex_[i - 1]);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    // ── Filmic upsample: choke deep mips, bias toward mip1/mip2 ─────
    float upW[6];
    filmicBloomUpWeights(mistTightness, upW);
    float ovalRx = upsampleRadiusPx, ovalRy = upsampleRadiusPx;
    filmicBloomOvalRadii(upsampleRadiusPx, params_.bloomShape, ovalRx, ovalRy);
    glUseProgram(bloomUpProg_);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE);
    if (uBloomUpRadiusLoc_ >= 0) glUniform2f(uBloomUpRadiusLoc_, ovalRx, ovalRy);
    if (uBloomUpSrcLoc_    >= 0) glUniform1i(uBloomUpSrcLoc_, 0);
    for (int i = kBloomMipCount - 1; i > 0; --i) {
        const float w = upW[i];
        if (w < 1e-4f) continue;   // skip near-zero deep mips
        if (uBloomUpWeightLoc_ >= 0) glUniform1f(uBloomUpWeightLoc_, w);
        glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo_[i - 1]);
        glViewport(0, 0, bloomW_[i - 1], bloomH_[i - 1]);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, bloomTex_[i]);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    glDisable(GL_BLEND);
    glBindVertexArray(0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

void GlesRenderer::cacheUniformLocations() {
    // Uniform locations are per-program in GLES. Both `program_` and
    // `programSnap_` share the same fragment shader so the names match,
    // but the integer slot IDs can differ. We cache against the display
    // program; the snapshot path re-pushes uniforms with the snapshot
    // program's own location lookup at that moment (see snapshotGradedToAhb).
    auto L = [&](const char* n) { return glGetUniformLocation(program_, n); };
    uTexLoc_         = L("uTex");
    uViewZoomLoc_    = L("uViewZoom");
    uViewPanLoc_     = L("uViewPan");
    uExposureLoc_    = L("uExposure");
    uContrastLoc_    = L("uContrast");
    uHighlightsLoc_  = L("uHighlights");
    uShadowsLoc_     = L("uShadows");
    uWhitesLoc_      = L("uWhites");
    uBlacksLoc_      = L("uBlacks");
    uWhitesSubjectLoc_    = L("uWhitesSubject");
    uBlacksSubjectLoc_    = L("uBlacksSubject");
    uWhitesBackgroundLoc_ = L("uWhitesBackground");
    uBlacksBackgroundLoc_ = L("uBlacksBackground");
    uShadowsSubjectLoc_    = L("uShadowsSubject");
    uShadowsBackgroundLoc_ = L("uShadowsBackground");
    uHighlightsSubjectLoc_    = L("uHighlightsSubject");
    uHighlightsBackgroundLoc_ = L("uHighlightsBackground");
    uAmbianceSubjectLoc_      = L("uAmbianceSubject");
    uAmbianceBackgroundLoc_   = L("uAmbianceBackground");
    uSaturationLoc_  = L("uSaturation");
    uVibranceLoc_    = L("uVibrance");
    uWhiteBalanceLoc_= L("uWhiteBalance");
    uTintLoc_        = L("uTint");
    uHslRedLoc_      = L("uHslRed");
    uHslOrangeLoc_   = L("uHslOrange");
    uHslYellowLoc_   = L("uHslYellow");
    uHslGreenLoc_    = L("uHslGreen");
    uHslAquaLoc_     = L("uHslAqua");
    uHslBlueLoc_     = L("uHslBlue");
    uHslYellowGreenLoc_ = L("uHslYellowGreen");
    uHslSpringGreenLoc_ = L("uHslSpringGreen");
    uHslSkyBlueLoc_     = L("uHslSkyBlue");
    uHslPurpleLoc_      = L("uHslPurple");
    uHslMagentaLoc_     = L("uHslMagenta");
    uHslPinkLoc_        = L("uHslPink");
    uDitherLoc_      = L("uDitherStrength");
    uPurpleFringeLoc_ = L("uPurpleFringeMode");
    uLutEnabledLoc_   = L("uLutEnabled");
    uLutIntensityLoc_ = L("uLutIntensity");
    uLutBwForceLoc_ = L("uLutBwForce");
    uLutSizeLoc_      = L("uLutSize");
    uLutDomainMinLoc_ = L("uLutDomainMin");
    uLutDomainMaxLoc_ = L("uLutDomainMax");
    uLutHighlightVibrancyLoc_ = L("uLutHighlightVibrancy");
    uHighlightTemperatureLoc_ = L("uHighlightTemperature");
    uHighlightTintLoc_        = L("uHighlightTint");
    uShadowTemperatureLoc_    = L("uShadowTemperature");
    uShadowTintLoc_           = L("uShadowTint");
    uAmbianceLoc_       = L("uAmbiance");
    uOrtonStrengthLoc_  = L("uOrtonStrength");
    uBloomRadiusLoc_    = L("uBloomRadius");
    uBloomShapeLoc_     = L("uBloomShape");
    uFilmRolloffLoc_    = L("uFilmRolloff");
    uFilmicLumaLoc_     = L("uFilmicLuma");
    uOklabHlChromaLoc_  = L("uOklabHlChroma");
    uFilmRecoveryLoc_   = L("uFilmRecovery");
    uFilmFillLightLoc_  = L("uFilmFillLight");
    uFilmMonochromeLoc_ = L("uFilmMonochrome");
    uFilmGrayMixLoc_    = L("uFilmGrayMix");
    uMistTightnessLoc_  = L("uMistTightness");
    uMistHalationLoc_   = L("uMistHalation");
    uGamutCompressLoc_  = L("uGamutCompress");
    uCgGlobalTintLoc_   = L("uCgGlobalTint");
    uCgGlobalSatLoc_    = L("uCgGlobalSat");
    uClarityLiftLoc_    = L("uClarityLift");
    uLensFlareXLoc_          = L("uLensFlareX");
    uLensFlareYLoc_          = L("uLensFlareY");
    uLensFlareBrightnessLoc_ = L("uLensFlareBrightness");
    uLensFlareSizeLoc_       = L("uLensFlareSize");
    uLensFlareSpreadLoc_     = L("uLensFlareSpread");
    uLensFlareWarmthLoc_     = L("uLensFlareWarmth");
    uLensFlareDistanceLoc_   = L("uLensFlareDistance");
    uLensFlareHoodLoc_       = L("uLensFlareHood");
    uSceneDistanceLoc_       = L("uSceneDistance");
    uShadowStrengthLoc_      = L("uShadowStrength");
    uShadowSoftnessLoc_      = L("uShadowSoftness");
    uStarburstLoc_           = L("uStarburst");
    uIrisBladesLoc_          = L("uIrisBlades");
    uIrisRotationLoc_        = L("uIrisRotation");
    uIrisRoundnessLoc_       = L("uIrisRoundness");
    uShadowAspectLoc_        = L("uShadowAspect");
    uColorShiftRedXLoc_      = L("uColorShiftRedX");
    uColorShiftGreenXLoc_    = L("uColorShiftGreenX");
    uColorShiftBlueXLoc_     = L("uColorShiftBlueX");
    uBloomExcludeSubjectLoc_ = L("uBloomExcludeSubject");
    uSubjectBloomLoc_        = L("uSubjectBloom");
    uCgShadowsTintLoc_    = L("uCgShadowsTint");
    uCgShadowsSatLoc_     = L("uCgShadowsSat");
    uCgMidtonesTintLoc_   = L("uCgMidtonesTint");
    uCgMidtonesSatLoc_    = L("uCgMidtonesSat");
    uCgHighlightsTintLoc_ = L("uCgHighlightsTint");
    uCgHighlightsSatLoc_  = L("uCgHighlightsSat");
    uClarityAmountLoc_    = L("uClarityAmount");
    uCenterPopLoc_        = L("uCenterPop");
    uBloomTexLoc_       = L("uBloomTex");
    uOpticalSpreadLoc_    = L("uOpticalSpread");
    uOpticalHalationLoc_  = L("uOpticalHalation");
    uOpticalDirectionLoc_ = L("uOpticalDirection");
    uHighlightStartLoc_ = L("uHighlightStart");
    uHighlightEndLoc_ = L("uHighlightEnd");
    uOpticalDensityLoc_   = L("uOpticalDensity");
    uWorkspaceSpaceLoc_   = L("uWorkspaceSpace");
    uLutAuthoredSpaceLoc_ = L("uLutAuthoredSpace");
    uLightTabOpacityLoc_ = L("uLightTabOpacity");
    uColorTabOpacityLoc_ = L("uColorTabOpacity");
    uXmpTabOpacityLoc_   = L("uXmpTabOpacity");
    uDehazeLoc_          = L("uDehaze");
    uVigAmountLoc_       = L("uVigAmount");
    uVigCenterLoc_       = L("uVigCenter");
    uVigFeatherLoc_      = L("uVigFeather");
    uVigIntensityLoc_    = L("uVigIntensity");
    uVigEffectLoc_       = L("uVigEffect");
    uVigTabOpacityLoc_   = L("uVigTabOpacity");
    uGradAngleLoc_       = L("uGradAngle");
    // Float array uniforms: GLES exposes them as the [0] location.
    uGradTopLoc_         = L("uGradTop[0]");
    uGradBottomLoc_      = L("uGradBottom[0]");
    uGradLeftLoc_        = L("uGradLeft[0]");
    uGradRightLoc_       = L("uGradRight[0]");
    uGradTabOpacityLoc_  = L("uGradTabOpacity");
    uSubjectMaskLoc_        = L("uSubjectMask");
    uSubjectMaskEnabledLoc_ = L("uSubjectMaskEnabled");
    uSubjectMaskRectLoc_    = L("uSubjectMaskRect");
    uBokehAttenLoc_         = L("uBokehAttenuation");
    uBokehAttenEnabledLoc_  = L("uBokehAttenuationEnabled");
    uDepthMapEnabledLoc_    = L("uDepthMapEnabled");
    uBokehFocusDepthLoc_    = L("uBokehFocusDepth");
    uGradTopApplyToLoc_     = L("uGradTopApplyTo");
    uGradBottomApplyToLoc_  = L("uGradBottomApplyTo");
    uGradLeftApplyToLoc_    = L("uGradLeftApplyTo");
    uGradRightApplyToLoc_   = L("uGradRightApplyTo");
    uGradTopBlendModeLoc_    = L("uGradTopBlendMode");
    uGradBottomBlendModeLoc_ = L("uGradBottomBlendMode");
    uGradLeftBlendModeLoc_   = L("uGradLeftBlendMode");
    uGradRightBlendModeLoc_  = L("uGradRightBlendMode");
    uBrushMaskLoc_[0]       = L("uBrushMask");
    uBrushMaskLoc_[1]       = L("uBrushMask1");
    uBrushMaskLoc_[2]       = L("uBrushMask2");
    uBrushMaskLoc_[3]       = L("uBrushMask3");
    uBrushMaskEnabledLoc_   = L("uBrushMaskEnabled");
    uShowMaskOverlayLoc_    = L("uShowMaskOverlay");
    uMaskOverlayLayerLoc_   = L("uMaskOverlayLayer");
    // Array uniforms: glGetUniformLocation returns element [0]; [i] = base+i.
    uMaskBrightnessLoc_     = L("uMaskBrightness");
    uMaskContrastLoc_       = L("uMaskContrast");
    uMaskTemperatureLoc_    = L("uMaskTemperature");
    uMaskTintLoc_           = L("uMaskTint");
    uMaskSaturationLoc_     = L("uMaskSaturation");
    uMaskClarityLoc_        = L("uMaskClarity");
    uMaskSharpnessLoc_      = L("uMaskSharpness");
    // Tone-region slots [410..425] — must match grading_uniforms / shader loop.
    // These locs existed on the class but were never cached, so live preview
    // left uMaskHighlights/… at GL defaults (0) while Brightness/Temp still
    // worked → Mask tab Tone All sliders looked dead.
    uMaskHighlightsLoc_     = L("uMaskHighlights");
    uMaskShadowsLoc_        = L("uMaskShadows");
    uMaskWhitesLoc_         = L("uMaskWhites");
    uMaskBlacksLoc_         = L("uMaskBlacks");
    uMaskTabOpacityLoc_     = L("uMaskTabOpacity");
    uMaskLumTargetLoc_      = L("uMaskLumTarget");
    uMaskLumSpreadLoc_      = L("uMaskLumSpread");
    uMaskLumFeatherLoc_     = L("uMaskLumFeather");
    uMaskLumCombineLoc_     = L("uMaskLumCombine");
    uTonemapExposureLoc_    = L("uTonemapExposure");
    uTonemapHighlightsLoc_  = L("uTonemapHighlights");
    uTonemapShadowsLoc_     = L("uTonemapShadows");
    // uFilmicHlProtectLoc_: dead slot — not cached (see grading_uniforms.h).
    uSobelEdgeMaskLoc_      = L("uSobelEdgeMask");
    uEdgeSnapStrengthLoc_   = L("uEdgeSnapStrength");
    uEdgeSnapThresholdLoc_  = L("uEdgeSnapThreshold");
    uGamutOutLoc_             = L("uGamutOut");
    uLutTexLoc_               = L("uLutTex");

    // Bokeh (GL real-time).
    uBlurTexLoc_     = L("uBlurTex");
    uBokehBlurLoc_   = L("uBokehBlur");
    uBokehBallsLoc_  = L("uBokehBalls");
    uMaskBandingLoc_ = L("uMaskBanding");
    uBokehSpreadLoc_ = L("uBokehSpread");

    // Tone Curve LUT.
    uToneCurveTexLoc_     = L("uToneCurveTex");
    uToneCurveEnabledLoc_ = L("uToneCurveEnabled");
    uToneCurveLumaModeLoc_ = L("uToneCurveLumaMode");
    uFilmHighlightKneeLoc_ = L("uFilmHighlightKnee");

    // Film grain (cinematic 3D noise).
    uFilmGrainLoc_      = L("uFilmGrain");
    uFilmGrainSizeLoc_  = L("uFilmGrainSize");
    uFilmGrainWashLoc_  = L("uFilmGrainWash");
    uGrainExLoc_        = L("uGrainEx[0]");
    uGrainSeedLoc_      = L("uGrainSeed");
    uImageSizeLoc_      = L("uImageSize");

    // Haxademic film grain extension (Req 8, slots 375–378).
    locHaxGrainCrossfade_ = L("uHaxGrainCrossfade");
    locHaxGrainScale_     = L("uHaxGrainScale");
    locHaxGrainLumaAmp_   = L("uHaxGrainLumaAmp");
    locHaxGrainChromaAmp_ = L("uHaxGrainChromaAmp");

    // M5.5 — XMP overlay.
    uXmpEnabledLoc_    = L("uXmpEnabled");
    uXmpExposureLoc_   = L("uXmpExposure");
    uXmpContrastLoc_   = L("uXmpContrast");
    uXmpHighlightsLoc_ = L("uXmpHighlights");
    uXmpShadowsLoc_    = L("uXmpShadows");
    uXmpWhitesLoc_     = L("uXmpWhites");
    uXmpBlacksLoc_     = L("uXmpBlacks");
    uXmpHslRedLoc_     = L("uXmpHslRed");
    uXmpHslOrangeLoc_  = L("uXmpHslOrange");
    uXmpHslYellowLoc_  = L("uXmpHslYellow");
    uXmpHslGreenLoc_   = L("uXmpHslGreen");
    uXmpHslAquaLoc_    = L("uXmpHslAqua");
    uXmpHslBlueLoc_    = L("uXmpHslBlue");

    // PREQ-Port uniform locations
    uHslFullLoc_             = L("uHslFull[0]");
    uCurveMasterTexLoc_      = L("uCurveMasterTex");
    uCurveRTexLoc_           = L("uCurveRTex");
    uCurveGTexLoc_           = L("uCurveGTex");
    uCurveBTexLoc_           = L("uCurveBTex");
    uCurvesEnabledLoc_       = L("uCurvesEnabled");
    uDetailGrainRoughnessLoc_ = L("uDetailGrainRoughness");
    uDetailSharpenMaskLoc_    = L("uDetailSharpenMask");
    uColorDensityLoc_        = L("uColorDensity");
    uFilmSeparationLoc_      = L("uFilmSeparation");
    uSkintoneLoc_            = L("uSkintone");
    uMidtoneDetailsLoc_      = L("uMidtoneDetails");
    uLowFreqMidLoc_          = L("uLowFreqMid");
    uPushPullLoc_            = L("uPushPull");
    uLutColorDensityLoc_     = L("uLutColorDensity");
    uLutSkintoneBalanceLoc_  = L("uLutSkintoneBalance");
    uAberStrengthLoc_        = L("uAberStrength");
    uAberFringeReduceLoc_    = L("uAberFringeReduce");
    uFxBlurStyleLoc_         = L("uFxBlurStyle");
    uFxGaussBlurLoc_         = L("uFxGaussBlur");
    uFxDirBlurAmtLoc_        = L("uFxDirBlurAmt");
    uFxDirBlurAngleLoc_      = L("uFxDirBlurAngle");
    uFxRadBlurAmtLoc_        = L("uFxRadBlurAmt");
    uFxRadBlurCenterLoc_     = L("uFxRadBlurCenter");
    uFxZoomBlurAmtLoc_       = L("uFxZoomBlurAmt");
    uFxZoomBlurCenterLoc_    = L("uFxZoomBlurCenter");
    uFxBlurExcludeSubjectLoc_= L("uFxBlurExcludeSubject");
    uFxMistLoc_              = L("uFxMist");
    uFxMistWarmthLoc_        = L("uFxMistWarmth");
    uFxDustLoc_              = L("uFxDust");
    uFxDustSizeLoc_          = L("uFxDustSize");
    uFxVintageStrengthLoc_   = L("uFxVintageStrength");
    uFxVintageFadeLoc_       = L("uFxVintageFade");
    uFxVintageVigLoc_        = L("uFxVintageVig");
    uFxVintageMistIntensityLoc_ = L("uFxVintageMistIntensity");
    uFxVintageMistScaleLoc_     = L("uFxVintageMistScale");
    uFxVintageTextureIntensityLoc_ = L("uFxVintageTextureIntensity");
    uFxVintageTextureScaleLoc_     = L("uFxVintageTextureScale");
    uFxVintageMistTexLoc_    = L("uFxVintageMistTex");
    uFxVintageFilmTexLoc_    = L("uFxVintageFilmTex");
    uFxGlowStrengthLoc_      = L("uFxGlowStrength");
    uFxGlowSpreadLoc_        = L("uFxGlowSpread");
    uFxGlowWarmthLoc_        = L("uFxGlowWarmth");
    uSmartColorEnhanceLoc_   = L("uSmartColorEnhance");
    uSmartWbMinLoc_          = L("uSmartWbMin");
    uSmartWbMaxLoc_          = L("uSmartWbMax");
}

void GlesRenderer::setParams(const ShaderParams& p) {
    // ── Verbose adjustment debug dump ────────────────────────────────────────
    // Fires a full param dump whenever any value changes OR the workspace
    // selector dialog was reopened (generation counter bumped). This lets
    // logcat show every slider move in the GL preview path fresh each session.
    static int  s_lastGen  = -1;
    static float s_lastExp = 1e9f, s_lastCon = 1e9f, s_lastHi = 1e9f, s_lastSh = 1e9f;
    static float s_lastWh = 1e9f, s_lastBl = 1e9f, s_lastSat = 1e9f, s_lastVib = 1e9f;
    static float s_lastWb = 1e9f, s_lastTint = 1e9f;
    static float s_lastTmExp = 1e9f, s_lastAmb = 1e9f, s_lastClar = 1e9f;
    static float s_lastDhz = 1e9f, s_lastVig = 1e9f, s_lastFilm = 1e9f;
    static float s_lastSmartColor = 1e9f;

    const int curGen = g_debugLogGeneration.load(std::memory_order_relaxed);
    const bool genChanged = (curGen != s_lastGen);

    const bool lightChanged = genChanged ||
        p.exposure   != s_lastExp  || p.contrast   != s_lastCon ||
        p.highlights != s_lastHi   || p.shadows    != s_lastSh  ||
        p.whites     != s_lastWh   || p.blacks      != s_lastBl  ||
        p.saturation != s_lastSat  || p.vibrance    != s_lastVib ||
        p.whiteBalance != s_lastWb || p.tint        != s_lastTint;
    const bool toneChanged = genChanged ||
        p.tonemapExposure != s_lastTmExp || p.ambiance != s_lastAmb ||
        p.clarityAmount   != s_lastClar  || p.dehaze   != s_lastDhz ||
        p.vigAmount       != s_lastVig   || p.filmRolloff != s_lastFilm;

    if (lightChanged) {
        LOGI("[GL-ADJ] LIGHT: exp=%.3f con=%.3f hi=%.3f sh=%.3f wh=%.3f bl=%.3f sat=%.3f vib=%.3f wb=%.1f tint=%.3f",
             p.exposure, p.contrast, p.highlights, p.shadows,
             p.whites, p.blacks, p.saturation, p.vibrance,
             p.whiteBalance, p.tint);
        s_lastExp=p.exposure; s_lastCon=p.contrast; s_lastHi=p.highlights;
        s_lastSh=p.shadows;   s_lastWh=p.whites;    s_lastBl=p.blacks;
        s_lastSat=p.saturation; s_lastVib=p.vibrance;
        s_lastWb=p.whiteBalance; s_lastTint=p.tint;
    }
    if (toneChanged) {
        LOGI("[GL-ADJ] TONE: tonemapExp=%.3f ambiance=%.3f clarity=%.3f dehaze=%.3f vig=%.3f filmRolloff=%.3f",
             p.tonemapExposure, p.ambiance, p.clarityAmount,
             p.dehaze, p.vigAmount, p.filmRolloff);
        s_lastTmExp=p.tonemapExposure; s_lastAmb=p.ambiance;
        s_lastClar=p.clarityAmount;    s_lastDhz=p.dehaze;
        s_lastVig=p.vigAmount;         s_lastFilm=p.filmRolloff;
    }
    if (genChanged) {
        LOGI("[GL-ADJ] --- workspace selector opened (gen=%d) — log reset ---", curGen);
        s_lastGen = curGen;
    }
    static float s_lastFilmRec = 1e9f, s_lastFilmFill = 1e9f;
    if (genChanged || p.filmRecovery != s_lastFilmRec || p.filmFillLight != s_lastFilmFill) {
        LOGI("[GL-ADJ] FILM: recovery=%.3f fillLight=%.3f mono=%d",
             p.filmRecovery, p.filmFillLight, p.filmMonochrome);
        s_lastFilmRec = p.filmRecovery; s_lastFilmFill = p.filmFillLight;
    }
    if (p.smartColorEnhance != s_lastSmartColor) {
        LOGI("[GL-ADJ] SMART_COLOR: enable=%.0f wbMin=(%.3f,%.3f,%.3f) wbMax=(%.3f,%.3f,%.3f) loc=%d",
             p.smartColorEnhance,
             p.smartWbRMin, p.smartWbGMin, p.smartWbBMin,
             p.smartWbRMax, p.smartWbGMax, p.smartWbBMax,
             uSmartColorEnhanceLoc_);
        s_lastSmartColor = p.smartColorEnhance;
    }

    // Legacy segmentation/mask change-detect logs (kept for compatibility).
    static int lastVig = -1, lastT = -1, lastB = -1, lastL = -1, lastR = -1;
    static float lastMB = 1e9f, lastMC = 1e9f, lastMTemp = 1e9f, lastMTint = 1e9f, lastMSat = 1e9f;
    int vig = int(p.vigEffect);
    int tg = int(p.gradTopApplyTo);
    int bg = int(p.gradBottomApplyTo);
    int lg = int(p.gradLeftApplyTo);
    int rg = int(p.gradRightApplyTo);
    bool segChanged = vig != lastVig || tg != lastT || bg != lastB || lg != lastL || rg != lastR;
    bool maskChanged = p.maskBrightness != lastMB || p.maskContrast != lastMC ||
                       p.maskTemperature != lastMTemp || p.maskTint != lastMTint ||
                       p.maskSaturation != lastMSat;
    if (segChanged) {
        LOGI("setParams: vigEffect=%d gradApplyTo T=%d B=%d L=%d R=%d (subjMask=%d brushMask0=%d)",
             vig, tg, bg, lg, rg, subjectMaskReady_ ? 1 : 0, brushMaskReady_[0] ? 1 : 0);
        lastVig = vig; lastT = tg; lastB = bg; lastL = lg; lastR = rg;
    }
    if (maskChanged) {
        LOGI("setParams mask[0]: brightness=%.2f contrast=%.2f temp=%.0f tint=%.2f sat=%.2f opacity=%.2f (brushReady=%d)",
             p.maskBrightness, p.maskContrast, p.maskTemperature, p.maskTint, p.maskSaturation,
             p.maskTabOpacity, brushMaskReady_[0] ? 1 : 0);
        lastMB = p.maskBrightness; lastMC = p.maskContrast;
        lastMTemp = p.maskTemperature; lastMTint = p.maskTint; lastMSat = p.maskSaturation;
    }
    // Per-segment tone + bloom diagnostics — log whenever any subject-targeted value changes.
    static float s_lastHiSubj=1e9f, s_lastShSubj=1e9f, s_lastWhSubj=1e9f;
    static float s_lastAmSubj=1e9f, s_lastBloomEx=1e9f, s_lastSubjBloom=1e9f, s_lastOrton=1e9f;
    bool segToneChanged = p.highlightsSubject != s_lastHiSubj || p.shadowsSubject != s_lastShSubj
                       || p.whitesSubject     != s_lastWhSubj || p.ambianceSubject != s_lastAmSubj;
    bool bloomChanged   = p.bloomExcludeSubject != s_lastBloomEx || p.subjectBloom != s_lastSubjBloom
                       || p.ortonStrength != s_lastOrton;
    if (segToneChanged) {
        LOGI("[GL-ADJ] SUBJ-TONE: hiSubj=%.3f shSubj=%.3f whSubj=%.3f ambSubj=%.3f | hiBg=%.3f shBg=%.3f whBg=%.3f ambBg=%.3f (maskReady=%d)",
             p.highlightsSubject, p.shadowsSubject, p.whitesSubject, p.ambianceSubject,
             p.highlightsBackground, p.shadowsBackground, p.whitesBackground, p.ambianceBackground,
             subjectMaskReady_ ? 1 : 0);
        s_lastHiSubj = p.highlightsSubject; s_lastShSubj = p.shadowsSubject;
        s_lastWhSubj = p.whitesSubject;     s_lastAmSubj = p.ambianceSubject;
    }
    if (bloomChanged) {
        LOGI("[GL-ADJ] BLOOM: orton=%.3f excludeSubj=%d subjBloom=%.3f (maskReady=%d)",
             p.ortonStrength, int(p.bloomExcludeSubject > 0.5f), p.subjectBloom,
             subjectMaskReady_ ? 1 : 0);
        s_lastOrton = p.ortonStrength; s_lastBloomEx = p.bloomExcludeSubject;
        s_lastSubjBloom = p.subjectBloom;
    }
    updateDirtyFlags(p);
    params_ = p;
}

// ── Solution A: update pre-pass dirty flags ──────────────────────────────
void GlesRenderer::updateDirtyFlags(const ShaderParams& p) {
    // Blur pass: driven by bokeh, ambiance, and fx blur params.
    if (p.bokehBlur   != lastBlurBokehBlur_   ||
        p.bokehSpread != lastBlurBokehSpread_ ||
        p.ambiance    != lastBlurAmbiance_    ||
        p.fxGaussBlur != lastBlurFxGaussBlur_ ||
        p.fxDirBlurAmt  != lastBlurFxDirBlurAmt_ ||
        p.fxRadBlurAmt  != lastBlurFxRadBlurAmt_ ||
        p.fxZoomBlurAmt != lastBlurFxZoomBlurAmt_ ||
        p.fxBlurStyle   != lastBlurFxBlurStyle_ ||
        p.clarityAmount != lastBlurClarity_ ||
        p.centerPop     != lastBlurCenterPop_) {
        blurPassDirty_ = true;
        lastBlurBokehBlur_    = p.bokehBlur;
        lastBlurBokehSpread_  = p.bokehSpread;
        lastBlurAmbiance_     = p.ambiance;
        lastBlurFxGaussBlur_  = p.fxGaussBlur;
        lastBlurFxDirBlurAmt_ = p.fxDirBlurAmt;
        lastBlurFxRadBlurAmt_ = p.fxRadBlurAmt;
        lastBlurFxZoomBlurAmt_= p.fxZoomBlurAmt;
        lastBlurFxBlurStyle_  = p.fxBlurStyle;
        lastBlurClarity_      = p.clarityAmount;
        lastBlurCenterPop_    = p.centerPop;
    }
    // Bloom pass: orton / radius / glow / mist tightness / shape all rebuild
    // the Karis pyramid (halation is main-pass UV only — no dirty needed).
    // Soft-diff Gaussian is a separate plane (not uBlurTex) — dirty that flag.
    if (p.ortonStrength  != lastBloomOrton_  ||
        p.bloomRadius    != lastBloomRadius_ ||
        p.fxGlowStrength != lastBloomGlow_   ||
        p.mistTightness  != lastBloomTight_  ||
        p.bloomShape     != lastBloomHalation_ ||
        p.opticalSpread  != lastOpticalSpread_ ||
        p.opticalHalation != lastOpticalHalation_ ||
        p.opticalDirection != lastOpticalDirection_ ||
        p.highlightStart != lastHighlightStart_ ||
        p.highlightEnd != lastHighlightEnd_) {
        bloomPassDirty_    = true;
        softDiffPassDirty_ = true;
        lastBloomOrton_    = p.ortonStrength;
        lastBloomRadius_   = p.bloomRadius;
        lastBloomGlow_     = p.fxGlowStrength;
        lastBloomTight_    = p.mistTightness;
        lastBloomHalation_ = p.bloomShape; // tracks shape (name legacy)
        lastOpticalSpread_ = p.opticalSpread;
        lastOpticalHalation_ = p.opticalHalation;
        lastOpticalDirection_ = p.opticalDirection;
        lastHighlightStart_ = p.highlightStart;
        lastHighlightEnd_ = p.highlightEnd;
    }
    // NR pass: driven by NR slot values (set via setNrSlots, not ShaderParams).
    if (nrSlot147_ != lastNrSlot147_ ||
        nrSlot148_ != lastNrSlot148_) {
        nrPassDirty_   = true;
        lastNrSlot147_ = nrSlot147_;
        lastNrSlot148_ = nrSlot148_;
    }
}

bool GlesRenderer::uploadLut3d(const char* cubePath) {
    if (display_ == EGL_NO_DISPLAY) {
        LOGE("uploadLut3d: renderer not initialized");
        return false;
    }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("uploadLut3d: eglMakeCurrent failed err=0x%x", eglGetError());
        return false;
    }
    auto lut = parseCubeFile(cubePath ? cubePath : "");
    if (lut.size == 0) {
        LOGE("uploadLut3d: parse failed for %s", cubePath ? cubePath : "(null)");
        return false;
    }
    // Task 4.5: free the old LUT texture before allocating the new one
    // to avoid leaking the GL object. The shader won't sample it between
    // here and uploadCubeLutAsTexture3D because we are on the GL thread.
    if (lutTexture_ != 0) {
        glDeleteTextures(1, &lutTexture_);
        lutTexture_ = 0;
    }
    GLuint newTex = uploadCubeLutAsTexture3D(lut);
    if (!newTex) return false;
    lutTexture_  = newTex;
    lutSize_     = lut.size;       // for the half-texel sampling fix
    for (int i = 0; i < 3; ++i) {
        lutDomainMin_[i] = lut.domainMin[i];
        lutDomainMax_[i] = lut.domainMax[i];
    }
    lutUploaded_ = true;
    return true;
}

void GlesRenderer::clearLut3d() {
    if (display_ != EGL_NO_DISPLAY && lutTexture_) {
        eglMakeCurrent(display_, surface_, surface_, context_);
        glDeleteTextures(1, &lutTexture_);
    }
    lutTexture_  = 0;
    lutUploaded_ = false;
    lutSize_     = 33;   // default; no-op while uLutEnabled=0
    for (int i = 0; i < 3; ++i) { lutDomainMin_[i] = 0.f; lutDomainMax_[i] = 1.f; }
}

// Fritsch-Carlson monotone cubic interpolation from 8 (x,y) control points → 256-point LUT.
// control_pts: 16 floats [x0,y0, x1,y1, …, x7,y7] in [0,1]×[0,1], must be monotone in x.
// Returns a 256-element float array to be uploaded as GL_R16F 1×256 texture.
static std::vector<float> buildMonotoneCubicLut(const float* control_pts, int n_pts) {
    // n_pts = 8 (16 floats)
    int n = n_pts;
    std::vector<double> xs(n), ys(n);
    for (int i = 0; i < n; i++) { xs[i] = control_pts[i*2]; ys[i] = control_pts[i*2+1]; }

    // Compute secant slopes
    std::vector<double> delta(n-1), m(n);
    for (int i = 0; i < n-1; i++) {
        double dx = xs[i+1] - xs[i];
        delta[i] = (dx < 1e-12) ? 0.0 : (ys[i+1] - ys[i]) / dx;
    }
    // Tangents: average of adjacent secants
    m[0] = delta[0];
    for (int i = 1; i < n-1; i++) m[i] = (delta[i-1] + delta[i]) * 0.5;
    m[n-1] = delta[n-2];
    // Fritsch-Carlson monotonicity constraint
    for (int i = 0; i < n-1; i++) {
        if (fabsf((float)delta[i]) < 1e-12) { m[i] = m[i+1] = 0.0; continue; }
        double alpha = m[i] / delta[i], beta = m[i+1] / delta[i];
        if (alpha*alpha + beta*beta > 9.0) {
            double t = 3.0 / sqrt(alpha*alpha + beta*beta);
            m[i] = t * alpha * delta[i]; m[i+1] = t * beta * delta[i];
        }
    }

    // Sample 256 points
    std::vector<float> lut(256);
    for (int k = 0; k < 256; k++) {
        double t = k / 255.0;
        // Find segment
        int seg = n - 2;
        for (int i = 0; i < n-1; i++) { if (t <= xs[i+1]) { seg = i; break; } }
        double h = xs[seg+1] - xs[seg];
        if (h < 1e-12) { lut[k] = (float)ys[seg]; continue; }
        double u = (t - xs[seg]) / h;
        double u2 = u*u, u3 = u2*u;
        double val = (2*u3 - 3*u2 + 1)*ys[seg]
                   + (u3 - 2*u2 + u)*h*m[seg]
                   + (-2*u3 + 3*u2)*ys[seg+1]
                   + (u3 - u2)*h*m[seg+1];
        lut[k] = (float)fmax(0.0, fmin(1.0, val));
    }
    return lut;
}

static GLuint uploadLut1D(const float* data, int size) {
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R16F, size, 1, 0, GL_RED, GL_FLOAT, data);
    glBindTexture(GL_TEXTURE_2D, 0);
    return tex;
}

void GlesRenderer::uploadCurveLuts(const float* master, const float* r, const float* g, const float* b) {
    if (display_ == EGL_NO_DISPLAY) return;
    eglMakeCurrent(display_, surface_, surface_, context_);
    auto del = [](GLuint& t) { if (t) { glDeleteTextures(1, &t); t = 0; } };
    del(curveMasterTex_); del(curveRTex_); del(curveGTex_); del(curveBTex_);
    auto lm = buildMonotoneCubicLut(master, 8);
    auto lr = buildMonotoneCubicLut(r,      8);
    auto lg = buildMonotoneCubicLut(g,      8);
    auto lb = buildMonotoneCubicLut(b,      8);
    curveMasterTex_ = uploadLut1D(lm.data(), 256);
    curveRTex_      = uploadLut1D(lr.data(), 256);
    curveGTex_      = uploadLut1D(lg.data(), 256);
    curveBTex_      = uploadLut1D(lb.data(), 256);
}

bool GlesRenderer::uploadSubjectMask(const uint8_t* gray8, int width, int height) {
    if (display_ == EGL_NO_DISPLAY) {
        LOGE("uploadSubjectMask: renderer not initialized");
        return false;
    }
    if (!gray8 || width <= 0 || height <= 0) {
        LOGE("uploadSubjectMask: bad args (%p %dx%d)", gray8, width, height);
        return false;
    }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("uploadSubjectMask: eglMakeCurrent failed err=0x%x", eglGetError());
        return false;
    }
    // (Re)allocate immutable storage if size changed or first call.
    if (subjectMaskTex_ == 0 || width != subjectMaskW_ || height != subjectMaskH_) {
        if (subjectMaskTex_) {
            glDeleteTextures(1, &subjectMaskTex_);
            subjectMaskTex_ = 0;
        }
        glGenTextures(1, &subjectMaskTex_);
        glBindTexture(GL_TEXTURE_2D, subjectMaskTex_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        // GL_R8 = single-channel 8-bit normalized, sampled as .r in [0,1].
        // Immutable storage = driver never reallocates on subsequent uploads.
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R8, width, height);
        subjectMaskW_ = width;
        subjectMaskH_ = height;
    } else {
        glBindTexture(GL_TEXTURE_2D, subjectMaskTex_);
    }
    // Tight packing — 320×320 row stride = 320 bytes, default unpack of 4
    // would otherwise round up.
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GL_RED, GL_UNSIGNED_BYTE, gray8);
    glBindTexture(GL_TEXTURE_2D, 0);
    subjectMaskReady_ = true;
    return true;
}

bool GlesRenderer::uploadSubjectMask(AHardwareBuffer* ahb, int fenceFd) {
    if (display_ == EGL_NO_DISPLAY) return false;
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) return false;
    bool ok = importAhbToTexture(ahb, subjectMaskTex_, subjectMaskW_, subjectMaskH_,
                                 subjectMaskImage_, subjectMaskAhb_,
                                 AHARDWAREBUFFER_FORMAT_R8_UNORM,
                                 AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, fenceFd);
    if (ok) subjectMaskReady_ = true;
    return ok;
}


// Rebuild unit-10 GL_RG8 from CPU planes (R=atten, G=depth). Either plane
// may be empty — missing channel uploads as 0.
bool GlesRenderer::rebuildBokehAuxTexLocked(int width, int height) {
    if (width <= 0 || height <= 0) return false;
    const size_t n = size_t(width) * size_t(height);
    if (bokehAttenPlane_.size() != n) {
        bokehAttenPlane_.assign(n, 0);
    }
    if (depthPlane_.size() != n) {
        depthPlane_.assign(n, 0);
    }
    std::vector<uint8_t> rg(n * 2);
    for (size_t i = 0; i < n; ++i) {
        rg[i * 2 + 0] = bokehAttenPlane_[i];
        rg[i * 2 + 1] = depthPlane_[i];
    }
    if (bokehAttenTex_ == 0 || width != bokehAttenW_ || height != bokehAttenH_) {
        if (bokehAttenTex_) {
            glDeleteTextures(1, &bokehAttenTex_);
            bokehAttenTex_ = 0;
        }
        glGenTextures(1, &bokehAttenTex_);
        glBindTexture(GL_TEXTURE_2D, bokehAttenTex_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RG8, width, height);
        bokehAttenW_ = width;
        bokehAttenH_ = height;
    } else {
        glBindTexture(GL_TEXTURE_2D, bokehAttenTex_);
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GL_RG, GL_UNSIGNED_BYTE, rg.data());
    glBindTexture(GL_TEXTURE_2D, 0);
    return true;
}

bool GlesRenderer::uploadBokehAttenuation(const uint8_t* gray8, int width, int height) {
    // Packs into unit-10 GL_RG8 .r (depth lives in .g). Same letterbox UV
    // as subject mask via uSubjectMaskRect.
    if (display_ == EGL_NO_DISPLAY) {
        LOGE("uploadBokehAttenuation: renderer not initialized");
        return false;
    }
    if (!gray8 || width <= 0 || height <= 0) {
        LOGE("uploadBokehAttenuation: bad args (%p %dx%d)", gray8, width, height);
        return false;
    }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("uploadBokehAttenuation: eglMakeCurrent failed err=0x%x", eglGetError());
        return false;
    }
    const size_t n = size_t(width) * size_t(height);
    // Size change: keep depth if same resolution, else reset depth plane.
    if (depthPlane_.size() != n) {
        depthPlane_.assign(n, 0);
        depthMapReady_ = false;
    }
    bokehAttenPlane_.assign(gray8, gray8 + n);
    if (!rebuildBokehAuxTexLocked(width, height)) return false;
    bokehAttenReady_ = true;
    return true;
}

bool GlesRenderer::uploadBokehAttenuation(AHardwareBuffer* ahb, int fenceFd) {
    if (display_ == EGL_NO_DISPLAY) return false;
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) return false;
    bool ok = importAhbToTexture(ahb, bokehAttenTex_, bokehAttenW_, bokehAttenH_,
                                 bokehAttenImage_, bokehAttenAhb_,
                                 AHARDWAREBUFFER_FORMAT_R8_UNORM,
                                 AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, fenceFd);
    if (ok) bokehAttenReady_ = true;
    return ok;
}

bool GlesRenderer::uploadDepthMap(const uint8_t* gray8, int width, int height,
                                  float focusDepth01) {
    if (display_ == EGL_NO_DISPLAY) {
        LOGE("uploadDepthMap: renderer not initialized");
        return false;
    }
    if (!gray8 || width <= 0 || height <= 0) {
        LOGE("uploadDepthMap: bad args (%p %dx%d)", gray8, width, height);
        return false;
    }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("uploadDepthMap: eglMakeCurrent failed err=0x%x", eglGetError());
        return false;
    }
    const size_t n = size_t(width) * size_t(height);
    if (bokehAttenPlane_.size() != n) {
        bokehAttenPlane_.assign(n, 0);
        bokehAttenReady_ = false;
    }
    depthPlane_.assign(gray8, gray8 + n);
    bokehFocusDepth_ = focusDepth01 < 0.f ? 0.f : (focusDepth01 > 1.f ? 1.f : focusDepth01);
    if (!rebuildBokehAuxTexLocked(width, height)) return false;
    depthMapReady_ = true;
    return true;
}

bool GlesRenderer::uploadDepthMap(AHardwareBuffer* ahb, float focusDepth01, int fenceFd) {
    if (display_ == EGL_NO_DISPLAY) return false;
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) return false;
    // AHB depth map overwrites the unit-10 RG8 texture.
    bool ok = importAhbToTexture(ahb, bokehAttenTex_, bokehAttenW_, bokehAttenH_,
                                 bokehAttenImage_, bokehAttenAhb_,
                                 AHARDWAREBUFFER_FORMAT_R8_UNORM,
                                 AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, fenceFd);
    if (ok) {
        depthMapReady_ = true;
        bokehFocusDepth_ = focusDepth01 < 0.f ? 0.f : (focusDepth01 > 1.f ? 1.f : focusDepth01);
    }
    return ok;
}

void GlesRenderer::clearDepthMap() {
    depthMapReady_ = false;
    bokehFocusDepth_ = 0.5f;
    if (display_ != EGL_NO_DISPLAY && bokehAttenImage_ != EGL_NO_IMAGE_KHR && fn_eglDestroyImageKHR) {
        fn_eglDestroyImageKHR(display_, bokehAttenImage_);
        bokehAttenImage_ = EGL_NO_IMAGE_KHR;
        if (bokehAttenAhb_) { AHardwareBuffer_release(bokehAttenAhb_); bokehAttenAhb_ = nullptr; }
    }
    if (!depthPlane_.empty()) {
        std::fill(depthPlane_.begin(), depthPlane_.end(), 0);
        if (display_ != EGL_NO_DISPLAY && bokehAttenTex_ != 0 &&
            bokehAttenW_ > 0 && bokehAttenH_ > 0) {
            if (eglMakeCurrent(display_, surface_, surface_, context_)) {
                rebuildBokehAuxTexLocked(bokehAttenW_, bokehAttenH_);
            }
        }
    }
}

bool GlesRenderer::uploadSobelEdgeMask(const uint8_t* gray8, int width, int height) {
    if (display_ == EGL_NO_DISPLAY || !gray8 || width <= 0 || height <= 0) return false;
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("uploadSobelEdgeMask: eglMakeCurrent failed err=0x%x", eglGetError());
        return false;
    }
    if (sobelEdgeTex_ == 0 || width != sobelEdgeW_ || height != sobelEdgeH_) {
        if (sobelEdgeTex_) {
            glDeleteTextures(1, &sobelEdgeTex_);
            sobelEdgeTex_ = 0;
        }
        glGenTextures(1, &sobelEdgeTex_);
        glBindTexture(GL_TEXTURE_2D, sobelEdgeTex_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R8, width, height);
        sobelEdgeW_ = width;
        sobelEdgeH_ = height;
    } else {
        glBindTexture(GL_TEXTURE_2D, sobelEdgeTex_);
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GL_RED, GL_UNSIGNED_BYTE, gray8);
    glBindTexture(GL_TEXTURE_2D, 0);
    sobelEdgeReady_ = true;
    return true;
}

bool GlesRenderer::uploadSobelEdgeMask(AHardwareBuffer* ahb, int fenceFd) {
    if (display_ == EGL_NO_DISPLAY) return false;
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) return false;
    bool ok = importAhbToTexture(ahb, sobelEdgeTex_, sobelEdgeW_, sobelEdgeH_,
                                 sobelEdgeImage_, sobelEdgeAhb_,
                                 AHARDWAREBUFFER_FORMAT_R8_UNORM,
                                 AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, fenceFd);
    if (ok) sobelEdgeReady_ = true;
    return ok;
}

void GlesRenderer::clearSobelEdgeMask() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, surface_, surface_, context_);
        if (sobelEdgeImage_ != EGL_NO_IMAGE_KHR && fn_eglDestroyImageKHR) {
            fn_eglDestroyImageKHR(display_, sobelEdgeImage_);
            sobelEdgeImage_ = EGL_NO_IMAGE_KHR;
            if (sobelEdgeAhb_) { AHardwareBuffer_release(sobelEdgeAhb_); sobelEdgeAhb_ = nullptr; }
        }
        if (sobelEdgeTex_) glDeleteTextures(1, &sobelEdgeTex_);
    }
    sobelEdgeTex_   = 0;
    sobelEdgeW_     = 0;
    sobelEdgeH_     = 0;
    sobelEdgeReady_ = false;
}

bool GlesRenderer::uploadToneCurve(const uint8_t* rgb256) {
    if (display_ == EGL_NO_DISPLAY || !rgb256) return false;
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("uploadToneCurve: eglMakeCurrent failed err=0x%x", eglGetError());
        return false;
    }
    if (toneCurveTex_ == 0) {
        glGenTextures(1, &toneCurveTex_);
        glBindTexture(GL_TEXTURE_2D, toneCurveTex_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGB8, 256, 1);
    } else {
        glBindTexture(GL_TEXTURE_2D, toneCurveTex_);
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 256, 1,
                    GL_RGB, GL_UNSIGNED_BYTE, rgb256);
    glBindTexture(GL_TEXTURE_2D, 0);
    toneCurveReady_ = true;
    return true;
}

void GlesRenderer::clearToneCurve() {
    if (display_ != EGL_NO_DISPLAY && toneCurveTex_) {
        eglMakeCurrent(display_, surface_, surface_, context_);
        glDeleteTextures(1, &toneCurveTex_);
    }
    toneCurveTex_   = 0;
    toneCurveReady_ = false;
}

void GlesRenderer::ensureVintageBlackTex() {
    if (fxVintageBlackTex_) return;
    glGenTextures(1, &fxVintageBlackTex_);
    glBindTexture(GL_TEXTURE_2D, fxVintageBlackTex_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    const uint8_t z[4] = {0, 0, 0, 255};
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, z);
    glBindTexture(GL_TEXTURE_2D, 0);
}

bool GlesRenderer::uploadVintageOverlay(GLuint& tex, int& tw, int& th,
                                        const uint8_t* bytes, int nbytes,
                                        int width, int height) {
    if (display_ == EGL_NO_DISPLAY) {
        LOGE("uploadVintageOverlay: renderer not initialized");
        return false;
    }
    if (!bytes || width <= 0 || height <= 0 || nbytes <= 0) {
        LOGE("uploadVintageOverlay: bad args (%p %dx%d n=%d)", bytes, width, height, nbytes);
        return false;
    }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("uploadVintageOverlay: eglMakeCurrent failed err=0x%x", eglGetError());
        return false;
    }
    const size_t n = size_t(width) * size_t(height);
    const uint8_t* rgba = bytes;
    std::vector<uint8_t> expand;
    if (nbytes >= int(n * 4)) {
        rgba = bytes;
    } else if (nbytes >= int(n * 3)) {
        expand.resize(n * 4);
        for (size_t i = 0; i < n; ++i) {
            expand[i * 4 + 0] = bytes[i * 3 + 0];
            expand[i * 4 + 1] = bytes[i * 3 + 1];
            expand[i * 4 + 2] = bytes[i * 3 + 2];
            expand[i * 4 + 3] = 255;
        }
        rgba = expand.data();
    } else if (nbytes >= int(n)) {
        expand.resize(n * 4);
        for (size_t i = 0; i < n; ++i) {
            expand[i * 4 + 0] = bytes[i];
            expand[i * 4 + 1] = bytes[i];
            expand[i * 4 + 2] = bytes[i];
            expand[i * 4 + 3] = 255;
        }
        rgba = expand.data();
    } else {
        LOGE("uploadVintageOverlay: short buffer %d for %dx%d", nbytes, width, height);
        return false;
    }
    if (tex == 0 || width != tw || height != th) {
        if (tex) {
            glDeleteTextures(1, &tex);
            tex = 0;
        }
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height);
        tw = width;
        th = height;
    } else {
        glBindTexture(GL_TEXTURE_2D, tex);
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    glBindTexture(GL_TEXTURE_2D, 0);
    return true;
}

bool GlesRenderer::uploadVintageMist(const uint8_t* bytes, int nbytes, int width, int height) {
    return uploadVintageOverlay(fxVintageMistTex_, fxVintageMistW_, fxVintageMistH_,
                                bytes, nbytes, width, height);
}

bool GlesRenderer::uploadVintageFilm(const uint8_t* bytes, int nbytes, int width, int height) {
    return uploadVintageOverlay(fxVintageFilmTex_, fxVintageFilmW_, fxVintageFilmH_,
                                bytes, nbytes, width, height);
}

void GlesRenderer::bindVintageFxTextures(GLuint prog) {
    GLint mistLoc = (prog == program_) ? uFxVintageMistTexLoc_
                    : glGetUniformLocation(prog, "uFxVintageMistTex");
    GLint filmLoc = (prog == program_) ? uFxVintageFilmTexLoc_
                    : glGetUniformLocation(prog, "uFxVintageFilmTex");
    if (mistLoc < 0 && filmLoc < 0) return;
    ensureVintageBlackTex();
    if (mistLoc >= 0) {
        glActiveTexture(GL_TEXTURE0 + fxVintageTextureUnitBase_);
        glBindTexture(GL_TEXTURE_2D, fxVintageMistTex_ ? fxVintageMistTex_ : fxVintageBlackTex_);
        glUniform1i(mistLoc, fxVintageTextureUnitBase_);
    }
    if (filmLoc >= 0) {
        glActiveTexture(GL_TEXTURE0 + fxVintageTextureUnitBase_ + 1);
        glBindTexture(GL_TEXTURE_2D, fxVintageFilmTex_ ? fxVintageFilmTex_ : fxVintageBlackTex_);
        glUniform1i(filmLoc, fxVintageTextureUnitBase_ + 1);
    }
}


void GlesRenderer::setEdgeSnap(float strength, float threshold) {
    edgeSnapStrength_  = strength;
    edgeSnapThreshold_ = threshold;
}

void GlesRenderer::setSubjectMaskInnerRect(float u0, float v0, float u1, float v1) {
    subjectMaskRect_[0] = u0;
    subjectMaskRect_[1] = v0;
    subjectMaskRect_[2] = u1;
    subjectMaskRect_[3] = v1;
}

bool GlesRenderer::uploadBrushMask(int layer, const uint8_t* gray8, int width, int height) {
    if (layer < 0 || layer >= kMaskLayers) return false;
    if (display_ == EGL_NO_DISPLAY) return false;
    if (!gray8 || width <= 0 || height <= 0) return false;
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("uploadBrushMask: eglMakeCurrent failed err=0x%x", eglGetError());
        return false;
    }
    GLuint& tex = brushMaskTex_[layer];
    if (tex == 0 || width != brushMaskW_[layer] || height != brushMaskH_[layer]) {
        if (tex) { glDeleteTextures(1, &tex); tex = 0; }
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R8, width, height);
        brushMaskW_[layer] = width;
        brushMaskH_[layer] = height;
    } else {
        glBindTexture(GL_TEXTURE_2D, tex);
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GL_RED, GL_UNSIGNED_BYTE, gray8);
    glBindTexture(GL_TEXTURE_2D, 0);
    brushMaskReady_[layer] = true;
    return true;
}

bool GlesRenderer::uploadBrushMask(int layer, AHardwareBuffer* ahb, int fenceFd) {
    if (layer < 0 || layer >= kMaskLayers) return false;
    if (display_ == EGL_NO_DISPLAY) return false;
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) return false;
    bool ok = importAhbToTexture(ahb, brushMaskTex_[layer], brushMaskW_[layer], brushMaskH_[layer],
                                 brushMaskImage_[layer], brushMaskAhb_[layer],
                                 AHARDWAREBUFFER_FORMAT_R8_UNORM,
                                 AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, fenceFd);
    if (ok) brushMaskReady_[layer] = true;
    return ok;
}

void GlesRenderer::clearBrushMask(int layer) {
    if (layer < 0 || layer >= kMaskLayers) return;
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, surface_, surface_, context_);
        if (brushMaskImage_[layer] != EGL_NO_IMAGE_KHR && fn_eglDestroyImageKHR) {
            fn_eglDestroyImageKHR(display_, brushMaskImage_[layer]);
            brushMaskImage_[layer] = EGL_NO_IMAGE_KHR;
            if (brushMaskAhb_[layer]) { AHardwareBuffer_release(brushMaskAhb_[layer]); brushMaskAhb_[layer] = nullptr; }
        }
        if (brushMaskTex_[layer]) glDeleteTextures(1, &brushMaskTex_[layer]);
    }
    brushMaskTex_[layer]   = 0;
    brushMaskW_[layer]     = 0;
    brushMaskH_[layer]     = 0;
    brushMaskReady_[layer] = false;
}

void GlesRenderer::clearAllBrushMasks() {
    for (int i = 0; i < kMaskLayers; ++i) clearBrushMask(i);
}

void GlesRenderer::clearSubjectMask() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, surface_, surface_, context_);
        if (subjectMaskImage_ != EGL_NO_IMAGE_KHR && fn_eglDestroyImageKHR) {
            fn_eglDestroyImageKHR(display_, subjectMaskImage_);
            subjectMaskImage_ = EGL_NO_IMAGE_KHR;
            if (subjectMaskAhb_) { AHardwareBuffer_release(subjectMaskAhb_); subjectMaskAhb_ = nullptr; }
        }
        if (subjectMaskTex_) glDeleteTextures(1, &subjectMaskTex_);
    }
    subjectMaskTex_   = 0;
    subjectMaskW_     = 0;
    subjectMaskH_     = 0;
    subjectMaskReady_ = false;
}

void GlesRenderer::pushUniforms() {
    if (uExposureLoc_     >= 0) glUniform1f(uExposureLoc_,     params_.exposure);
    if (uContrastLoc_     >= 0) glUniform1f(uContrastLoc_,     params_.contrast);
    if (uHighlightsLoc_   >= 0) glUniform1f(uHighlightsLoc_,   params_.highlights);
    if (uShadowsLoc_      >= 0) glUniform1f(uShadowsLoc_,      params_.shadows);
    if (uWhitesLoc_       >= 0) glUniform1f(uWhitesLoc_,       params_.whites);
    if (uBlacksLoc_       >= 0) glUniform1f(uBlacksLoc_,       params_.blacks);
    if (uWhitesSubjectLoc_    >= 0) glUniform1f(uWhitesSubjectLoc_,    params_.whitesSubject);
    if (uBlacksSubjectLoc_    >= 0) glUniform1f(uBlacksSubjectLoc_,    params_.blacksSubject);
    if (uWhitesBackgroundLoc_ >= 0) glUniform1f(uWhitesBackgroundLoc_, params_.whitesBackground);
    if (uBlacksBackgroundLoc_ >= 0) glUniform1f(uBlacksBackgroundLoc_, params_.blacksBackground);
    if (uShadowsSubjectLoc_    >= 0) glUniform1f(uShadowsSubjectLoc_,    params_.shadowsSubject);
    if (uShadowsBackgroundLoc_ >= 0) glUniform1f(uShadowsBackgroundLoc_, params_.shadowsBackground);
    if (uHighlightsSubjectLoc_    >= 0) glUniform1f(uHighlightsSubjectLoc_,    params_.highlightsSubject);
    if (uHighlightsBackgroundLoc_ >= 0) glUniform1f(uHighlightsBackgroundLoc_, params_.highlightsBackground);
    if (uAmbianceSubjectLoc_      >= 0) glUniform1f(uAmbianceSubjectLoc_,      params_.ambianceSubject);
    if (uAmbianceBackgroundLoc_   >= 0) glUniform1f(uAmbianceBackgroundLoc_,   params_.ambianceBackground);
    if (uSaturationLoc_   >= 0) glUniform1f(uSaturationLoc_,   params_.saturation);
    if (uVibranceLoc_     >= 0) glUniform1f(uVibranceLoc_,     params_.vibrance);
    if (uWhiteBalanceLoc_ >= 0) glUniform1f(uWhiteBalanceLoc_, params_.whiteBalance);
    if (uTintLoc_         >= 0) glUniform1f(uTintLoc_,         params_.tint);
    if (uHslRedLoc_    >= 0) glUniform3fv(uHslRedLoc_,    1, &params_.hsl[0]);
    if (uHslOrangeLoc_ >= 0) glUniform3fv(uHslOrangeLoc_, 1, &params_.hsl[3]);
    if (uHslYellowLoc_ >= 0) glUniform3fv(uHslYellowLoc_, 1, &params_.hsl[6]);
    if (uHslGreenLoc_  >= 0) glUniform3fv(uHslGreenLoc_,  1, &params_.hsl[9]);
    if (uHslAquaLoc_   >= 0) glUniform3fv(uHslAquaLoc_,   1, &params_.hsl[12]);
    if (uHslBlueLoc_   >= 0) glUniform3fv(uHslBlueLoc_,   1, &params_.hsl[15]);
    if (uHslYellowGreenLoc_ >= 0) glUniform3fv(uHslYellowGreenLoc_, 1, &params_.hsl2[0]);
    if (uHslSpringGreenLoc_ >= 0) glUniform3fv(uHslSpringGreenLoc_, 1, &params_.hsl2[3]);
    if (uHslSkyBlueLoc_     >= 0) glUniform3fv(uHslSkyBlueLoc_,     1, &params_.hsl2[6]);
    if (uHslPurpleLoc_      >= 0) glUniform3fv(uHslPurpleLoc_,      1, &params_.hsl2[9]);
    if (uHslMagentaLoc_     >= 0) glUniform3fv(uHslMagentaLoc_,     1, &params_.hsl2[12]);
    if (uHslPinkLoc_        >= 0) glUniform3fv(uHslPinkLoc_,        1, &params_.hsl2[15]);
    if (uDitherLoc_      >= 0) glUniform1f(uDitherLoc_,     params_.ditherStrength);
    if (uPurpleFringeLoc_ >= 0) glUniform1i(uPurpleFringeLoc_, (int)params_.purpleFringeMode);
    // uLutEnabled is the LOGICAL-AND of the param flag and whether a LUT
    // texture is actually uploaded. Prevents the shader from sampling unit
    // 1 (which is 0 → all-black output) when no LUT is set.
    const bool effLut = (params_.lutEnabled > 0.5f) && lutUploaded_;
    if (uLutEnabledLoc_  >= 0) glUniform1i(uLutEnabledLoc_, effLut ? 1 : 0);
    if (uLutIntensityLoc_ >= 0) glUniform1f(uLutIntensityLoc_, params_.lutIntensity);
    if (uLutBwForceLoc_ >= 0) glUniform1i(uLutBwForceLoc_, params_.lutBwForce);
    if (uLutSizeLoc_     >= 0) glUniform1f(uLutSizeLoc_, float(lutSize_));
    if (uLutDomainMinLoc_ >= 0) glUniform3fv(uLutDomainMinLoc_, 1, lutDomainMin_);
    if (uLutDomainMaxLoc_ >= 0) glUniform3fv(uLutDomainMaxLoc_, 1, lutDomainMax_);
    if (uLutHighlightVibrancyLoc_ >= 0) glUniform1f(uLutHighlightVibrancyLoc_, params_.lutHighlightVibrancy);
    if (uHighlightTemperatureLoc_ >= 0) glUniform1f(uHighlightTemperatureLoc_, params_.highlightTemperature);
    if (uHighlightTintLoc_        >= 0) glUniform1f(uHighlightTintLoc_,        params_.highlightTint);
    if (uShadowTemperatureLoc_    >= 0) glUniform1f(uShadowTemperatureLoc_,    params_.shadowTemperature);
    if (uShadowTintLoc_           >= 0) glUniform1f(uShadowTintLoc_,           params_.shadowTint);
    if (uAmbianceLoc_       >= 0) glUniform1f(uAmbianceLoc_,       params_.ambiance);
    if (uOrtonStrengthLoc_  >= 0) glUniform1f(uOrtonStrengthLoc_,  params_.ortonStrength);
    if (uBloomRadiusLoc_    >= 0) glUniform1f(uBloomRadiusLoc_,    params_.bloomRadius);
    if (uBloomShapeLoc_     >= 0) glUniform1f(uBloomShapeLoc_,     params_.bloomShape);
    if (uFilmRolloffLoc_    >= 0) glUniform1f(uFilmRolloffLoc_,    params_.filmRolloff);
    if (uFilmicLumaLoc_     >= 0) glUniform1f(uFilmicLumaLoc_,     params_.filmicLuma);
    if (uOklabHlChromaLoc_  >= 0) glUniform1f(uOklabHlChromaLoc_,  params_.oklabHlChroma);
    // Film Response (Tone Highlights Recovery / Fill Light / GrayMixer).
    // Export already sets these in pushGradingUniforms; live preview must too.
    if (uFilmRecoveryLoc_   >= 0) glUniform1f(uFilmRecoveryLoc_,   params_.filmRecovery);
    if (uFilmFillLightLoc_  >= 0) glUniform1f(uFilmFillLightLoc_,  params_.filmFillLight);
    if (uFilmMonochromeLoc_ >= 0) glUniform1i(uFilmMonochromeLoc_, params_.filmMonochrome);
    if (uFilmGrayMixLoc_    >= 0) glUniform1fv(uFilmGrayMixLoc_, 8, params_.filmGrayMix);
    if (uMistTightnessLoc_  >= 0) glUniform1f(uMistTightnessLoc_,  params_.mistTightness);
    if (uMistHalationLoc_   >= 0) glUniform1f(uMistHalationLoc_,   params_.mistHalation);
    if (uOpticalSpreadLoc_    >= 0) glUniform1f(uOpticalSpreadLoc_,    params_.opticalSpread);
    if (uOpticalHalationLoc_  >= 0) glUniform1f(uOpticalHalationLoc_,  params_.opticalHalation);
    if (uOpticalDirectionLoc_ >= 0) glUniform1f(uOpticalDirectionLoc_, params_.opticalDirection);
    if (uHighlightStartLoc_ >= 0) glUniform1f(uHighlightStartLoc_, params_.highlightStart > 0.f ? params_.highlightStart : 0.78f);
    if (uHighlightEndLoc_ >= 0) glUniform1f(uHighlightEndLoc_, params_.highlightEnd > params_.highlightStart ? params_.highlightEnd : 0.98f);
    if (uOpticalDensityLoc_   >= 0) {
        const int longSide = std::max(texW_, texH_);
        glUniform1f(uOpticalDensityLoc_, opticalSpreadDensity(longSide > 0 ? longSide : 2048));
    }
    if (uGamutCompressLoc_  >= 0) glUniform1f(uGamutCompressLoc_,  params_.gamutCompress);
    if (uCgGlobalTintLoc_ >= 0)
        glUniform3f(uCgGlobalTintLoc_,
                    params_.cgGlobalR, params_.cgGlobalG, params_.cgGlobalB);
    if (uCgGlobalSatLoc_ >= 0) glUniform1f(uCgGlobalSatLoc_, params_.cgGlobalSat);
    if (uClarityLiftLoc_ >= 0) glUniform1f(uClarityLiftLoc_, params_.clarityLift);
    if (uLensFlareXLoc_          >= 0) glUniform1f(uLensFlareXLoc_,          params_.lensFlareX);
    if (uLensFlareYLoc_          >= 0) glUniform1f(uLensFlareYLoc_,          params_.lensFlareY);
    if (uLensFlareBrightnessLoc_ >= 0) glUniform1f(uLensFlareBrightnessLoc_, params_.lensFlareBrightness);
    if (uLensFlareSizeLoc_       >= 0) glUniform1f(uLensFlareSizeLoc_,       params_.lensFlareSize);
    if (uLensFlareSpreadLoc_     >= 0) glUniform1f(uLensFlareSpreadLoc_,     params_.lensFlareSpread);
    if (uLensFlareWarmthLoc_     >= 0) glUniform1f(uLensFlareWarmthLoc_,     params_.lensFlareWarmth);
    if (uLensFlareDistanceLoc_   >= 0) glUniform1f(uLensFlareDistanceLoc_,   params_.lensFlareDistance);
    if (uLensFlareHoodLoc_       >= 0) glUniform1f(uLensFlareHoodLoc_,       params_.lensFlareHood);
    if (uSceneDistanceLoc_       >= 0) glUniform1f(uSceneDistanceLoc_,       params_.sceneDistance);
    if (uShadowStrengthLoc_      >= 0) glUniform1f(uShadowStrengthLoc_,      params_.shadowStrength);
    if (uShadowSoftnessLoc_      >= 0) glUniform1f(uShadowSoftnessLoc_,      params_.shadowSoftness);
    if (uStarburstLoc_           >= 0) glUniform1f(uStarburstLoc_,           params_.starburst);
    if (uIrisBladesLoc_          >= 0) glUniform1f(uIrisBladesLoc_,          params_.irisBlades);
    if (uIrisRotationLoc_        >= 0) glUniform1f(uIrisRotationLoc_,        params_.irisRotation);
    if (uIrisRoundnessLoc_       >= 0) glUniform1f(uIrisRoundnessLoc_,       params_.irisRoundness);
    if (uShadowAspectLoc_        >= 0) {
        float aspect = (texH_ > 0) ? float(texW_) / float(texH_) : 1.f;
        glUniform1f(uShadowAspectLoc_, aspect);
    }
    if (uColorShiftRedXLoc_      >= 0) glUniform1f(uColorShiftRedXLoc_,      params_.colorShiftRedX);
    if (uColorShiftGreenXLoc_    >= 0) glUniform1f(uColorShiftGreenXLoc_,    params_.colorShiftGreenX);
    if (uColorShiftBlueXLoc_     >= 0) glUniform1f(uColorShiftBlueXLoc_,     params_.colorShiftBlueX);
    if (uBloomExcludeSubjectLoc_ >= 0)
        glUniform1f(uBloomExcludeSubjectLoc_, params_.bloomExcludeSubject);
    if (uSubjectBloomLoc_ >= 0)
        glUniform1f(uSubjectBloomLoc_, params_.subjectBloom);
    if (uCgShadowsTintLoc_ >= 0)
        glUniform3f(uCgShadowsTintLoc_,
                    params_.cgShadowsR, params_.cgShadowsG, params_.cgShadowsB);
    if (uCgShadowsSatLoc_ >= 0)  glUniform1f(uCgShadowsSatLoc_,  params_.cgShadowsSat);
    if (uCgMidtonesTintLoc_ >= 0)
        glUniform3f(uCgMidtonesTintLoc_,
                    params_.cgMidtonesR, params_.cgMidtonesG, params_.cgMidtonesB);
    if (uCgMidtonesSatLoc_ >= 0) glUniform1f(uCgMidtonesSatLoc_, params_.cgMidtonesSat);
    if (uCgHighlightsTintLoc_ >= 0)
        glUniform3f(uCgHighlightsTintLoc_,
                    params_.cgHighlightsR, params_.cgHighlightsG, params_.cgHighlightsB);
    if (uCgHighlightsSatLoc_ >= 0) glUniform1f(uCgHighlightsSatLoc_, params_.cgHighlightsSat);
    if (uClarityAmountLoc_ >= 0) glUniform1f(uClarityAmountLoc_, params_.clarityAmount);
    if (uCenterPopLoc_     >= 0) glUniform1f(uCenterPopLoc_,     params_.centerPop);
    if (uWorkspaceSpaceLoc_   >= 0) glUniform1i(uWorkspaceSpaceLoc_,   params_.workspaceSpace);
    if (uLutAuthoredSpaceLoc_ >= 0) glUniform1i(uLutAuthoredSpaceLoc_, params_.lutAuthoredSpace);
    if (uLightTabOpacityLoc_ >= 0) glUniform1f(uLightTabOpacityLoc_, params_.lightTabOpacity);
    if (uColorTabOpacityLoc_ >= 0) glUniform1f(uColorTabOpacityLoc_, params_.colorTabOpacity);
    if (uXmpTabOpacityLoc_   >= 0) glUniform1f(uXmpTabOpacityLoc_,   params_.xmpTabOpacity);
    if (uDehazeLoc_          >= 0) glUniform1f(uDehazeLoc_,          params_.dehaze);
    if (uVigAmountLoc_       >= 0) glUniform1f(uVigAmountLoc_,       params_.vigAmount);
    if (uVigCenterLoc_       >= 0) glUniform2f(uVigCenterLoc_,       params_.vigCenterX, params_.vigCenterY);
    if (uVigFeatherLoc_      >= 0) glUniform1f(uVigFeatherLoc_,      params_.vigFeather);
    if (uVigIntensityLoc_    >= 0) glUniform1f(uVigIntensityLoc_,    params_.vigIntensity);
    if (uVigEffectLoc_       >= 0) glUniform1i(uVigEffectLoc_,       int(params_.vigEffect));
    if (uVigTabOpacityLoc_   >= 0) glUniform1f(uVigTabOpacityLoc_,   params_.vigTabOpacity);
    if (uGradAngleLoc_       >= 0) glUniform1f(uGradAngleLoc_,       params_.gradAngle);
    if (uGradTopLoc_         >= 0) glUniform1fv(uGradTopLoc_,    15, params_.gradTop);
    if (uGradBottomLoc_      >= 0) glUniform1fv(uGradBottomLoc_, 15, params_.gradBottom);
    if (uGradLeftLoc_        >= 0) glUniform1fv(uGradLeftLoc_,   15, params_.gradLeft);
    if (uGradRightLoc_       >= 0) glUniform1fv(uGradRightLoc_,  15, params_.gradRight);
    if (uGradTabOpacityLoc_  >= 0) glUniform1f(uGradTabOpacityLoc_, params_.gradTabOpacity);
    if (uSubjectMaskEnabledLoc_ >= 0) glUniform1i(uSubjectMaskEnabledLoc_, subjectMaskReady_ ? 1 : 0);
    if (uSubjectMaskRectLoc_    >= 0) glUniform4fv(uSubjectMaskRectLoc_, 1, subjectMaskRect_);
    if (uGradTopApplyToLoc_     >= 0) glUniform1i(uGradTopApplyToLoc_,    int(params_.gradTopApplyTo));
    if (uGradBottomApplyToLoc_  >= 0) glUniform1i(uGradBottomApplyToLoc_, int(params_.gradBottomApplyTo));
    if (uGradLeftApplyToLoc_    >= 0) glUniform1i(uGradLeftApplyToLoc_,   int(params_.gradLeftApplyTo));
    if (uGradRightApplyToLoc_   >= 0) glUniform1i(uGradRightApplyToLoc_,  int(params_.gradRightApplyTo));
    if (uGradTopBlendModeLoc_    >= 0) glUniform1i(uGradTopBlendModeLoc_,    int(params_.gradTopBlendMode));
    if (uGradBottomBlendModeLoc_ >= 0) glUniform1i(uGradBottomBlendModeLoc_, int(params_.gradBottomBlendMode));
    if (uGradLeftBlendModeLoc_   >= 0) glUniform1i(uGradLeftBlendModeLoc_,   int(params_.gradLeftBlendMode));
    if (uGradRightBlendModeLoc_  >= 0) glUniform1i(uGradRightBlendModeLoc_,  int(params_.gradRightBlendMode));
    // Mask tab — bitfield of active layers + per-layer adjustment arrays.
    // The shader loops 0..3, gating each layer by bit i of the enabled mask.
    int maskBits = 0;
    float mBright[kMaskLayers], mCont[kMaskLayers], mTemp[kMaskLayers];
    float mTint[kMaskLayers], mSat[kMaskLayers], mClar[kMaskLayers], mOpac[kMaskLayers];
    float mSharp[kMaskLayers];
    float mHi[kMaskLayers], mShd[kMaskLayers], mWht[kMaskLayers], mBlk[kMaskLayers];
    float mLumTgt[kMaskLayers], mLumSpr[kMaskLayers], mLumFth[kMaskLayers];
    int   mLumCmb[kMaskLayers];
    for (int i = 0; i < kMaskLayers; ++i) {
        if (brushMaskReady_[i]) maskBits |= (1 << i);
        mBright[i] = params_.maskLayer[i].brightness;
        mSharp[i]  = params_.maskLayer[i].sharpness;
        mCont[i]   = params_.maskLayer[i].contrast;
        mTemp[i]   = params_.maskLayer[i].temperature;
        mTint[i]   = params_.maskLayer[i].tint;
        mSat[i]    = params_.maskLayer[i].saturation;
        mClar[i]   = params_.maskLayer[i].clarity;
        mHi[i]     = params_.maskLayer[i].highlights;
        mShd[i]    = params_.maskLayer[i].shadows;
        mWht[i]    = params_.maskLayer[i].whites;
        mBlk[i]    = params_.maskLayer[i].blacks;
        mOpac[i]   = params_.maskLayer[i].opacity;
        mLumTgt[i] = params_.maskLayer[i].lumTarget;
        mLumSpr[i] = params_.maskLayer[i].lumSpread;
        mLumFth[i] = params_.maskLayer[i].lumFeather;
        mLumCmb[i] = params_.maskLayer[i].lumCombine;
    }
    if (uBrushMaskEnabledLoc_   >= 0) glUniform1i(uBrushMaskEnabledLoc_,  maskBits);
    if (uShowMaskOverlayLoc_    >= 0) glUniform1i(uShowMaskOverlayLoc_,   showMaskOverlay_ ? 1 : 0);
    if (uMaskOverlayLayerLoc_   >= 0) glUniform1i(uMaskOverlayLayerLoc_,  maskOverlayLayer_);
    if (uMaskBrightnessLoc_     >= 0) glUniform1fv(uMaskBrightnessLoc_,   kMaskLayers, mBright);
    if (uMaskContrastLoc_       >= 0) glUniform1fv(uMaskContrastLoc_,     kMaskLayers, mCont);
    if (uMaskTemperatureLoc_    >= 0) glUniform1fv(uMaskTemperatureLoc_,  kMaskLayers, mTemp);
    if (uMaskTintLoc_           >= 0) glUniform1fv(uMaskTintLoc_,         kMaskLayers, mTint);
    if (uMaskSaturationLoc_     >= 0) glUniform1fv(uMaskSaturationLoc_,   kMaskLayers, mSat);
    if (uMaskClarityLoc_        >= 0) glUniform1fv(uMaskClarityLoc_,      kMaskLayers, mClar);
    if (uMaskSharpnessLoc_      >= 0) glUniform1fv(uMaskSharpnessLoc_,    kMaskLayers, mSharp);
    if (uMaskHighlightsLoc_     >= 0) glUniform1fv(uMaskHighlightsLoc_,   kMaskLayers, mHi);
    if (uMaskShadowsLoc_        >= 0) glUniform1fv(uMaskShadowsLoc_,      kMaskLayers, mShd);
    if (uMaskWhitesLoc_         >= 0) glUniform1fv(uMaskWhitesLoc_,       kMaskLayers, mWht);
    if (uMaskBlacksLoc_         >= 0) glUniform1fv(uMaskBlacksLoc_,       kMaskLayers, mBlk);
    if (uMaskTabOpacityLoc_     >= 0) glUniform1fv(uMaskTabOpacityLoc_,   kMaskLayers, mOpac);
    if (uMaskLumTargetLoc_      >= 0) glUniform1fv(uMaskLumTargetLoc_,    kMaskLayers, mLumTgt);
    if (uMaskLumSpreadLoc_      >= 0) glUniform1fv(uMaskLumSpreadLoc_,    kMaskLayers, mLumSpr);
    if (uMaskLumFeatherLoc_     >= 0) glUniform1fv(uMaskLumFeatherLoc_,   kMaskLayers, mLumFth);
    if (uMaskLumCombineLoc_     >= 0) glUniform1iv(uMaskLumCombineLoc_,   kMaskLayers, mLumCmb);
    // Sampler units for each mask layer: 0→unit3, 1→unit5, 2→unit6, 3→unit7.
    static const int kMaskUnits[kMaskLayers] = {3, 5, 6, 7};
    for (int i = 0; i < kMaskLayers; ++i) {
        if (uBrushMaskLoc_[i] >= 0) glUniform1i(uBrushMaskLoc_[i], kMaskUnits[i]);
    }
    if (uTonemapExposureLoc_    >= 0) glUniform1f(uTonemapExposureLoc_,   params_.tonemapExposure);
    if (uTonemapHighlightsLoc_  >= 0) glUniform1f(uTonemapHighlightsLoc_, params_.tonemapHighlights);
    if (uTonemapShadowsLoc_     >= 0) glUniform1f(uTonemapShadowsLoc_,    params_.tonemapShadows);
    // uFilmicHlProtect: dead slot — never read; skip upload (see grading_uniforms.h).
    if (uEdgeSnapStrengthLoc_   >= 0) glUniform1f(uEdgeSnapStrengthLoc_,
        sobelEdgeReady_ ? edgeSnapStrength_ : 0.f);
    if (uEdgeSnapThresholdLoc_  >= 0) glUniform1f(uEdgeSnapThresholdLoc_, edgeSnapThreshold_);
    if (uGamutOutLoc_             >= 0) glUniform1i(uGamutOutLoc_,             int(params_.gamutOut));

    // Bokeh (sampler bound to unit 8 in renderFrame / snapshot paths).
    if (uBlurTexLoc_     >= 0) glUniform1i(uBlurTexLoc_,    8);
    if (uBokehBlurLoc_   >= 0) glUniform1f(uBokehBlurLoc_,   params_.bokehBlur);
    if (uBokehBallsLoc_  >= 0) glUniform1f(uBokehBallsLoc_,  params_.bokehBalls);
    if (uMaskBandingLoc_ >= 0) glUniform1f(uMaskBandingLoc_, params_.maskBanding);
    if (uBokehSpreadLoc_ >= 0) glUniform1f(uBokehSpreadLoc_, params_.bokehSpread);

    // Tone Curve LUT (sampler bound to unit 9 in renderFrame / snapshot).
    if (uToneCurveTexLoc_     >= 0) glUniform1i(uToneCurveTexLoc_, 9);
    if (uToneCurveEnabledLoc_ >= 0) glUniform1i(uToneCurveEnabledLoc_, toneCurveReady_ ? 1 : 0);
    if (uToneCurveLumaModeLoc_ >= 0) glUniform1i(uToneCurveLumaModeLoc_, params_.toneCurveLumaMode > 0.5f ? 1 : 0);
    if (uFilmHighlightKneeLoc_ >= 0) glUniform1f(uFilmHighlightKneeLoc_, params_.filmHighlightKnee);

    // Film grain (cinematic 3D noise — procedural).
    if (uFilmGrainLoc_     >= 0) glUniform1f(uFilmGrainLoc_,     params_.filmGrain);
    if (uFilmGrainSizeLoc_ >= 0) glUniform1f(uFilmGrainSizeLoc_, params_.filmGrainSize);
    if (uFilmGrainWashLoc_ >= 0) glUniform1f(uFilmGrainWashLoc_, params_.filmGrainWash);
    if (uGrainExLoc_ >= 0) glUniform1fv(uGrainExLoc_, 13, params_.grainEx);
    if (uGrainSeedLoc_     >= 0) glUniform1f(uGrainSeedLoc_,     kGrainSeed);
    // Grain reference grid: a FIXED resolution (aspect-matched) shared with the
    // Stage C export so preview + file sample identical noise coords.
    if (uImageSizeLoc_ >= 0) {
        const float REF = 2048.f;
        float aspect = (texH_ > 0) ? float(texW_) / float(texH_) : 1.f;
        glUniform2f(uImageSizeLoc_, REF * aspect, REF);
    }

    // Haxademic film grain extension (Req 8, slots 375–378).
    if (locHaxGrainCrossfade_ >= 0) glUniform1f(locHaxGrainCrossfade_, params_.haxGrainCrossfade);
    if (locHaxGrainScale_     >= 0) glUniform1f(locHaxGrainScale_,     params_.haxGrainScale);
    if (locHaxGrainLumaAmp_   >= 0) glUniform1f(locHaxGrainLumaAmp_,   params_.haxGrainLumaAmp);
    if (locHaxGrainChromaAmp_ >= 0) glUniform1f(locHaxGrainChromaAmp_, params_.haxGrainChromaAmp);

    // M5.5 — XMP overlay block.
    if (uXmpEnabledLoc_    >= 0) glUniform1i(uXmpEnabledLoc_,    params_.xmpEnabled > 0.5f ? 1 : 0);
    if (uXmpExposureLoc_   >= 0) glUniform1f(uXmpExposureLoc_,   params_.xmpExposure);
    if (uXmpContrastLoc_   >= 0) glUniform1f(uXmpContrastLoc_,   params_.xmpContrast);
    if (uXmpHighlightsLoc_ >= 0) glUniform1f(uXmpHighlightsLoc_, params_.xmpHighlights);
    if (uXmpShadowsLoc_    >= 0) glUniform1f(uXmpShadowsLoc_,    params_.xmpShadows);
    if (uXmpWhitesLoc_     >= 0) glUniform1f(uXmpWhitesLoc_,     params_.xmpWhites);
    if (uXmpBlacksLoc_     >= 0) glUniform1f(uXmpBlacksLoc_,     params_.xmpBlacks);
    if (uXmpHslRedLoc_    >= 0) glUniform3fv(uXmpHslRedLoc_,    1, &params_.xmpHsl[0]);
    if (uXmpHslOrangeLoc_ >= 0) glUniform3fv(uXmpHslOrangeLoc_, 1, &params_.xmpHsl[3]);
    if (uXmpHslYellowLoc_ >= 0) glUniform3fv(uXmpHslYellowLoc_, 1, &params_.xmpHsl[6]);
    if (uXmpHslGreenLoc_  >= 0) glUniform3fv(uXmpHslGreenLoc_,  1, &params_.xmpHsl[9]);
    if (uXmpHslAquaLoc_   >= 0) glUniform3fv(uXmpHslAquaLoc_,   1, &params_.xmpHsl[12]);
    if (uXmpHslBlueLoc_   >= 0) glUniform3fv(uXmpHslBlueLoc_,   1, &params_.xmpHsl[15]);

    // PREQ-Port uniform push
    if (uHslFullLoc_ >= 0) glUniform3fv(uHslFullLoc_, 8, params_.hslFull);
    if (uCurvesEnabledLoc_ >= 0) {
        // Detect identity: check if all control points match (i/14) within epsilon
        int nonIdentity = 0;
        for (int i = 0; i < 16; i++) {
            float id = (i / 2) / 7.f;
            if (fabsf(params_.curveMaster[i] - id) > 0.002f ||
                fabsf(params_.curveR[i]      - id) > 0.002f ||
                fabsf(params_.curveG[i]      - id) > 0.002f ||
                fabsf(params_.curveB[i]      - id) > 0.002f) { nonIdentity = 1; break; }
        }
        glUniform1i(uCurvesEnabledLoc_, nonIdentity);
    }
    // Bind curve LUT textures to units 8-11
    if (curveMasterTex_ && uCurveMasterTexLoc_ >= 0) {
        glActiveTexture(GL_TEXTURE12); glBindTexture(GL_TEXTURE_2D, curveMasterTex_);
        glUniform1i(uCurveMasterTexLoc_, 12);
    }
    if (curveRTex_ && uCurveRTexLoc_ >= 0) {
        glActiveTexture(GL_TEXTURE13); glBindTexture(GL_TEXTURE_2D, curveRTex_);
        glUniform1i(uCurveRTexLoc_, 13);
    }
    if (curveGTex_ && uCurveGTexLoc_ >= 0) {
        glActiveTexture(GL_TEXTURE14); glBindTexture(GL_TEXTURE_2D, curveGTex_);
        glUniform1i(uCurveGTexLoc_, 14);
    }
    if (curveBTex_ && uCurveBTexLoc_ >= 0) {
        glActiveTexture(GL_TEXTURE15); glBindTexture(GL_TEXTURE_2D, curveBTex_);
        glUniform1i(uCurveBTexLoc_, 15);
    }
    if (uDetailGrainRoughnessLoc_ >= 0) glUniform1f(uDetailGrainRoughnessLoc_, params_.detailGrainRoughness);
    if (uDetailSharpenMaskLoc_    >= 0) glUniform1f(uDetailSharpenMaskLoc_,    params_.detailSharpenMask);
    if (uColorDensityLoc_  >= 0) glUniform1f(uColorDensityLoc_,  params_.colorDensity);
    if (uFilmSeparationLoc_ >= 0) glUniform1f(uFilmSeparationLoc_, params_.filmSeparation);
    if (uSkintoneLoc_      >= 0) glUniform3f(uSkintoneLoc_, params_.skintoneWarm, params_.skintoneSmooth, params_.skintoneLuma);
    if (uMidtoneDetailsLoc_>= 0) glUniform1f(uMidtoneDetailsLoc_, params_.midtoneDetails);
    // uLowFreqMid sampler removed (exceeds 16-unit limit)
    if (uPushPullLoc_          >= 0) glUniform1f(uPushPullLoc_,          params_.pushPull);
    if (uLutColorDensityLoc_   >= 0) glUniform1f(uLutColorDensityLoc_,   params_.lutColorDensity);
    if (uLutSkintoneBalanceLoc_>= 0) glUniform1f(uLutSkintoneBalanceLoc_,params_.lutSkintoneBalance);
    if (uAberStrengthLoc_      >= 0) glUniform1f(uAberStrengthLoc_,      params_.aberStrength);
    if (uAberFringeReduceLoc_  >= 0) glUniform1f(uAberFringeReduceLoc_,  params_.aberFringeReduce);
    if (uFxBlurStyleLoc_       >= 0) glUniform1i(uFxBlurStyleLoc_, (int)params_.fxBlurStyle);
    if (uFxGaussBlurLoc_       >= 0) glUniform1f(uFxGaussBlurLoc_,       params_.fxGaussBlur);
    if (uFxDirBlurAmtLoc_      >= 0) glUniform1f(uFxDirBlurAmtLoc_,      params_.fxDirBlurAmt);
    if (uFxDirBlurAngleLoc_    >= 0) glUniform1f(uFxDirBlurAngleLoc_,    params_.fxDirBlurAngle);
    if (uFxRadBlurAmtLoc_      >= 0) glUniform1f(uFxRadBlurAmtLoc_,      params_.fxRadBlurAmt);
    if (uFxRadBlurCenterLoc_   >= 0) glUniform2f(uFxRadBlurCenterLoc_,   params_.fxRadBlurCx, params_.fxRadBlurCy);
    if (uFxZoomBlurAmtLoc_     >= 0) glUniform1f(uFxZoomBlurAmtLoc_,     params_.fxZoomBlurAmt);
    if (uFxZoomBlurCenterLoc_  >= 0) glUniform2f(uFxZoomBlurCenterLoc_,  params_.fxZoomBlurCx, params_.fxZoomBlurCy);
    if (uFxBlurExcludeSubjectLoc_>= 0) glUniform1f(uFxBlurExcludeSubjectLoc_, params_.fxBlurExcludeSubject);
    if (uFxMistLoc_            >= 0) glUniform1f(uFxMistLoc_,            params_.fxMist);
    if (uFxMistWarmthLoc_      >= 0) glUniform1f(uFxMistWarmthLoc_,      params_.fxMistWarmth);
    if (uFxDustLoc_            >= 0) glUniform1f(uFxDustLoc_,            params_.fxDust);
    if (uFxDustSizeLoc_        >= 0) glUniform1f(uFxDustSizeLoc_,        params_.fxDustSize);
    if (uFxVintageStrengthLoc_ >= 0) glUniform1f(uFxVintageStrengthLoc_, params_.fxVintageStrength);
    if (uFxVintageFadeLoc_     >= 0) glUniform1f(uFxVintageFadeLoc_,     params_.fxVintageFade);
    if (uFxVintageVigLoc_      >= 0) glUniform1f(uFxVintageVigLoc_,      params_.fxVintageVig);
    if (uFxVintageMistIntensityLoc_ >= 0) glUniform1f(uFxVintageMistIntensityLoc_, params_.fxVintageMistIntensity);
    if (uFxVintageMistScaleLoc_     >= 0) glUniform1f(uFxVintageMistScaleLoc_,     params_.fxVintageMistScale);
    if (uFxVintageTextureIntensityLoc_ >= 0) glUniform1f(uFxVintageTextureIntensityLoc_, params_.fxVintageTextureIntensity);
    if (uFxVintageTextureScaleLoc_     >= 0) glUniform1f(uFxVintageTextureScaleLoc_,     params_.fxVintageTextureScale);
    if (uFxGlowStrengthLoc_    >= 0) glUniform1f(uFxGlowStrengthLoc_,    params_.fxGlowStrength);
    if (uFxGlowSpreadLoc_      >= 0) glUniform1f(uFxGlowSpreadLoc_,      params_.fxGlowSpread);
    if (uFxGlowWarmthLoc_      >= 0) glUniform1f(uFxGlowWarmthLoc_,      params_.fxGlowWarmth);
    if (uSmartColorEnhanceLoc_ >= 0) glUniform1f(uSmartColorEnhanceLoc_, params_.smartColorEnhance);
    if (uSmartWbMinLoc_        >= 0) glUniform3f(uSmartWbMinLoc_,        params_.smartWbRMin, params_.smartWbGMin, params_.smartWbBMin);
    if (uSmartWbMaxLoc_        >= 0) glUniform3f(uSmartWbMaxLoc_,        params_.smartWbRMax, params_.smartWbGMax, params_.smartWbBMax);
    // uFxBlurTex sampler removed (exceeds 16-unit limit)
}

// Thin forwarder. The 133 glUniform calls now live in
// v3/grading_uniforms.cpp so the headless/ANGLE export path pushes the SAME
// uniforms in the SAME order as this preview path (hard rule #1). This
// renderer still owns all GL state; only the parameter->uniform mapping is
// shared. Do not re-inline the calls here.
void GlesRenderer::pushUniformsForProgram(unsigned int prog) {
    GradingInputs in;
    in.texW              = texW_;
    in.texH              = texH_;
    in.lutUploaded       = lutUploaded_;
    in.lutSize           = lutSize_;
    in.subjectMaskReady  = subjectMaskReady_;
    in.sobelEdgeReady    = sobelEdgeReady_;
    in.edgeSnapStrength  = edgeSnapStrength_;
    in.edgeSnapThreshold = edgeSnapThreshold_;
    in.toneCurveReady    = toneCurveReady_;
    for (int i = 0; i < 3; ++i) {
        in.lutDomainMin[i] = lutDomainMin_[i];
        in.lutDomainMax[i] = lutDomainMax_[i];
    }
    for (int i = 0; i < 4; ++i) in.subjectMaskRect[i] = subjectMaskRect_[i];
    for (int i = 0; i < ShaderParams::kMaskLayers; ++i)
        in.brushMaskReady[i] = brushMaskReady_[i];
    pushGradingUniforms(prog, params_, in);
}

void GlesRenderer::assertGlThread(const char* what) {
    if (glThreadTid_ < 0) return;   // context not created yet — nothing to compare
    pid_t tid = gettid();
    if (tid != glThreadTid_) {
        LOGE("%s: called off the GL thread (tid=%d, glTid=%d) — eglMakeCurrent "
             "here steals the context from the render loop mid-frame. Post to "
             "RawV3GlSurfaceView's renderHandler instead.", what, (int) tid,
             (int) glThreadTid_);
    }
}

void GlesRenderer::waitOnProducerFence(int fenceFd) {
    if (fenceFd < 0) return;
    if (!resolveFenceExtensions()) {
        LOGE("waitOnProducerFence: EGL sync/fence extensions missing — sampling "
             "the AHB without a producer wait (fd=%d)", fenceFd);
        return;
    }
    // POSIX dup: eglCreateSyncKHR(EGL_SYNC_NATIVE_FENCE_ANDROID) takes
    // ownership of the fd we pass. eglDupNativeFenceFDANDROID is the inverse
    // (sync → fd) and must not be used here.
    int dupFd = dup(fenceFd);
    if (dupFd < 0) {
        LOGE("waitOnProducerFence: dup(fd=%d) failed errno=%d", fenceFd, errno);
        return;
    }
    const EGLint attrs[] = { EGL_SYNC_NATIVE_FENCE_FD_ANDROID, dupFd, EGL_NONE };
    EGLSyncKHR sync = fn_eglCreateSyncKHR(display_, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs);
    if (sync == EGL_NO_SYNC_KHR) {
        LOGE("waitOnProducerFence: eglCreateSyncKHR failed err=0x%x", eglGetError());
        close(dupFd);   // createSync takes ownership only on success
        return;
    }
    // eglCreateSyncKHR consumed dupFd (even on EGLSyncKHR success we no longer
    // own it). GPU-side wait: inserts a pipeline barrier, never stalls the CPU.
    if (fn_eglWaitSyncKHR(display_, sync, 0) != EGL_TRUE) {
        LOGE("waitOnProducerFence: eglWaitSyncKHR failed err=0x%x", eglGetError());
    }
    fn_eglDestroySyncKHR(display_, sync);
}

bool GlesRenderer::importAhbToTexture(AHardwareBuffer* ahb, GLuint& tex, int& tw, int& th,
                                      EGLImageKHR& img, AHardwareBuffer*& owner,
                                      uint32_t fmt0, uint32_t fmt1,
                                      int fenceFd, GLenum target) {
    if (!ahb || !resolveExtensions()) return false;
    assertGlThread("importAhbToTexture");

    // Validate BEFORE import: an AHB in an unexpected format imports
    // "successfully" and then samples garbage — fail loudly instead.
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    if (desc.format != fmt0 && (fmt1 == 0 || desc.format != fmt1)) {
        LOGE("importAhbToTexture: rejecting AHB — format 0x%x not in allowed "
             "{0x%x%s0x%x} (%ux%u layers=%u usage=0x%" PRIx64 ")",
             desc.format, fmt0, fmt1 ? ", " : "", fmt1,
             desc.width, desc.height, desc.layers, desc.usage);
        return false;
    }

    waitOnProducerFence(fenceFd);

    EGLClientBuffer clientBuf = fn_eglGetNativeClientBufferANDROID(ahb);
    if (!clientBuf) {
        LOGE("importAhbToTexture: eglGetNativeClientBufferANDROID null");
        return false;
    }

    EGLint imageAttribs[] = {
        EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
        EGL_NONE,
    };
    EGLImageKHR image = fn_eglCreateImageKHR(
        display_, EGL_NO_CONTEXT,
        EGL_NATIVE_BUFFER_ANDROID, clientBuf, imageAttribs);
    if (image == EGL_NO_IMAGE_KHR) {
        LOGE("importAhbToTexture: eglCreateImageKHR failed err=0x%x", eglGetError());
        return false;
    }

    // Hold a native ref on the buffer for the EGLImage's lifetime. The EGL
    // image does NOT own the AHB; Java's HardwareBuffer.close() can otherwise
    // free it under the driver (UAF).
    AHardwareBuffer_acquire(ahb);
    if (img != EGL_NO_IMAGE_KHR) {
        fn_eglDestroyImageKHR(display_, img);
        if (owner) AHardwareBuffer_release(owner);
    }
    img = image;
    owner = ahb;

    if (!tex) glGenTextures(1, &tex);
    glBindTexture(target, tex);
    glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(target, GL_TEXTURE_WRAP_S,     GL_CLAMP_TO_EDGE);
    glTexParameteri(target, GL_TEXTURE_WRAP_T,     GL_CLAMP_TO_EDGE);
    fn_glEGLImageTargetTexture2DOES(target, (GLeglImageOES) image);

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("importAhbToTexture: glEGLImageTargetTexture2DOES err=0x%x", err);
        fn_eglDestroyImageKHR(display_, image);
        img = EGL_NO_IMAGE_KHR;
        owner = nullptr;
        AHardwareBuffer_release(ahb);
        return false;
    }

    tw = int(desc.width);
    th = int(desc.height);
    return true;
}

bool GlesRenderer::importAhbAsTexture(AHardwareBuffer* ahb) {
    return importAhbAsTexture(ahb, -1);
}

bool GlesRenderer::importAhbAsTexture(AHardwareBuffer* ahb, int fenceFd) {
    return importAhbToTexture(ahb, texture_, texW_, texH_, ahbImage_, sourceAhb_,
                              AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT,
                              AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, fenceFd);
}

bool GlesRenderer::init(ANativeWindow* window, AHardwareBuffer* ahb) {
    if (!window || !ahb) {
        LOGE("init: null window or ahb");
        return false;
    }
    if (!initEgl(window))           return false;
    // init() runs on the RawV3Gl render thread (Kotlin posts nativeInitRenderer
    // there); record its tid so assertGlThread can catch off-thread uploads.
    glThreadTid_ = gettid();
    if (!createProgram())           return false;
    if (!importAhbAsTexture(ahb))   return false;
    LOGI("init: OK");
    return true;
}

bool GlesRenderer::updateAhb(AHardwareBuffer* ahb, int fenceFd) {
    if (!ahb) return false;
    if (display_ == EGL_NO_DISPLAY) {
        LOGE("updateAhb: renderer not initialized");
        return false;
    }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("updateAhb: eglMakeCurrent failed err=0x%x", eglGetError());
        return false;
    }
    assertGlThread("updateAhb");
    // Source texture changed — all pre-passes must re-run.
    blurPassDirty_     = true;
    softDiffPassDirty_ = true;
    bloomPassDirty_    = true;
    nrPassDirty_       = true;
    return importAhbAsTexture(ahb, fenceFd);
}

bool GlesRenderer::bindTextureToMask(int layer, GLuint textureId) {
    if (layer < -3 || layer >= kMaskLayers) return false;
    if (display_ == EGL_NO_DISPLAY) return false;
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) return false;

    // layer -1 = subject mask (unit 2)
    // layer -2 = bokeh aux (unit 10)
    // layer -3 = sobel edge (unit 4)
    if (layer == -1) {
        subjectMaskTex_ = textureId;
        subjectMaskReady_ = (textureId != 0);
    } else if (layer == -2) {
        bokehAttenTex_ = textureId;
        bokehAttenReady_ = (textureId != 0);
    } else if (layer == -3) {
        sobelEdgeTex_ = textureId;
        sobelEdgeReady_ = (textureId != 0);
    } else if (layer >= 0) {
        brushMaskTex_[layer] = textureId;
        brushMaskReady_[layer] = (textureId != 0);
    }
    return true;
}

bool GlesRenderer::renderFrame() {
    if (display_ == EGL_NO_DISPLAY || surface_ == EGL_NO_SURFACE) return false;
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) return false;
    // AHB write lock: skip this frame (not an error) if a commitProcessedBuffer
    // call is currently writing new pixel data into the AHB.
    if (pthread_mutex_trylock(&ahbWriteLock_) != 0) return true;

    // Re-query size in case the surface was resized (rotation, etc.).
    eglQuerySurface(display_, surface_, EGL_WIDTH,  &surfaceW_);
    eglQuerySurface(display_, surface_, EGL_HEIGHT, &surfaceH_);

    LOGD("renderFrame: surface=%dx%d tex=%dx%d fxBlur=%d bokeh=%.2f ambiance=%.2f mist=%.2f vintage=%.2f glow=%.2f dust=%.2f",
         surfaceW_, surfaceH_, texW_, texH_,
         (int)params_.fxBlurStyle,
         params_.bokehBlur, params_.ambiance,
         params_.fxMist, params_.fxVintageStrength,
         params_.fxGlowStrength, params_.fxDust);

    // Lowpass pre-pass — populates `blurTexB_` (bound on unit 8 as
    // `uBlurTex` in the main pass). Driven by Bokeh / Ambiance / FX blur /
    // Clarity / CenterPop only. Soft diffusion has its own plane
    // (softDiffTex_) so Bokeh cannot overwrite the Orton/Glow wrap radius.
    const bool bokehActive = params_.bokehBlur > 0.0f || params_.bokehBalls > 0.f;
    const bool ambianceActive = params_.ambiance != 0.f;
    const bool ortonActive    = params_.ortonStrength > 0.f;
    const bool glowActive     = params_.fxGlowStrength > 0.f;
    const bool fxBlurActive   = params_.fxBlurStyle > 0 &&
        (params_.fxGaussBlur > 0.f || params_.fxDirBlurAmt > 0.f ||
         params_.fxRadBlurAmt > 0.f || params_.fxZoomBlurAmt > 0.f);
    // Clarity + CenterPop ALSO consume uBlurTex (tonalBlur tap, see the shader's
    // needTonalBlur). They were missing here, so adjusting Clarity alone (with
    // ambiance=0) left uBlurTex empty/stale → Clarity rendered weirdly until the
    // user nudged Ambiance. Include them so the blur pass runs for them too.
    const bool localContrastActive = params_.clarityAmount != 0.f || params_.centerPop != 0.f;
    const bool softDiffusionActive = ortonActive || glowActive;
    const bool blurConsumersActive =
        bokehActive || ambianceActive || fxBlurActive || localContrastActive;

    // Soft-diff Gaussian FIRST (uses blurTexA_ as H scratch) so a following
    // bokeh pass can overwrite A/B without destroying softDiffTex_.
    // Force Karis rebuild when softDiff changes — bloomTex_[0] may already
    // hold a prior softDiff bake; compositing again would double-apply.
    const bool softDiffDirtyNow = softDiffusionActive && softDiffPassDirty_;
    if (softDiffDirtyNow) {
        softDiffPassDirty_ = false;
        bloomPassDirty_ = true;
        const float softR = 2.0f + params_.bloomRadius * 1.15f;
        runSoftDiffBlurPass(softR);
    }

    if (blurConsumersActive && blurPassDirty_) {
        blurPassDirty_ = false;
        float radiusPx;
        if (bokehActive)
            radiusPx = 6.0f + params_.bokehBlur * 28.0f + params_.bokehSpread * 18.0f; // scale with strength+spread
        else if (fxBlurActive) {
            float amt = std::max({params_.fxGaussBlur, params_.fxDirBlurAmt,
                                  params_.fxRadBlurAmt, params_.fxZoomBlurAmt});
            radiusPx = 2.0f + amt * 14.0f;
        } else
            radiusPx = 4.0f;
        runBokehBlurPass(radiusPx);
    }

    // ── Karis bloom pre-pass ────────────────────────────────────────
    //   Glow samples uBloomTex independently of Orton — gate on either.
    const bool opticalActive = params_.opticalSpread > 1e-4f || params_.opticalHalation > 1e-4f;
    const bool bloomTexNeeded =
        (ortonActive && params_.bloomRadius > 0.0f) || glowActive || opticalActive;
    const bool bloomDirtyNow = bloomEnabled_ && bloomTexNeeded && bloomPassDirty_;
    if (bloomDirtyNow) {
        bloomPassDirty_ = false;
        // Threshold luma: where the bloom starts to "fire". 0.65 mirrors
        // the shader's old highlight-gate of smoothstep(0.35, 0.95) so
        // the look stays consistent. Lower = more pixels contribute,
        // higher = only the brightest. Fixed for now; could become a
        // slider later.
        const float threshold = 0.65f;
        // Upsample radius: soft vertical-oval kernel (see filmicBloomOvalRadii).
        // Resolution-invariant — anchor to 1080-long-side. Spread coeff 0.22
        // (~1.5× original 0.15; dialed back from aggressive 0.45 / 3×).
        const float bloomR = (params_.bloomRadius > 0.f) ? params_.bloomRadius : 8.f;
        const float baseTent = 1.0f + bloomR * 0.22f;
        const int longSide = std::max(texW_, texH_);
        const float tentRadius = baseTent * float(longSide) / 1080.0f;
        runKarisBloomPass(threshold, tentRadius, params_.mistTightness);
    }

    // Bake soft-diff into a fresh Karis bloom (composite whenever either
    // plane rebuilt this frame).
    if (softDiffusionActive && bloomTexNeeded && softDiffTex_ && bloomTex_[0] &&
        (softDiffDirtyNow || bloomDirtyNow)) {
        runSoftDiffBloomComposite(params_.ortonStrength, params_.fxGlowStrength);
    }

    // ── Bilateral denoise pre-pass (Req 3) ──────────────────────────
    //   When NR slots 147/148 are non-zero, run the bilateral filter on
    //   the source texture and store the denoised result in nrTexA_.
    //   The uber-shader then reads nrTexA_ instead of texture_.
    //   When both slots are zero, skip entirely — no FBO overhead.
    // Solution A: only re-run when NR params actually changed.
    const bool nrActive = (nrSlot147_ != 0.f || nrSlot148_ != 0.f) && nrProg_ != 0;
    if (nrActive && nrPassDirty_ && ensureNrTargets(texW_, texH_)) {
        nrPassDirty_ = false;
        glUseProgram(nrProg_);
        glBindVertexArray(vao_);
        glViewport(0, 0, nrW_, nrH_);
        glBindFramebuffer(GL_FRAMEBUFFER, nrFboA_);

        // Bind source texture on unit 0.
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture_);
        if (nrLocTex_ >= 0) glUniform1i(nrLocTex_, 0);

        // Uniform mapping (Task 3.9):
        //   uSigma     = slot147 / 100.0 * 5.0   → range [0..5]
        //   uThreshold = slot148 / 100.0 * 0.1   → range [0..0.1]
        //   uKSigma    = 2.0                      (fixed)
        const float sigma     = nrSlot147_ / 100.f * 5.f;
        const float threshold = nrSlot148_ / 100.f * 0.1f;
        if (nrLocSigma_     >= 0) glUniform1f(nrLocSigma_,     sigma);
        if (nrLocThreshold_ >= 0) glUniform1f(nrLocThreshold_, threshold);
        if (nrLocKSigma_    >= 0) glUniform1f(nrLocKSigma_,    2.0f);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    // ── Sharpen gate: when sharpenAmount != 0 and the program compiled
    //   successfully, redirect uber-shader output into the sharpen FBO.
    //   The sharpen pass then reads from sharpenTex_ and draws to the
    //   default framebuffer (window surface). When sharpen is off, the
    //   uber-shader draws directly to the window surface (zero overhead).
    // Task 4.6: C++ guard — sharpen FBO pass only when slider > 0.
    // Selective-bokeh: auto subject sharpen 0.35 when depth+bokehBlur and
    // slot 379 is still 0. Explicit sharpenAmount always wins.
    const bool selBokehSharpen = depthMapReady_ && params_.bokehBlur > 0.f;
    float sharpenAmt = params_.sharpenAmount;
    // auto subject sharpen removed — explicit only
    const bool sharpenActive = sharpenAmt > 0.0f && sharpenProg_ != 0;
    int vpX = 0, vpY = 0, vpW = surfaceW_, vpH = surfaceH_;

    // Letterbox: clear to opaque black to prevent stale overlays from showing through.
    glViewport(0, 0, surfaceW_, surfaceH_);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    if (texW_ > 0 && texH_ > 0 && surfaceW_ > 0 && surfaceH_ > 0) {
        float texAspect = float(texW_) / float(texH_);
        float surAspect = float(surfaceW_) / float(surfaceH_);
        if (texAspect > surAspect) {
            // Texture is wider — fit by width.
            vpW = surfaceW_;
            vpH = int(float(surfaceW_) / texAspect);
            vpX = 0;
            vpY = (surfaceH_ - vpH) / 2;
        } else {
            vpH = surfaceH_;
            vpW = int(float(surfaceH_) * texAspect);
            vpY = 0;
            vpX = (surfaceW_ - vpW) / 2;
        }
    }

    // Preview pan/zoom — applied to the WINDOW viewport only. The fitted photo
    // rect (vpX,vpY,vpW,vpH) is scaled about its centre by viewScale_ and panned
    // by viewOffset (surface px; Y flipped for GL's bottom-up viewport). GL clips
    // the draw to the surface, so a zoomed preview is contained by the slot and
    // never spills over the chrome. Intermediate FBO passes keep the fitted size.
    int wvpW = int(float(vpW) * viewScale_ + 0.5f);
    int wvpH = int(float(vpH) * viewScale_ + 0.5f);
    int wvpX = (vpX + vpW / 2) + (int)viewOffsetX_ - wvpW / 2;
    int wvpY = (vpY + vpH / 2) - (int)viewOffsetY_ - wvpH / 2;

    if (sharpenActive && ensureSharpenTargets(vpW, vpH)) {
        // Uber-shader draws into sharpen FBO at the (un-zoomed) fitted size.
        glBindFramebuffer(GL_FRAMEBUFFER, sharpenFbo_);
        glViewport(0, 0, vpW, vpH);
    } else {
        glViewport(wvpX, wvpY, wvpW, wvpH);
    }

    glUseProgram(program_);
    glActiveTexture(GL_TEXTURE0);
    // Task 3.8/3.10: uber-shader reads denoised nrTexA_ when NR active,
    // otherwise reads source texture_ directly (no FBO overhead).
    glBindTexture(GL_TEXTURE_2D, (nrActive && nrTexA_) ? nrTexA_ : texture_);
    if (uTexLoc_ >= 0) glUniform1i(uTexLoc_, 0);

    // 3D LUT on texture unit 1. Bind unconditionally (the shader skips the
    // sample when uLutEnabled is 0) so the sampler always has a valid
    // backing — some drivers UB when an active sampler points at nothing.
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_3D, lutTexture_);
    if (uLutTexLoc_ >= 0) glUniform1i(uLutTexLoc_, 1);

    // U2Net subject mask on unit 2. Same defensive bind — sampler always
    // has backing even when no segmentation is present (shader checks
    // uSubjectMaskEnabled before sampling).
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, subjectMaskTex_);
    if (uSubjectMaskLoc_ >= 0) glUniform1i(uSubjectMaskLoc_, 2);

    // Sobel edge mask on unit 4 (used by subjectGate for edge-snap).
    glActiveTexture(GL_TEXTURE4);
    glBindTexture(GL_TEXTURE_2D, sobelEdgeTex_);
    if (uSobelEdgeMaskLoc_ >= 0) glUniform1i(uSobelEdgeMaskLoc_, 4);

    // Bokeh attenuation (Cityscapes max(sky, terrain)) on unit 10. Defensive
    // bind even when bokehAttenReady_ is false so the sampler always has
    // backing — shader gates on uBokehAttenuationEnabled.
    glActiveTexture(GL_TEXTURE10);
    glBindTexture(GL_TEXTURE_2D, bokehAttenTex_);
    if (uBokehAttenLoc_         >= 0) glUniform1i(uBokehAttenLoc_, 10);
    if (uBokehAttenEnabledLoc_  >= 0) glUniform1i(uBokehAttenEnabledLoc_, bokehAttenReady_ ? 1 : 0);
    if (uDepthMapEnabledLoc_    >= 0) glUniform1i(uDepthMapEnabledLoc_, depthMapReady_ ? 1 : 0);
    if (uBokehFocusDepthLoc_    >= 0) glUniform1f(uBokehFocusDepthLoc_, bokehFocusDepth_);

    // Brush-painted Mask tab layers: 0→unit3, 1→unit5, 2→unit6, 3→unit7.
    // Unit 4 stays the Sobel edge mask. Bind 0 for empty layers so the
    // sampler always has valid backing (shader gates by uBrushMaskEnabled).
    {
        static const GLenum kMaskTexUnits[kMaskLayers] =
            {GL_TEXTURE3, GL_TEXTURE5, GL_TEXTURE6, GL_TEXTURE7};
        for (int i = 0; i < kMaskLayers; ++i) {
            glActiveTexture(kMaskTexUnits[i]);
            glBindTexture(GL_TEXTURE_2D, brushMaskTex_[i]);
        }
    }

    // Blurred-source texture on unit 8 (blurTexB_ from the Gaussian
    // pre-pass) — bokeh, Ambiance, FX blur, Clarity only. Soft diffusion
    // uses softDiffTex_ → baked into uBloomTex, not this unit.
    glActiveTexture(GL_TEXTURE8);
    glBindTexture(GL_TEXTURE_2D,
        blurConsumersActive ? blurTexB_ : 0);

    // Tone Curve LUT on unit 9 (shader gates on uToneCurveEnabled).
    glActiveTexture(GL_TEXTURE9);
    glBindTexture(GL_TEXTURE_2D, toneCurveTex_);

    bindVintageFxTextures(program_);

    // Karis bloom pyramid composite on unit 11 (bloomTex_[0] — mip 0)
    // after the upsample chain composited all smaller mips into it).
    // Glow samples uBloomTex without Orton — bind whenever bloomTexNeeded.
    glActiveTexture(GL_TEXTURE11);
    glBindTexture(GL_TEXTURE_2D, bloomTexNeeded ? bloomTex_[0] : 0);
    if (uBloomTexLoc_ >= 0) glUniform1i(uBloomTexLoc_, 11);


    pushUniforms();
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    // ── Laplacian sharpen post-pass (Req 6, slot 379) ────────────────
    //   When active, the uber-shader rendered into sharpenFbo_. Now run
    //   the 3×3 kernel reading sharpenTex_ and draw to the window surface.
    //   When inactive (sharpenAmount == 0), this block is skipped — the
    //   uber-shader already drew directly to the default framebuffer.
    if (sharpenActive && sharpenFbo_ && sharpenTex_) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        // Window draw → apply the same pan/zoom viewport as the non-sharpen path.
        glViewport(wvpX, wvpY, wvpW, wvpH);

        glUseProgram(sharpenProg_);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sharpenTex_);
        if (sharpenLocTex_       >= 0) glUniform1i(sharpenLocTex_, 0);
        if (sharpenLocSharpness_ >= 0) glUniform1f(sharpenLocSharpness_, sharpenAmt);
        if (sharpenLocTexelSize_ >= 0) glUniform2f(sharpenLocTexelSize_,
            1.0f / float(vpW), 1.0f / float(vpH));
        // Subject-only when selective bokeh look is live (depth+bokeh).
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, subjectMaskReady_ ? subjectMaskTex_ : 0);
        if (sharpenLocSubjectMask_ >= 0) glUniform1i(sharpenLocSubjectMask_, 1);
        if (sharpenLocSubjectEn_   >= 0) glUniform1i(sharpenLocSubjectEn_, subjectMaskReady_ ? 1 : 0);
        if (sharpenLocSubjectRect_ >= 0) glUniform4fv(sharpenLocSubjectRect_, 1, subjectMaskRect_);
        if (sharpenLocSubjectOnly_ >= 0) glUniform1i(sharpenLocSubjectOnly_, selBokehSharpen ? 1 : 0);

        glBindVertexArray(vao_);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
    }

    // NOTE: do NOT glInvalidateFramebuffer(GL_COLOR) before eglSwapBuffers
    // on the *default* framebuffer. Mali interprets that as "the
    // application doesn't need this buffer's contents preserved" — and the
    // contents are exactly what we want to present. We were drawing
    // correctly (glReadPixels confirmed green pixels in the back buffer)
    // but the swap chain then discarded them, leaving the user with a
    // black screen. The tile-memory hint is correct for *FBO render
    // targets* (intermediate render passes) but not for the swap-chain
    // surface. Skip it here; the driver can manage the swap-chain buffer
    // lifetime on its own.

    if (!eglSwapBuffers(display_, surface_)) {
        EGLint err = eglGetError();
        LOGE("renderFrame: eglSwapBuffers failed err=0x%x", err);
        if (err == EGL_BAD_SURFACE || err == EGL_BAD_NATIVE_WINDOW) {
            pthread_mutex_unlock(&ahbWriteLock_);
            release();
            return false; // signal Kotlin to clear handle and reboot
        }
    }
    pthread_mutex_unlock(&ahbWriteLock_);
    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Render the current uber-shader output (with all current uniforms + LUT)
//  into a destination AHardwareBuffer. Used at Apply time so the saved
//  snapshot contains the GRADED pixels the user sees on the editor canvas,
//  not the pristine pre-shader Stage B base. See Plan.md M7.fix.
//
//  The destination AHB must be the same size + format as the source AHB
//  (RGBA_F16). Allocator side: Kotlin allocates with the same dims it gave
//  to Stage B, and passes it here.
//
//  Steps:
//    1. Import dst AHB as an EGLImageKHR + bind to a separate GL_TEXTURE_2D.
//    2. Attach that texture to an FBO color attachment.
//    3. Bind FBO, set viewport to dst dims, render fullscreen quad with the
//       current program + uniforms + source texture.
//    4. glFinish() so the AHB contains valid pixels before the caller reads
//       it back via AHardwareBuffer_lock.
// ─────────────────────────────────────────────────────────────────────────────
bool GlesRenderer::snapshotGradedToAhb(AHardwareBuffer* dst) {
    if (display_ == EGL_NO_DISPLAY) { LOGE("snapshot: no display"); return false; }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("snapshot: eglMakeCurrent failed err=0x%x", eglGetError()); return false;
    }
    if (!resolveExtensions()) return false;

    AHardwareBuffer_Desc dstDesc{};
    AHardwareBuffer_describe(dst, &dstDesc);
    LOGI("snapshot: dst=%ux%u format=0x%x src texture=%dx%d",
         dstDesc.width, dstDesc.height, dstDesc.format, texW_, texH_);

    // Import dst AHB as EGLImageKHR + a fresh texture name.
    EGLClientBuffer cb = fn_eglGetNativeClientBufferANDROID(dst);
    if (!cb) { LOGE("snapshot: dst clientBuffer null"); return false; }
    EGLint imageAttribs[] = {
        EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
        EGL_NONE,
    };
    EGLImageKHR dstImage = fn_eglCreateImageKHR(
        display_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, imageAttribs);
    if (dstImage == EGL_NO_IMAGE_KHR) {
        LOGE("snapshot: eglCreateImageKHR(dst) failed err=0x%x", eglGetError());
        return false;
    }

    GLuint dstTex = 0;
    glGenTextures(1, &dstTex);
    glBindTexture(GL_TEXTURE_2D, dstTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    fn_glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, (GLeglImageOES) dstImage);
    if (glGetError() != GL_NO_ERROR) {
        LOGE("snapshot: bind dst image to texture failed");
        fn_eglDestroyImageKHR(display_, dstImage);
        glDeleteTextures(1, &dstTex);
        return false;
    }

    // Blur pre-pass (into the ping-pong FBOs) BEFORE binding the dst FBO,
    // so the export carries the same blurred-source consumers (bokeh /
    // Ambiance / Clarity) that the live preview does. Soft diffusion is a
    // separate plane baked into bloom (not uBlurTex).
    const bool snapBokeh    = params_.bokehBlur > 0.f || params_.bokehBalls > 0.f;
    const bool snapAmbiance = params_.ambiance != 0.f;
    const bool snapOrton    = params_.ortonStrength > 0.f;
    const bool snapGlow     = params_.fxGlowStrength > 0.f;
    const bool snapFxBlur   = params_.fxBlurStyle > 0 &&
        (params_.fxGaussBlur > 0.f || params_.fxDirBlurAmt > 0.f ||
         params_.fxRadBlurAmt > 0.f || params_.fxZoomBlurAmt > 0.f);
    const bool snapSoftDiff = snapOrton || snapGlow;
    const bool snapLocalContrast = params_.clarityAmount != 0.f || params_.centerPop != 0.f;
    const bool snapBlurNeeded = snapBokeh || snapAmbiance || snapFxBlur || snapLocalContrast;
    if (snapSoftDiff) {
        runSoftDiffBlurPass(2.0f + params_.bloomRadius * 1.15f);
    }
    if (snapBlurNeeded) {
        float radiusPx;
        if (snapBokeh)
            radiusPx = 6.0f + params_.bokehBlur * 28.0f + params_.bokehSpread * 18.0f; // scale with strength+spread
        else if (snapFxBlur) {
            float amt = std::max({params_.fxGaussBlur, params_.fxDirBlurAmt,
                                  params_.fxRadBlurAmt, params_.fxZoomBlurAmt});
            radiusPx = 2.0f + amt * 14.0f;
        } else
            radiusPx = 4.0f;
        runBokehBlurPass(radiusPx);
    }
    // Karis bloom pyramid for the snapshot export — mirrors the live path.
    const bool snapOptical = params_.opticalSpread > 1e-4f || params_.opticalHalation > 1e-4f;
    const bool snapBloomNeeded =
        (snapOrton && params_.bloomRadius > 0.f) || snapGlow || snapOptical;
    if (snapBloomNeeded) {
        const float threshold  = 0.65f;
        const float bloomR = (params_.bloomRadius > 0.f) ? params_.bloomRadius : 8.f;
        const float baseTent = 1.0f + bloomR * 0.22f;
        const int longSide = std::max(texW_, texH_);
        const float tentRadius = baseTent * float(longSide) / 1080.0f;
        runKarisBloomPass(threshold, tentRadius, params_.mistTightness);
        if (snapSoftDiff && softDiffTex_ && bloomTex_[0])
            runSoftDiffBloomComposite(params_.ortonStrength, params_.fxGlowStrength);
    }

    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, dstTex, 0);
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("snapshot: FBO not complete 0x%x", status);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &dstTex);
        fn_eglDestroyImageKHR(display_, dstImage);
        return false;
    }

    // No letterbox at snapshot time: the destination matches the source,
    // so we render full-frame.
    glViewport(0, 0, dstDesc.width, dstDesc.height);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);

    // Use the *snapshot* program (identity vert shader). The fragment shader
    // is the same as the display program, so all uniform NAMES are the same,
    // but the per-program location IDs may differ. Re-resolve them at the
    // moment of use; this runs once per Apply, not once per frame.
    glUseProgram(programSnap_);
    glActiveTexture(GL_TEXTURE0);
    // NR: use denoised texture when bilateral was active (same logic as renderFrame).
    const bool snapNrActive = (nrSlot147_ != 0.f || nrSlot148_ != 0.f) && nrProg_ != 0 && nrTexA_ != 0;
    glBindTexture(GL_TEXTURE_2D, snapNrActive ? nrTexA_ : texture_);
    GLint snapTexLoc    = glGetUniformLocation(programSnap_, "uTex");
    GLint snapLutTexLoc = glGetUniformLocation(programSnap_, "uLutTex");
    if (snapTexLoc >= 0) glUniform1i(snapTexLoc, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_3D, lutTexture_);
    if (snapLutTexLoc >= 0) glUniform1i(snapLutTexLoc, 1);
    // Subject mask on unit 2 — same defensive bind as the display path.
    GLint snapSubjectMaskLoc = glGetUniformLocation(programSnap_, "uSubjectMask");
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, subjectMaskTex_);
    if (snapSubjectMaskLoc >= 0) glUniform1i(snapSubjectMaskLoc, 2);
    GLint snapSobelLoc = glGetUniformLocation(programSnap_, "uSobelEdgeMask");
    glActiveTexture(GL_TEXTURE4);
    glBindTexture(GL_TEXTURE_2D, sobelEdgeTex_);
    if (snapSobelLoc >= 0) glUniform1i(snapSobelLoc, 4);
    // Bokeh attenuation on unit 10 — same defensive bind as the display path.
    GLint snapBokehAttenLoc = glGetUniformLocation(programSnap_, "uBokehAttenuation");
    GLint snapBokehAttenEnLoc = glGetUniformLocation(programSnap_, "uBokehAttenuationEnabled");
    glActiveTexture(GL_TEXTURE10);
    glBindTexture(GL_TEXTURE_2D, bokehAttenTex_);
    if (snapBokehAttenLoc   >= 0) glUniform1i(snapBokehAttenLoc, 10);
    if (snapBokehAttenEnLoc >= 0) glUniform1i(snapBokehAttenEnLoc, bokehAttenReady_ ? 1 : 0);
    if (uDepthMapEnabledLoc_    >= 0) glUniform1i(uDepthMapEnabledLoc_, depthMapReady_ ? 1 : 0);
    if (uBokehFocusDepthLoc_    >= 0) glUniform1f(uBokehFocusDepthLoc_, bokehFocusDepth_);
    // 4 brush mask layers on units 3,5,6,7 (sampler uniforms set in
    // pushUniformsForProgram below).
    {
        static const GLenum kMaskTexUnits[kMaskLayers] =
            {GL_TEXTURE3, GL_TEXTURE5, GL_TEXTURE6, GL_TEXTURE7};
        for (int i = 0; i < kMaskLayers; ++i) {
            glActiveTexture(kMaskTexUnits[i]);
            glBindTexture(GL_TEXTURE_2D, brushMaskTex_[i]);
        }
    }
    // Blurred-source texture on unit 8 (from the Gaussian pre-pass).
    // Used by bokeh / Ambiance / Clarity — softDiff is baked into bloom.
    glActiveTexture(GL_TEXTURE8);
    glBindTexture(GL_TEXTURE_2D, snapBlurNeeded ? blurTexB_ : 0);
    // Tone Curve LUT on unit 9.
    glActiveTexture(GL_TEXTURE9);
    glBindTexture(GL_TEXTURE_2D, toneCurveTex_);
    bindVintageFxTextures(programSnap_);
    // Karis bloom pyramid on unit 11 (mip 0 = final composited bloom).
    glActiveTexture(GL_TEXTURE11);
    glBindTexture(GL_TEXTURE_2D, snapBloomNeeded ? bloomTex_[0] : 0);
    GLint snapBloomTexLoc = glGetUniformLocation(programSnap_, "uBloomTex");
    if (snapBloomTexLoc >= 0) glUniform1i(snapBloomTexLoc, 11);

    // Push all the uniform values — uses the currently active program
    // (programSnap_), but resolves locations against programSnap_ via
    // glGetUniformLocation inside a small helper.
    pushUniformsForProgram(programSnap_);

    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    // glFinish so the AHB has the rendered bytes before the caller locks it.
    glFinish();

    // Restore default framebuffer + clean up.
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &dstTex);
    fn_eglDestroyImageKHR(display_, dstImage);

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) LOGE("snapshot: trailing GL err=0x%x", err);
    LOGI("snapshot: done %ux%u", dstDesc.width, dstDesc.height);
    return true;
}

bool GlesRenderer::histogramGraded(int side, int* outHist) {
    if (!outHist) return false;
    for (int i = 0; i < 256; ++i) outHist[i] = 0;
    if (side <= 0) side = 256;
    if (display_ == EGL_NO_DISPLAY) { LOGE("hist: no display"); return false; }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("hist: eglMakeCurrent failed err=0x%x", eglGetError()); return false;
    }
    if (!resolveExtensions()) return false;

    // Offscreen RGBA8 renderbuffer-backed FBO at side×side. We don't need an
    // AHB here — the result is read straight back to CPU and discarded.
    GLuint rbo = 0;
    glGenRenderbuffers(1, &rbo);
    glBindRenderbuffer(GL_RENDERBUFFER, rbo);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, side, side);

    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rbo);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("hist: FBO incomplete");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(1, &fbo);
        glDeleteRenderbuffers(1, &rbo);
        return false;
    }

    glViewport(0, 0, side, side);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);

    // Same graded draw as snapshotGradedToAhb: snapshot program (identity
    // vert), full set of textures + uniforms.
    glUseProgram(programSnap_);
    glActiveTexture(GL_TEXTURE0);
    // NR: use denoised texture when bilateral was active.
    glBindTexture(GL_TEXTURE_2D,
        ((nrSlot147_ != 0.f || nrSlot148_ != 0.f) && nrProg_ && nrTexA_) ? nrTexA_ : texture_);
    GLint texLoc    = glGetUniformLocation(programSnap_, "uTex");
    GLint lutTexLoc = glGetUniformLocation(programSnap_, "uLutTex");
    if (texLoc >= 0) glUniform1i(texLoc, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_3D, lutTexture_);
    if (lutTexLoc >= 0) glUniform1i(lutTexLoc, 1);
    GLint subjLoc = glGetUniformLocation(programSnap_, "uSubjectMask");
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, subjectMaskTex_);
    if (subjLoc >= 0) glUniform1i(subjLoc, 2);
    GLint sobelLoc = glGetUniformLocation(programSnap_, "uSobelEdgeMask");
    glActiveTexture(GL_TEXTURE4);
    glBindTexture(GL_TEXTURE_2D, sobelEdgeTex_);
    if (sobelLoc >= 0) glUniform1i(sobelLoc, 4);
    // Bokeh attenuation on unit 10.
    GLint bokehAttLoc = glGetUniformLocation(programSnap_, "uBokehAttenuation");
    GLint bokehAttEnLoc = glGetUniformLocation(programSnap_, "uBokehAttenuationEnabled");
    glActiveTexture(GL_TEXTURE10);
    glBindTexture(GL_TEXTURE_2D, bokehAttenTex_);
    if (bokehAttLoc   >= 0) glUniform1i(bokehAttLoc, 10);
    if (bokehAttEnLoc >= 0) glUniform1i(bokehAttEnLoc, bokehAttenReady_ ? 1 : 0);
    if (uDepthMapEnabledLoc_    >= 0) glUniform1i(uDepthMapEnabledLoc_, depthMapReady_ ? 1 : 0);
    if (uBokehFocusDepthLoc_    >= 0) glUniform1f(uBokehFocusDepthLoc_, bokehFocusDepth_);
    // 4 brush mask layers on units 3,5,6,7.
    {
        static const GLenum kMaskTexUnits[kMaskLayers] =
            {GL_TEXTURE3, GL_TEXTURE5, GL_TEXTURE6, GL_TEXTURE7};
        for (int i = 0; i < kMaskLayers; ++i) {
            glActiveTexture(kMaskTexUnits[i]);
            glBindTexture(GL_TEXTURE_2D, brushMaskTex_[i]);
        }
    }
    // Tone Curve LUT on unit 9 (so the graded histogram reflects the curve).
    glActiveTexture(GL_TEXTURE9);
    glBindTexture(GL_TEXTURE_2D, toneCurveTex_);
    bindVintageFxTextures(programSnap_);

    pushUniformsForProgram(programSnap_);

    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glFinish();

    // Read back RGBA8 and bin BT.601 luma into 256 buckets.
    std::vector<uint8_t> px(size_t(side) * side * 4);
    glReadPixels(0, 0, side, side, GL_RGBA, GL_UNSIGNED_BYTE, px.data());

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    glDeleteRenderbuffers(1, &rbo);

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) { LOGE("hist: GL err=0x%x", err); return false; }

    const size_t n = size_t(side) * side;
    for (size_t i = 0; i < n; ++i) {
        const int r = px[i * 4 + 0];
        const int g = px[i * 4 + 1];
        const int b = px[i * 4 + 2];
        // BT.601 luma, matching the CLAHE kernel's coefficients.
        const int luma = (r * 299 + g * 587 + b * 114) / 1000;
        outHist[luma < 0 ? 0 : (luma > 255 ? 255 : luma)]++;
    }
    return true;
}

bool GlesRenderer::snapshotGradedToBitmap(int outW, int outH, uint8_t* outRgba) {
    if (!outRgba || outW <= 0 || outH <= 0) return false;
    if (display_ == EGL_NO_DISPLAY) { LOGE("snapBmp: no display"); return false; }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("snapBmp: eglMakeCurrent failed err=0x%x", eglGetError()); return false;
    }
    if (!resolveExtensions()) return false;

    // Run the same pre-passes the live frame uses: Gaussian blur for
    // Ambiance/Bokeh (populates blurTexB_ → uBlurTex unit 8), dedicated
    // soft-diff Gaussian → bake into Karis bloom (uBloomTex unit 11).
    const bool snapBokeh    = params_.bokehBlur > 0.f || params_.bokehBalls > 0.f;
    const bool snapAmbiance = params_.ambiance != 0.f ||
                              params_.clarityAmount != 0.f ||
                              params_.centerPop != 0.f;
    const bool snapOrton    = params_.ortonStrength > 0.f || params_.subjectBloom > 0.f;
    const bool snapGlow2    = params_.fxGlowStrength > 0.f;
    const bool snapFxBlur2  = params_.fxBlurStyle > 0 &&
        (params_.fxGaussBlur > 0.f || params_.fxDirBlurAmt > 0.f ||
         params_.fxRadBlurAmt > 0.f || params_.fxZoomBlurAmt > 0.f);
    const bool snapSoftDiff2 = snapOrton || snapGlow2;
    const bool snapBlurNeeded = snapBokeh || snapAmbiance || snapFxBlur2;
    if (snapSoftDiff2) {
        runSoftDiffBlurPass(2.0f + params_.bloomRadius * 1.15f);
    }
    if (snapBlurNeeded) {
        float radiusPx;
        if (snapBokeh)
            radiusPx = 6.0f + params_.bokehBlur * 28.0f + params_.bokehSpread * 18.0f; // scale with strength+spread
        else if (snapFxBlur2) {
            float amt = std::max({params_.fxGaussBlur, params_.fxDirBlurAmt,
                                  params_.fxRadBlurAmt, params_.fxZoomBlurAmt});
            radiusPx = 2.0f + amt * 14.0f;
        } else
            radiusPx = 4.0f;
        runBokehBlurPass(radiusPx);
    }
    const bool snapOptical2 = params_.opticalSpread > 1e-4f || params_.opticalHalation > 1e-4f;
    const bool snapBloomNeeded2 =
        (snapOrton && params_.bloomRadius > 0.f) || snapGlow2 || snapOptical2;
    if (snapBloomNeeded2) {
        const float threshold  = 0.65f;
        const float bloomR = (params_.bloomRadius > 0.f) ? params_.bloomRadius : 8.f;
        const float baseTent = 1.0f + bloomR * 0.22f;
        const int longSide = std::max(texW_, texH_);
        const float tentRadius = baseTent * float(longSide) / 1080.0f;
        runKarisBloomPass(threshold, tentRadius, params_.mistTightness);
        if (snapSoftDiff2 && softDiffTex_ && bloomTex_[0])
            runSoftDiffBloomComposite(params_.ortonStrength, params_.fxGlowStrength);
    }

    // Offscreen RGBA8 renderbuffer at the requested (downscaled) size, full
    // graded frame (no letterbox). Same draw as histogramGraded, read back to
    // the caller's bitmap pixels. Used for the export page's graded preview.
    GLuint rbo = 0;
    glGenRenderbuffers(1, &rbo);
    glBindRenderbuffer(GL_RENDERBUFFER, rbo);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, outW, outH);

    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rbo);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("snapBmp: FBO incomplete");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(1, &fbo);
        glDeleteRenderbuffers(1, &rbo);
        return false;
    }

    glViewport(0, 0, outW, outH);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(programSnap_);
    glActiveTexture(GL_TEXTURE0);
    // NR: use denoised texture when bilateral was active.
    glBindTexture(GL_TEXTURE_2D,
        ((nrSlot147_ != 0.f || nrSlot148_ != 0.f) && nrProg_ && nrTexA_) ? nrTexA_ : texture_);
    GLint texLoc    = glGetUniformLocation(programSnap_, "uTex");
    GLint lutTexLoc = glGetUniformLocation(programSnap_, "uLutTex");
    if (texLoc >= 0) glUniform1i(texLoc, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_3D, lutTexture_);
    if (lutTexLoc >= 0) glUniform1i(lutTexLoc, 1);
    GLint subjLoc = glGetUniformLocation(programSnap_, "uSubjectMask");
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, subjectMaskTex_);
    if (subjLoc >= 0) glUniform1i(subjLoc, 2);
    GLint sobelLoc = glGetUniformLocation(programSnap_, "uSobelEdgeMask");
    glActiveTexture(GL_TEXTURE4);
    glBindTexture(GL_TEXTURE_2D, sobelEdgeTex_);
    if (sobelLoc >= 0) glUniform1i(sobelLoc, 4);
    // Bokeh attenuation on unit 10.
    GLint bokehAttLoc2   = glGetUniformLocation(programSnap_, "uBokehAttenuation");
    GLint bokehAttEnLoc2 = glGetUniformLocation(programSnap_, "uBokehAttenuationEnabled");
    glActiveTexture(GL_TEXTURE10);
    glBindTexture(GL_TEXTURE_2D, bokehAttenTex_);
    if (bokehAttLoc2   >= 0) glUniform1i(bokehAttLoc2, 10);
    if (bokehAttEnLoc2 >= 0) glUniform1i(bokehAttEnLoc2, bokehAttenReady_ ? 1 : 0);
    if (uDepthMapEnabledLoc_    >= 0) glUniform1i(uDepthMapEnabledLoc_, depthMapReady_ ? 1 : 0);
    if (uBokehFocusDepthLoc_    >= 0) glUniform1f(uBokehFocusDepthLoc_, bokehFocusDepth_);
    {
        static const GLenum kMaskTexUnits[kMaskLayers] =
            {GL_TEXTURE3, GL_TEXTURE5, GL_TEXTURE6, GL_TEXTURE7};
        for (int i = 0; i < kMaskLayers; ++i) {
            glActiveTexture(kMaskTexUnits[i]);
            glBindTexture(GL_TEXTURE_2D, brushMaskTex_[i]);
        }
    }
    // Tone Curve LUT on unit 9 (so the Export-page preview reflects the curve).
    glActiveTexture(GL_TEXTURE9);
    glBindTexture(GL_TEXTURE_2D, toneCurveTex_);
    bindVintageFxTextures(programSnap_);
    // Gaussian blur reference on unit 8 (uBlurTex). Used by Ambiance,
    // Clarity, CenterPop, Bokeh — softDiff is baked into uBloomTex.
    glActiveTexture(GL_TEXTURE8);
    glBindTexture(GL_TEXTURE_2D, snapBlurNeeded ? blurTexB_ : 0);
    GLint snapBlurTexLoc = glGetUniformLocation(programSnap_, "uBlurTex");
    if (snapBlurTexLoc >= 0) glUniform1i(snapBlurTexLoc, 8);
    // Karis bloom pyramid on unit 11 (mip 0 = final composited bloom).
    // Without this, the fragment shader samples an unbound/stale texture
    // for `uBloomTex` and bloom comes out black in the Export-page snapshot.
    glActiveTexture(GL_TEXTURE11);
    glBindTexture(GL_TEXTURE_2D, snapBloomNeeded2 ? bloomTex_[0] : 0);
    GLint snapBloomTexLoc2 = glGetUniformLocation(programSnap_, "uBloomTex");
    if (snapBloomTexLoc2 >= 0) glUniform1i(snapBloomTexLoc2, 11);

    pushUniformsForProgram(programSnap_);

    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glFinish();

    glReadPixels(0, 0, outW, outH, GL_RGBA, GL_UNSIGNED_BYTE, outRgba);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    glDeleteRenderbuffers(1, &rbo);

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) { LOGE("snapBmp: GL err=0x%x", err); return false; }
    LOGI("snapBmp: graded %dx%d readback ok", outW, outH);
    return true;
}

void GlesRenderer::release() {
    if (released_) return;
    released_ = true;
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (ahbImage_ != EGL_NO_IMAGE_KHR && fn_eglDestroyImageKHR) {
            fn_eglDestroyImageKHR(display_, ahbImage_);
            ahbImage_ = EGL_NO_IMAGE_KHR;
        }
        if (sourceAhb_) { AHardwareBuffer_release(sourceAhb_); sourceAhb_ = nullptr; }
        if (subjectMaskImage_ != EGL_NO_IMAGE_KHR && fn_eglDestroyImageKHR) {
            fn_eglDestroyImageKHR(display_, subjectMaskImage_);
            subjectMaskImage_ = EGL_NO_IMAGE_KHR;
        }
        if (subjectMaskAhb_) { AHardwareBuffer_release(subjectMaskAhb_); subjectMaskAhb_ = nullptr; }
        if (bokehAttenImage_ != EGL_NO_IMAGE_KHR && fn_eglDestroyImageKHR) {
            fn_eglDestroyImageKHR(display_, bokehAttenImage_);
            bokehAttenImage_ = EGL_NO_IMAGE_KHR;
        }
        if (bokehAttenAhb_) { AHardwareBuffer_release(bokehAttenAhb_); bokehAttenAhb_ = nullptr; }
        if (sobelEdgeImage_ != EGL_NO_IMAGE_KHR && fn_eglDestroyImageKHR) {
            fn_eglDestroyImageKHR(display_, sobelEdgeImage_);
            sobelEdgeImage_ = EGL_NO_IMAGE_KHR;
        }
        if (sobelEdgeAhb_) { AHardwareBuffer_release(sobelEdgeAhb_); sobelEdgeAhb_ = nullptr; }
        for (int i = 0; i < kMaskLayers; ++i) {
            if (brushMaskImage_[i] != EGL_NO_IMAGE_KHR && fn_eglDestroyImageKHR) {
                fn_eglDestroyImageKHR(display_, brushMaskImage_[i]);
                brushMaskImage_[i] = EGL_NO_IMAGE_KHR;
            }
            if (brushMaskAhb_[i]) { AHardwareBuffer_release(brushMaskAhb_[i]); brushMaskAhb_[i] = nullptr; }
        }
        if (texture_) { glDeleteTextures(1, &texture_); texture_ = 0; }
        if (subjectMaskTex_) { glDeleteTextures(1, &subjectMaskTex_); subjectMaskTex_ = 0; }
        subjectMaskW_ = 0; subjectMaskH_ = 0; subjectMaskReady_ = false;
        if (bokehAttenTex_) { glDeleteTextures(1, &bokehAttenTex_); bokehAttenTex_ = 0; }
        bokehAttenW_ = 0; bokehAttenH_ = 0; bokehAttenReady_ = false;
        depthMapReady_ = false; bokehFocusDepth_ = 0.5f;
        bokehAttenPlane_.clear(); depthPlane_.clear();
        for (int i = 0; i < kMaskLayers; ++i) {
            if (brushMaskTex_[i]) { glDeleteTextures(1, &brushMaskTex_[i]); brushMaskTex_[i] = 0; }
            brushMaskW_[i] = 0; brushMaskH_[i] = 0; brushMaskReady_[i] = false;
        }
        if (sobelEdgeTex_) { glDeleteTextures(1, &sobelEdgeTex_); sobelEdgeTex_ = 0; }
        sobelEdgeW_ = 0; sobelEdgeH_ = 0; sobelEdgeReady_ = false;
        if (toneCurveTex_) { glDeleteTextures(1, &toneCurveTex_); toneCurveTex_ = 0; }
        toneCurveReady_ = false;
        if (fxVintageMistTex_) { glDeleteTextures(1, &fxVintageMistTex_); fxVintageMistTex_ = 0; }
        fxVintageMistW_ = 0; fxVintageMistH_ = 0;
        if (fxVintageFilmTex_) { glDeleteTextures(1, &fxVintageFilmTex_); fxVintageFilmTex_ = 0; }
        fxVintageFilmW_ = 0; fxVintageFilmH_ = 0;
        if (fxVintageBlackTex_) { glDeleteTextures(1, &fxVintageBlackTex_); fxVintageBlackTex_ = 0; }
        if (blurProg_)    { glDeleteProgram(blurProg_);    blurProg_ = 0; }
        if (blurFboA_)    { glDeleteFramebuffers(1, &blurFboA_); blurFboA_ = 0; }
        if (blurFboB_)    { glDeleteFramebuffers(1, &blurFboB_); blurFboB_ = 0; }
        if (blurTexA_)    { glDeleteTextures(1, &blurTexA_); blurTexA_ = 0; }
        if (blurTexB_)    { glDeleteTextures(1, &blurTexB_); blurTexB_ = 0; }
        blurW_ = 0; blurH_ = 0;
        if (softDiffBloomProg_) { glDeleteProgram(softDiffBloomProg_); softDiffBloomProg_ = 0; }
        if (softDiffFbo_) { glDeleteFramebuffers(1, &softDiffFbo_); softDiffFbo_ = 0; }
        if (softDiffTex_) { glDeleteTextures(1, &softDiffTex_); softDiffTex_ = 0; }
        softDiffW_ = 0; softDiffH_ = 0;
        if (softDiffBloomScratchFbo_) {
            glDeleteFramebuffers(1, &softDiffBloomScratchFbo_); softDiffBloomScratchFbo_ = 0;
        }
        if (softDiffBloomScratchTex_) {
            glDeleteTextures(1, &softDiffBloomScratchTex_); softDiffBloomScratchTex_ = 0;
        }
        softDiffBloomScratchW_ = 0; softDiffBloomScratchH_ = 0;
        if (nrProg_) { glDeleteProgram(nrProg_); nrProg_ = 0; }
        if (nrTexA_) { glDeleteTextures(1, &nrTexA_); nrTexA_ = 0; }
        if (nrFboA_) { glDeleteFramebuffers(1, &nrFboA_); nrFboA_ = 0; }
        nrW_ = 0; nrH_ = 0;
        if (sharpenProg_) { glDeleteProgram(sharpenProg_); sharpenProg_ = 0; }
        if (sharpenFbo_)  { glDeleteFramebuffers(1, &sharpenFbo_); sharpenFbo_ = 0; }
        if (sharpenTex_)  { glDeleteTextures(1, &sharpenTex_); sharpenTex_ = 0; }
        sharpenW_ = 0; sharpenH_ = 0;
        if (gaussBlurHProg_) { glDeleteProgram(gaussBlurHProg_); gaussBlurHProg_ = 0; }
        if (gaussBlurVProg_) { glDeleteProgram(gaussBlurVProg_); gaussBlurVProg_ = 0; }
        if (bloomDownProg_) { glDeleteProgram(bloomDownProg_); bloomDownProg_ = 0; }
        if (bloomUpProg_)   { glDeleteProgram(bloomUpProg_);   bloomUpProg_ = 0; }
        for (int i = 0; i < kBloomMipCount; ++i) {
            if (bloomTex_[i]) { glDeleteTextures(1, &bloomTex_[i]); bloomTex_[i] = 0; }
            if (bloomFbo_[i]) { glDeleteFramebuffers(1, &bloomFbo_[i]); bloomFbo_[i] = 0; }
        }
        bloomBaseW_ = 0; bloomBaseH_ = 0;
        if (lutTexture_)     { glDeleteTextures(1, &lutTexture_); lutTexture_ = 0; }
        lutUploaded_ = false;
        if (curveMasterTex_) { glDeleteTextures(1, &curveMasterTex_); curveMasterTex_ = 0; }
        if (curveRTex_)      { glDeleteTextures(1, &curveRTex_); curveRTex_ = 0; }
        if (curveGTex_)      { glDeleteTextures(1, &curveGTex_); curveGTex_ = 0; }
        if (curveBTex_)      { glDeleteTextures(1, &curveBTex_); curveBTex_ = 0; }
        if (program_)     { glDeleteProgram(program_);     program_ = 0; }
        if (programSnap_) { glDeleteProgram(programSnap_); programSnap_ = 0; }
        if (vao_)     { glDeleteVertexArrays(1, &vao_); vao_ = 0; }
        if (vbo_)     { glDeleteBuffers(1, &vbo_); vbo_ = 0; }
        if (surface_ != EGL_NO_SURFACE) { eglDestroySurface(display_, surface_); surface_ = EGL_NO_SURFACE; }
        if (context_ != EGL_NO_CONTEXT) { eglDestroyContext(display_, context_); context_ = EGL_NO_CONTEXT; }
        eglTerminate(display_);
        display_ = EGL_NO_DISPLAY;
    }
    pthread_mutex_destroy(&ahbWriteLock_);
    LOGI("release: done");
}

}  // namespace raw_v3
