/*
 * StudioRoom — RAW Pipeline v3 — Headless GL save renderer.
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Phase 3 Checkpoint 1: minimal pbuffer EGL context, clear-and-readback.
 * No shader, no textures, no FBO yet — the goal is to prove the EGL
 * plumbing works on the device before piling shader work on top.
 */

#include "offscreen_save_renderer.h"

#include "shader_sources.h"   // canonical GLSL — do not re-inline shader text
#include "bloom_filmic.h"
#include "grading_uniforms.h"
#include "soft_diffusion.h"
#include "stage_blur.h"
#include "tiff_mmap_io.h"

#include <android/log.h>
#include <EGL/eglext.h>
#include <chrono>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#define LOG_TAG "OffscreenSave"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw_v3 {

OffscreenSaveRenderer::OffscreenSaveRenderer() = default;
OffscreenSaveRenderer::~OffscreenSaveRenderer() { release(); }

bool OffscreenSaveRenderer::init(int width, int height) {
    if (width <= 0 || height <= 0) {
        LOGE("init: bad dims %dx%d", width, height);
        return false;
    }
    width_  = width;
    height_ = height;

    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) {
        LOGE("init: eglGetDisplay failed");
        return false;
    }
    EGLint major = 0, minor = 0;
    if (!eglInitialize(display_, &major, &minor)) {
        LOGE("init: eglInitialize failed err=0x%x", eglGetError());
        return false;
    }
    LOGI("init: EGL %d.%d", major, minor);

    // Pick an RGBA8 pbuffer-capable config. RGBA8 is enough for save —
    // the framebuffer will hold the final 8-bit output before encode.
    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE,    EGL_PBUFFER_BIT,
        EGL_RED_SIZE,   8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE,  8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 0,
        EGL_STENCIL_SIZE, 0,
        EGL_NONE,
    };
    EGLConfig config;
    EGLint numConfigs = 0;
    if (!eglChooseConfig(display_, configAttribs, &config, 1, &numConfigs) ||
        numConfigs == 0) {
        LOGE("init: eglChooseConfig failed err=0x%x", eglGetError());
        release();
        return false;
    }

    const EGLint pbufferAttribs[] = {
        EGL_WIDTH,  width_,
        EGL_HEIGHT, height_,
        EGL_NONE,
    };
    surface_ = eglCreatePbufferSurface(display_, config, pbufferAttribs);
    if (surface_ == EGL_NO_SURFACE) {
        LOGE("init: eglCreatePbufferSurface failed err=0x%x", eglGetError());
        release();
        return false;
    }

    const EGLint ctxAttribs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE };
    context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, ctxAttribs);
    if (context_ == EGL_NO_CONTEXT) {
        LOGE("init: eglCreateContext failed err=0x%x", eglGetError());
        release();
        return false;
    }

    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("init: eglMakeCurrent failed err=0x%x", eglGetError());
        release();
        return false;
    }
    LOGI("init: ok %dx%d", width_, height_);
    return true;
}


bool OffscreenSaveRenderer::ensureContext() {
    if (display_ != EGL_NO_DISPLAY && context_ != EGL_NO_CONTEXT &&
        surface_ != EGL_NO_SURFACE) {
        return eglMakeCurrent(display_, surface_, surface_, context_);
    }
    // Tiny pbuffer — real graded output goes to an FBO at source resolution.
    return init(64, 64);
}

bool OffscreenSaveRenderer::clearAndReadback(uint8_t* outRgba) {
    if (display_ == EGL_NO_DISPLAY || !outRgba) {
        LOGE("clearAndReadback: not init or null out");
        return false;
    }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("clearAndReadback: eglMakeCurrent failed err=0x%x", eglGetError());
        return false;
    }
    glViewport(0, 0, width_, height_);
    // Opaque red — distinctive sentinel so a confused caller sees the
    // pipeline obviously reached this code.
    glClearColor(1.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glFinish();

    glReadPixels(0, 0, width_, height_, GL_RGBA, GL_UNSIGNED_BYTE, outRgba);
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("clearAndReadback: GL err=0x%x", err);
        return false;
    }
    LOGI("clearAndReadback: ok %dx%d", width_, height_);
    return true;
}

namespace {
// Fritsch-Carlson monotone cubic — same as GlesRenderer::uploadCurveLuts.
std::vector<float> buildMonotoneCubicLutOffscreen(const float* control_pts, int n_pts) {
    int n = n_pts;
    std::vector<double> xs(n), ys(n);
    for (int i = 0; i < n; i++) { xs[i] = control_pts[i * 2]; ys[i] = control_pts[i * 2 + 1]; }
    std::vector<double> delta(n - 1), m(n);
    for (int i = 0; i < n - 1; i++) {
        double dx = xs[i + 1] - xs[i];
        delta[i] = (dx < 1e-12) ? 0.0 : (ys[i + 1] - ys[i]) / dx;
    }
    m[0] = delta[0];
    for (int i = 1; i < n - 1; i++) m[i] = (delta[i - 1] + delta[i]) * 0.5;
    m[n - 1] = delta[n - 2];
    for (int i = 0; i < n - 1; i++) {
        if (std::fabs(delta[i]) < 1e-12) { m[i] = m[i + 1] = 0.0; continue; }
        double alpha = m[i] / delta[i], beta = m[i + 1] / delta[i];
        if (alpha * alpha + beta * beta > 9.0) {
            double t = 3.0 / std::sqrt(alpha * alpha + beta * beta);
            m[i] = t * alpha * delta[i]; m[i + 1] = t * beta * delta[i];
        }
    }
    std::vector<float> lut(256);
    for (int k = 0; k < 256; k++) {
        double t = k / 255.0;
        int seg = n - 2;
        for (int i = 0; i < n - 1; i++) { if (t <= xs[i + 1]) { seg = i; break; } }
        double h = xs[seg + 1] - xs[seg];
        if (h < 1e-12) { lut[k] = (float)ys[seg]; continue; }
        double u = (t - xs[seg]) / h;
        double u2 = u * u, u3 = u2 * u;
        double val = (2 * u3 - 3 * u2 + 1) * ys[seg]
                   + (u3 - 2 * u2 + u) * h * m[seg]
                   + (-2 * u3 + 3 * u2) * ys[seg + 1]
                   + (u3 - u2) * h * m[seg + 1];
        lut[k] = (float)std::fmax(0.0, std::fmin(1.0, val));
    }
    return lut;
}

GLuint uploadLut1DOffscreen(const float* data, int size) {
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

bool curvesNonIdentity(const ShaderParams& sp) {
    for (int i = 0; i < 16; i++) {
        float id = (i / 2) / 7.f;
        if (std::fabs(sp.curveMaster[i] - id) > 0.002f ||
            std::fabs(sp.curveR[i] - id) > 0.002f ||
            std::fabs(sp.curveG[i] - id) > 0.002f ||
            std::fabs(sp.curveB[i] - id) > 0.002f) return true;
    }
    return false;
}


// Minimal vertex shader for a fullscreen NDC quad with passed-through UV.
const char* kUvVert = R"(#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
})";

// UV → color test pattern. Center (R,G,B) = (uv.x*255, uv.y*255, 128).
const char* kUvFrag = R"(#version 300 es
precision mediump float;
in vec2 vUv;
out vec4 fragColor;
void main() {
    fragColor = vec4(vUv.x, vUv.y, 0.5, 1.0);
})";

GLuint compileShader(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024]{};
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        LOGE("compileShader(type=%u) failed: %s", type, log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

GLuint linkProgram(GLuint vs, GLuint fs) {
    GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    // Match attribute layout used below (location=0 for aPos).
    glBindAttribLocation(p, 0, "aPos");
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024]{};
        glGetProgramInfoLog(p, sizeof(log), nullptr, log);
        LOGE("linkProgram failed: %s", log);
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

}  // namespace

bool OffscreenSaveRenderer::renderUvPattern(uint8_t* outRgba) {
    if (display_ == EGL_NO_DISPLAY || !outRgba) {
        LOGE("renderUvPattern: not init or null out");
        return false;
    }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("renderUvPattern: eglMakeCurrent failed err=0x%x", eglGetError());
        return false;
    }

    GLuint vs = compileShader(GL_VERTEX_SHADER,   kUvVert);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kUvFrag);
    if (!vs || !fs) { if (vs) glDeleteShader(vs); if (fs) glDeleteShader(fs); return false; }
    GLuint prog = linkProgram(vs, fs);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (!prog) return false;

    // Fullscreen quad in NDC. TRIANGLE_STRIP order = BL, BR, TL, TR.
    const float verts[] = {
        -1.f, -1.f,
         1.f, -1.f,
        -1.f,  1.f,
         1.f,  1.f,
    };
    GLuint vbo = 0, vao = 0;
    glGenBuffers(1, &vbo);
    glGenVertexArrays(1, &vao);
    glBindVertexArray(vao);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, (void*)0);

    glViewport(0, 0, width_, height_);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(prog);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glFinish();

    glReadPixels(0, 0, width_, height_, GL_RGBA, GL_UNSIGNED_BYTE, outRgba);

    glBindVertexArray(0);
    glDeleteVertexArrays(1, &vao);
    glDeleteBuffers(1, &vbo);
    glUseProgram(0);
    glDeleteProgram(prog);

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("renderUvPattern: GL err=0x%x", err);
        return false;
    }
    LOGI("renderUvPattern: ok %dx%d", width_, height_);
    return true;
}

// ─── Karis bloom shaders ─────────────────────────────────────────────────
//   These used to be COPY-PASTED from gles_renderer.cpp, because the strings
//   lived in its anonymous namespace and were unreachable from here. That
//   duplication is exactly how preview != export divergence creeps in, so the
//   strings were hoisted to v3/shader_sources.{h,cpp} and are now included.
//
//   Verified behaviour-neutral at the time of the switch: the old local
//   kBloomVert was byte-identical to kBloomDownVert (and to kBloomUpVert —
//   all three bloom vertex shaders are the same text); kBloomUpFragOffscreen
//   differed only in comments; kBloomDownFragOffscreen differed only by six
//   MISSING uniform declarations (uBloomDownExcludeSubject, *SubjectEnabled,
//   *SubjectMask, *SubjectRect, *SobelMask, *EdgeSnapThreshold) which the
//   canonical shader body never reads — bloom subject-exclusion is declared
//   but not implemented. So picking up the canonical text changes no pixels.
namespace {

// compileShader + linkProgram reuse the helpers defined above for
// Checkpoint 2 (renderUvPattern) — they're in the same anonymous
// namespace, so name lookup finds them.

constexpr int kMipCount = 6;

}  // namespace

bool OffscreenSaveRenderer::computeKarisBloom(
        const float* srcRGB,
        int srcW, int srcH,
        float thresholdLuma,
        float tentRadiusPx,
        float* outRGB,
        float mistTightness,
        float bloomShape)
{
    if (display_ == EGL_NO_DISPLAY || !srcRGB || !outRGB || srcW <= 0 || srcH <= 0) {
        LOGE("computeKarisBloom: not init or null args");
        return false;
    }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("computeKarisBloom: eglMakeCurrent failed err=0x%x", eglGetError());
        return false;
    }

    GLint maxTex = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
    if (srcW > maxTex || srcH > maxTex) {
        LOGE("computeKarisBloom: source %dx%d exceeds GL_MAX_TEXTURE_SIZE=%d",
             srcW, srcH, maxTex);
        return false;
    }

    auto tStart = std::chrono::steady_clock::now();

    // ── Compile shaders ─────────────────────────────────────────────
    // Canonical text from shader_sources.h — same strings the live editor
    // compiles, so the offscreen bloom plane cannot drift from the preview.
    GLuint vs    = compileShader(GL_VERTEX_SHADER,   kBloomDownVert);
    GLuint fsDn  = compileShader(GL_FRAGMENT_SHADER, kBloomDownFrag);
    GLuint fsUp  = compileShader(GL_FRAGMENT_SHADER, kBloomUpFrag);
    if (!vs || !fsDn || !fsUp) {
        if (vs)   glDeleteShader(vs);
        if (fsDn) glDeleteShader(fsDn);
        if (fsUp) glDeleteShader(fsUp);
        return false;
    }
    GLuint dnProg = linkProgram(vs, fsDn);
    GLuint upProg = linkProgram(vs, fsUp);
    glDeleteShader(vs);
    glDeleteShader(fsDn);
    glDeleteShader(fsUp);
    if (!dnProg || !upProg) {
        if (dnProg) glDeleteProgram(dnProg);
        if (upProg) glDeleteProgram(upProg);
        return false;
    }

    // ── Upload source RGB → RGBA16F texture ─────────────────────────
    GLuint srcTex = 0;
    glGenTextures(1, &srcTex);
    glBindTexture(GL_TEXTURE_2D, srcTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB16F, srcW, srcH, 0,
                 GL_RGB, GL_FLOAT, srcRGB);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // ── Build mip chain ─────────────────────────────────────────────
    GLuint mipTex[kMipCount] = { 0 };
    GLuint mipFbo[kMipCount] = { 0 };
    int    mipW [kMipCount] = { 0 };
    int    mipH [kMipCount] = { 0 };
    for (int i = 0; i < kMipCount; ++i) {
        int w = srcW >> (i + 1);
        int h = srcH >> (i + 1);
        if (w < 4) w = 4;
        if (h < 4) h = 4;
        mipW[i] = w; mipH[i] = h;

        glGenTextures(1, &mipTex[i]);
        glBindTexture(GL_TEXTURE_2D, mipTex[i]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0,
                     GL_RGBA, GL_HALF_FLOAT, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glGenFramebuffers(1, &mipFbo[i]);
        glBindFramebuffer(GL_FRAMEBUFFER, mipFbo[i]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, mipTex[i], 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            LOGE("computeKarisBloom: mip %d FBO incomplete %dx%d", i, w, h);
            goto cleanup_fail;
        }
    }

    // Final-upscale FBO at source dimensions, sampled with bilinear from mip 0.
    GLuint outTex, outFbo;
    glGenTextures(1, &outTex);
    glBindTexture(GL_TEXTURE_2D, outTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB16F, srcW, srcH, 0,
                 GL_RGB, GL_FLOAT, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenFramebuffers(1, &outFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, outFbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, outTex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("computeKarisBloom: outFbo incomplete %dx%d", srcW, srcH);
        glDeleteFramebuffers(1, &outFbo);
        glDeleteTextures(1, &outTex);
        goto cleanup_fail;
    }

    // ── Pass 1: src → mip[0] with threshold ─────────────────────────
    GLuint vao;
    glGenVertexArrays(1, &vao);
    glBindVertexArray(vao);
    glDisable(GL_BLEND);
    glUseProgram(dnProg);
    {
        GLint srcLoc = glGetUniformLocation(dnProg, "uBloomDownSrc");
        GLint thrLoc = glGetUniformLocation(dnProg, "uBloomDownThreshold");
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, srcTex);
        if (srcLoc >= 0) glUniform1i(srcLoc, 0);
        if (thrLoc >= 0) glUniform1f(thrLoc, thresholdLuma);
        glBindFramebuffer(GL_FRAMEBUFFER, mipFbo[0]);
        glViewport(0, 0, mipW[0], mipH[0]);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        // Passes 2..N: mip[i-1] → mip[i] without threshold
        if (thrLoc >= 0) glUniform1f(thrLoc, -1.0f);
        for (int i = 1; i < kMipCount; ++i) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, mipTex[i - 1]);
            glBindFramebuffer(GL_FRAMEBUFFER, mipFbo[i]);
            glViewport(0, 0, mipW[i], mipH[i]);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }
    }

    // ── Filmic upsample: choke deep mips, bias toward mip1/mip2 ──────
    glUseProgram(upProg);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE);
    {
        float upW[6];
        filmicBloomUpWeights(mistTightness, upW);
        float ovalRx = tentRadiusPx, ovalRy = tentRadiusPx;
        filmicBloomOvalRadii(tentRadiusPx, bloomShape, ovalRx, ovalRy);
        GLint srcLoc = glGetUniformLocation(upProg, "uBloomUpSrc");
        GLint radLoc = glGetUniformLocation(upProg, "uBloomUpRadius");
        GLint wLoc   = glGetUniformLocation(upProg, "uBloomUpWeight");
        if (srcLoc >= 0) glUniform1i(srcLoc, 0);
        if (radLoc >= 0) glUniform2f(radLoc, ovalRx, ovalRy);
        for (int i = kMipCount - 1; i > 0; --i) {
            const float w = upW[i];
            if (w < 1e-4f) continue;
            if (wLoc >= 0) glUniform1f(wLoc, w);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, mipTex[i]);
            glBindFramebuffer(GL_FRAMEBUFFER, mipFbo[i - 1]);
            glViewport(0, 0, mipW[i - 1], mipH[i - 1]);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }
        // Final upscale: mip[0] → outTex at source dimensions, no blend.
        glDisable(GL_BLEND);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, mipTex[0]);
        glBindFramebuffer(GL_FRAMEBUFFER, outFbo);
        glViewport(0, 0, srcW, srcH);
        if (radLoc >= 0) glUniform2f(radLoc, 1.0f, 1.0f);
        if (wLoc >= 0) glUniform1f(wLoc, 1.0f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    // ── Readback to caller's float[3] buffer ────────────────────────
    // Note: ES 3.0 doesn't allow glReadPixels(GL_RGB, GL_FLOAT). Use
    // RGBA float intermediate, then pack down to RGB.
    {
        std::vector<float> rgba(size_t(srcW) * srcH * 4);
        glBindFramebuffer(GL_FRAMEBUFFER, outFbo);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, srcW, srcH, GL_RGBA, GL_FLOAT, rgba.data());
        GLenum err = glGetError();
        if (err != GL_NO_ERROR) {
            LOGE("computeKarisBloom: glReadPixels err=0x%x", err);
            glDeleteFramebuffers(1, &outFbo);
            glDeleteTextures(1, &outTex);
            glDeleteVertexArrays(1, &vao);
            goto cleanup_fail;
        }
        for (size_t i = 0; i < size_t(srcW) * srcH; ++i) {
            outRGB[i * 3 + 0] = rgba[i * 4 + 0];
            outRGB[i * 3 + 1] = rgba[i * 4 + 1];
            outRGB[i * 3 + 2] = rgba[i * 4 + 2];
        }
    }

    // ── Cleanup ─────────────────────────────────────────────────────
    glDeleteFramebuffers(1, &outFbo);
    glDeleteTextures(1, &outTex);
    glDeleteVertexArrays(1, &vao);
    for (int i = 0; i < kMipCount; ++i) {
        if (mipFbo[i]) glDeleteFramebuffers(1, &mipFbo[i]);
        if (mipTex[i]) glDeleteTextures(1, &mipTex[i]);
    }
    glDeleteTextures(1, &srcTex);
    glDeleteProgram(dnProg);
    glDeleteProgram(upProg);

    {
        auto tEnd = std::chrono::steady_clock::now();
        LOGI("computeKarisBloom: ok %dx%d in %lld ms (thr=%.2f tent=%.2f)",
             srcW, srcH,
             (long long) std::chrono::duration_cast<std::chrono::milliseconds>(tEnd - tStart).count(),
             thresholdLuma, tentRadiusPx);
    }
    return true;

cleanup_fail:
    for (int i = 0; i < kMipCount; ++i) {
        if (mipFbo[i]) glDeleteFramebuffers(1, &mipFbo[i]);
        if (mipTex[i]) glDeleteTextures(1, &mipTex[i]);
    }
    if (srcTex) glDeleteTextures(1, &srcTex);
    if (dnProg) glDeleteProgram(dnProg);
    if (upProg) glDeleteProgram(upProg);
    return false;
}

// ─── Checkpoint 3: Stage A FP16 source upload ────────────────────────────
//   The preview gets its source via importAhbAsTexture (zero-copy AHB); that
//   has no off-Android equivalent, so the headless path uploads explicitly.
//   See the header for the domain/sampler-parity rationale.
unsigned int OffscreenSaveRenderer::uploadSourceFp16(const uint16_t* rgbaHalf,
                                                     int w, int h) {
    if (!rgbaHalf || w <= 0 || h <= 0) {
        LOGE("uploadSourceFp16: bad args (ptr=%p %dx%d)", (const void*) rgbaHalf, w, h);
        return 0;
    }
    if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT) {
        LOGE("uploadSourceFp16: no EGL context — call init() first");
        return 0;
    }

    // Full-res in ONE texture or not at all: the Karis bloom tent radius scales
    // with longSide, so tiling would change bloom spread vs the preview. Better
    // to fail loudly and let the caller fall back to CPU than to tile silently.
    GLint maxTex = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
    if (w > maxTex || h > maxTex) {
        LOGE("uploadSourceFp16: %dx%d exceeds GL_MAX_TEXTURE_SIZE=%d", w, h, maxTex);
        return 0;
    }

    while (glGetError() != GL_NO_ERROR) {}   // clear stale errors so ours is ours

    GLuint tex = 0;
    glGenTextures(1, &tex);
    if (!tex) { LOGE("uploadSourceFp16: glGenTextures failed"); return 0; }
    glBindTexture(GL_TEXTURE_2D, tex);

    // RGBA16F rows are w*8 bytes — always 8-byte aligned, so the default
    // UNPACK_ALIGNMENT of 4 is already safe. Set it anyway: this renderer is
    // one-shot and shares a context with passes that set PACK_ALIGNMENT=1, and
    // relying on an inherited pixel-store value is how off-by-one-row bugs
    // start.
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

    // GL_HALF_FLOAT + GL_RGBA16F: a straight memcpy of Stage A's samples, no
    // float conversion on the CPU side and no precision lost on the way in.
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0,
                 GL_RGBA, GL_HALF_FLOAT, rgbaHalf);

    // Must match importAhbAsTexture exactly (gles_renderer.cpp) or edge taps
    // diverge from the preview.
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S,     GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T,     GL_CLAMP_TO_EDGE);

    const GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("uploadSourceFp16: GL error 0x%04x uploading %dx%d RGBA16F", err, w, h);
        glDeleteTextures(1, &tex);
        return 0;
    }
    LOGI("uploadSourceFp16: %dx%d RGBA16F uploaded (tex=%u, %.1f MB)",
         w, h, tex, double(size_t(w) * size_t(h) * 8) / (1024.0 * 1024.0));
    return tex;
}

void OffscreenSaveRenderer::releaseTexture(unsigned int tex) {
    if (!tex) return;
    if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT) return;
    GLuint t = GLuint(tex);
    glDeleteTextures(1, &t);
}

bool OffscreenSaveRenderer::renderGradedToRgba8(
        const uint16_t* srcFp16, int srcW, int srcH,
        const float* params, int paramsCount,
        const float* lutRgb, int lutSize,
        const float lutDomainMin[3], const float lutDomainMax[3],
        const uint8_t* toneCurve768,
        const float* subjectMask, int maskW, int maskH,
        const float subjectMaskRect[4],
        const float* brushMaskLayers, int brushMaskW, int brushMaskH,
        int brushMaskCount,
        uint8_t* outRgba) {
    if (!srcFp16 || !outRgba || !params || paramsCount < 57 || srcW <= 0 || srcH <= 0) {
        LOGE("renderGradedToRgba8: bad args");
        return false;
    }
    if (!ensureContext()) {
        LOGE("renderGradedToRgba8: ensureContext failed");
        return false;
    }

    auto t0 = std::chrono::steady_clock::now();
    ShaderParams sp = ShaderParams::fromFloatArray(params, paramsCount);

    GLint maxTex = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
    if (srcW > maxTex || srcH > maxTex) {
        LOGE("renderGradedToRgba8: %dx%d exceeds GL_MAX_TEXTURE_SIZE=%d",
             srcW, srcH, maxTex);
        return false;
    }

    // ── Compile canvas uber-shader (same strings as live preview) ──
    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertSrcSnapshot);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragSrc);
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return false;
    }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint linked = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[2048]{};
        glGetProgramInfoLog(prog, sizeof(log), nullptr, log);
        LOGE("renderGradedToRgba8: link failed: %s", log);
        glDeleteProgram(prog);
        return false;
    }

    GLuint vao = 0;
    glGenVertexArrays(1, &vao);

    glUseProgram(prog);
    struct { const char* name; int unit; } kUnits[] = {
        {"uTex", 0}, {"uLutTex", 1}, {"uSubjectMask", 2}, {"uBrushMask", 3},
        {"uSobelEdgeMask", 4}, {"uBrushMask1", 5}, {"uBrushMask2", 6},
        {"uBrushMask3", 7}, {"uBlurTex", 8}, {"uToneCurveTex", 9},
        {"uBokehAttenuation", 10}, {"uBloomTex", 11}, {"uCurveMasterTex", 12},
        {"uCurveRTex", 13}, {"uCurveGTex", 14}, {"uCurveBTex", 15},
    };
    for (const auto& u : kUnits) {
        GLint loc = glGetUniformLocation(prog, u.name);
        if (loc >= 0) glUniform1i(loc, u.unit);
    }

    GLuint srcTex = uploadSourceFp16(srcFp16, srcW, srcH);
    if (!srcTex) {
        glDeleteVertexArrays(1, &vao);
        glDeleteProgram(prog);
        return false;
    }

    // 1×1 neutral fillers for unused sampler units (parity with preview
    // binding a ready texture rather than leaving unit 0 unbound).
    auto make1x1 = [](float r, float g, float b, float a) -> GLuint {
        GLuint t = 0;
        glGenTextures(1, &t);
        const float px[4] = {r, g, b, a};
        glBindTexture(GL_TEXTURE_2D, t);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, 1, 1, 0, GL_RGBA, GL_FLOAT, px);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return t;
    };
    GLuint blackTex = make1x1(0.f, 0.f, 0.f, 1.f);
    GLuint whiteMask = make1x1(1.f, 1.f, 1.f, 1.f);

    // ── Karis bloom plane (same GPU shaders as canvas) ──
    const bool ortonOn = sp.ortonStrength > 0.f || sp.subjectBloom > 0.f;
    const bool glowOn  = sp.fxGlowStrength > 0.f;
    const bool bloomNeeded = (ortonOn && sp.bloomRadius > 0.f) || glowOn;
    GLuint bloomTex = blackTex;
    std::vector<float> bloomOwned;
    GLuint bloomOwnedTex = 0;
    if (bloomNeeded) {
        // Decode FP16 → float RGB for Karis (full source; Karis itself may
        // downscale internally via computeKarisBloomEditorMatched callers —
        // here we run at full res when maxTex allows, matching canvas look).
        auto h2f = [](uint16_t h) -> float {
            const uint32_t sign  = uint32_t(h & 0x8000u) << 16;
            const uint32_t exp16 = (h >> 10) & 0x1Fu;
            const uint32_t mant  = h & 0x3FFu;
            uint32_t r32;
            if (exp16 == 0)       r32 = sign | (mant == 0 ? 0u : (((1u + 127u - 15u) << 23) | (mant << 13)));
            else if (exp16 == 31) r32 = sign | 0x7F800000u | (mant << 13);
            else                  r32 = sign | ((exp16 + (127u - 15u)) << 23) | (mant << 13);
            float f; std::memcpy(&f, &r32, 4); return f;
        };
        std::vector<float> srcRgb(size_t(srcW) * srcH * 3);
        for (int i = 0, n = srcW * srcH; i < n; ++i) {
            srcRgb[size_t(i) * 3 + 0] = h2f(srcFp16[size_t(i) * 4 + 0]);
            srcRgb[size_t(i) * 3 + 1] = h2f(srcFp16[size_t(i) * 4 + 1]);
            srcRgb[size_t(i) * 3 + 2] = h2f(srcFp16[size_t(i) * 4 + 2]);
        }
        bloomOwned.resize(size_t(srcW) * srcH * 3);
        const float baseTent = 1.0f + sp.bloomRadius * 0.22f;
        const int longSide = std::max(srcW, srcH);
        // Cap Karis working long-side like the editor (~2048) when source is
        // huge, but upsample bloom back to full res so the graded draw is
        // full-res with canvas-matched bloom character.
        constexpr int kEditorMax = 2048;
        int workW = srcW, workH = srcH;
        if (longSide > kEditorMax) {
            const float s = float(kEditorMax) / float(longSide);
            workW = std::max(4, int(std::lround(float(srcW) * s)));
            workH = std::max(4, int(std::lround(float(srcH) * s)));
        }
        const float tent = baseTent * float(std::max(workW, workH)) / 1080.0f;
        bool bloomOk = false;
        if (workW == srcW && workH == srcH) {
            bloomOk = computeKarisBloom(srcRgb.data(), srcW, srcH, 0.65f, tent,
                                        bloomOwned.data(), sp.mistTightness,
                                        sp.bloomShape);
        } else {
            std::vector<float> small(size_t(workW) * workH * 3);
            std::vector<float> bloomSmall(size_t(workW) * workH * 3);
            for (int y = 0; y < workH; ++y) {
                const float fy = (workH > 1)
                    ? float(y) * float(srcH - 1) / float(workH - 1) : 0.f;
                const int y0 = int(fy);
                const int y1 = std::min(y0 + 1, srcH - 1);
                const float wy = fy - float(y0);
                for (int x = 0; x < workW; ++x) {
                    const float fx = (workW > 1)
                        ? float(x) * float(srcW - 1) / float(workW - 1) : 0.f;
                    const int x0 = int(fx);
                    const int x1 = std::min(x0 + 1, srcW - 1);
                    const float wx = fx - float(x0);
                    for (int c = 0; c < 3; ++c) {
                        const float s00 = srcRgb[(size_t(y0) * srcW + x0) * 3 + c];
                        const float s10 = srcRgb[(size_t(y0) * srcW + x1) * 3 + c];
                        const float s01 = srcRgb[(size_t(y1) * srcW + x0) * 3 + c];
                        const float s11 = srcRgb[(size_t(y1) * srcW + x1) * 3 + c];
                        small[(size_t(y) * workW + x) * 3 + c] =
                            (s00 * (1.f - wx) + s10 * wx) * (1.f - wy) +
                            (s01 * (1.f - wx) + s11 * wx) * wy;
                    }
                }
            }
            bloomOk = computeKarisBloom(small.data(), workW, workH, 0.65f, tent,
                                        bloomSmall.data(), sp.mistTightness,
                                        sp.bloomShape);
            if (bloomOk) {
                for (int y = 0; y < srcH; ++y) {
                    const float fy = (srcH > 1)
                        ? float(y) * float(workH - 1) / float(srcH - 1) : 0.f;
                    const int y0 = int(fy);
                    const int y1 = std::min(y0 + 1, workH - 1);
                    const float wy = fy - float(y0);
                    for (int x = 0; x < srcW; ++x) {
                        const float fx = (srcW > 1)
                            ? float(x) * float(workW - 1) / float(srcW - 1) : 0.f;
                        const int x0 = int(fx);
                        const int x1 = std::min(x0 + 1, workW - 1);
                        const float wx = fx - float(x0);
                        for (int c = 0; c < 3; ++c) {
                            const float s00 = bloomSmall[(size_t(y0) * workW + x0) * 3 + c];
                            const float s10 = bloomSmall[(size_t(y0) * workW + x1) * 3 + c];
                            const float s01 = bloomSmall[(size_t(y1) * workW + x0) * 3 + c];
                            const float s11 = bloomSmall[(size_t(y1) * workW + x1) * 3 + c];
                            bloomOwned[(size_t(y) * srcW + x) * 3 + c] =
                                (s00 * (1.f - wx) + s10 * wx) * (1.f - wy) +
                                (s01 * (1.f - wx) + s11 * wx) * wy;
                        }
                    }
                }
            }
        }
        if (bloomOk) {
            // Soft-diff bake into Karis (dedicated Gaussian — not uBlurTex).
            if (ortonOn || glowOn) {
                const float softR = 2.0f + std::max(0.f, std::min(24.f, sp.bloomRadius)) * 1.15f;
                const int longSide = std::max(srcW, srcH);
                int softRadiusPx = std::max(2, int(std::lround(softR * float(longSide) / 2560.f)));
                std::vector<float> softBuf(size_t(srcW) * srcH * 3);
                gaussianBlurSeparableShared(srcRgb.data(), softBuf.data(), srcW, srcH, softRadiusPx);
                bakeSoftDiffusionIntoBloom(bloomOwned.data(), softBuf.data(),
                                           srcW * srcH, sp.ortonStrength, sp.fxGlowStrength);
            }
            glGenTextures(1, &bloomOwnedTex);
            glBindTexture(GL_TEXTURE_2D, bloomOwnedTex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB16F, srcW, srcH, 0,
                         GL_RGB, GL_FLOAT, bloomOwned.data());
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            bloomTex = bloomOwnedTex;
            LOGI("renderGradedToRgba8: Karis bloom ok work=%dx%d → full %dx%d",
                 workW, workH, srcW, srcH);
        } else {
            LOGE("renderGradedToRgba8: Karis bloom failed — bloom unit stays black");
        }
    }

    // Blur tex for Ambiance/Bokeh/Clarity — NOT softDiff (already in bloom).
    // Prefer a neutral stand-in; full-res Gaussian is too heavy for this path.
    GLuint blurTex = srcTex;

    // ── Optional 3D LUT ──
    GLuint lutTex = 0;
    bool lutOk = false;
    if (lutRgb && lutSize >= 2) {
        glGenTextures(1, &lutTex);
        glBindTexture(GL_TEXTURE_3D, lutTex);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB16F, lutSize, lutSize, lutSize, 0,
                     GL_RGB, GL_FLOAT, lutRgb);
        lutOk = glGetError() == GL_NO_ERROR;
        if (!lutOk) {
            LOGE("renderGradedToRgba8: LUT upload failed");
            glDeleteTextures(1, &lutTex);
            lutTex = 0;
        }
    }
    if (!lutTex) {
        // Identity 2³ LUT so the sampler is valid when lutEnabled is off.
        glGenTextures(1, &lutTex);
        float id[2 * 2 * 2 * 3];
        for (int z = 0; z < 2; ++z)
            for (int y = 0; y < 2; ++y)
                for (int x = 0; x < 2; ++x) {
                    const int i = ((z * 2 + y) * 2 + x) * 3;
                    id[i] = float(x); id[i + 1] = float(y); id[i + 2] = float(z);
                }
        glBindTexture(GL_TEXTURE_3D, lutTex);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB16F, 2, 2, 2, 0, GL_RGB, GL_FLOAT, id);
    }

    // ── Tone curve 256×1 RGB8 ──
    GLuint toneTex = 0;
    bool toneReady = false;
    if (toneCurve768) {
        glGenTextures(1, &toneTex);
        glBindTexture(GL_TEXTURE_2D, toneTex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, 256, 1, 0,
                     GL_RGB, GL_UNSIGNED_BYTE, toneCurve768);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        toneReady = true;
    } else {
        toneTex = whiteMask;
    }

    // ── Subject mask ──
    GLuint subjTex = whiteMask;
    GLuint subjOwned = 0;
    bool subjReady = false;
    if (subjectMask && maskW > 0 && maskH > 0) {
        glGenTextures(1, &subjOwned);
        glBindTexture(GL_TEXTURE_2D, subjOwned);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R16F, maskW, maskH, 0,
                     GL_RED, GL_FLOAT, subjectMask);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        subjTex = subjOwned;
        subjReady = true;
    }

    // ── Output FBO at full source resolution ──
    GLuint fbo = 0, fboTex = 0;
    glGenTextures(1, &fboTex);
    glBindTexture(GL_TEXTURE_2D, fboTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, srcW, srcH, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, fboTex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("renderGradedToRgba8: FBO incomplete %dx%d", srcW, srcH);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &fboTex);
        releaseTexture(srcTex);
        if (bloomOwnedTex) glDeleteTextures(1, &bloomOwnedTex);
        if (lutTex) glDeleteTextures(1, &lutTex);
        if (toneReady) glDeleteTextures(1, &toneTex);
        if (subjOwned) glDeleteTextures(1, &subjOwned);
        glDeleteTextures(1, &blackTex);
        glDeleteTextures(1, &whiteMask);
        glDeleteVertexArrays(1, &vao);
        glDeleteProgram(prog);
        return false;
    }

    GradingInputs in;
    in.texW = srcW;
    in.texH = srcH;
    in.lutUploaded = lutOk;
    in.lutSize = lutOk ? lutSize : 2;
    if (lutDomainMin) {
        in.lutDomainMin[0] = lutDomainMin[0];
        in.lutDomainMin[1] = lutDomainMin[1];
        in.lutDomainMin[2] = lutDomainMin[2];
    }
    if (lutDomainMax) {
        in.lutDomainMax[0] = lutDomainMax[0];
        in.lutDomainMax[1] = lutDomainMax[1];
        in.lutDomainMax[2] = lutDomainMax[2];
    }
    in.toneCurveReady = toneReady;
    in.subjectMaskReady = subjReady;
    if (subjectMaskRect) {
        in.subjectMaskRect[0] = subjectMaskRect[0];
        in.subjectMaskRect[1] = subjectMaskRect[1];
        in.subjectMaskRect[2] = subjectMaskRect[2];
        in.subjectMaskRect[3] = subjectMaskRect[3];
    }

    glViewport(0, 0, srcW, srcH);
    glUseProgram(prog);
    glBindVertexArray(vao);

    glActiveTexture(GL_TEXTURE0);  glBindTexture(GL_TEXTURE_2D, srcTex);
    glActiveTexture(GL_TEXTURE1);  glBindTexture(GL_TEXTURE_3D, lutTex);
    glActiveTexture(GL_TEXTURE2);  glBindTexture(GL_TEXTURE_2D, subjTex);
    glActiveTexture(GL_TEXTURE3);  glBindTexture(GL_TEXTURE_2D, blackTex);
    glActiveTexture(GL_TEXTURE4);  glBindTexture(GL_TEXTURE_2D, blackTex);
    glActiveTexture(GL_TEXTURE5);  glBindTexture(GL_TEXTURE_2D, blackTex);
    glActiveTexture(GL_TEXTURE6);  glBindTexture(GL_TEXTURE_2D, blackTex);
    glActiveTexture(GL_TEXTURE7);  glBindTexture(GL_TEXTURE_2D, blackTex);
    glActiveTexture(GL_TEXTURE8);  glBindTexture(GL_TEXTURE_2D, blurTex);
    glActiveTexture(GL_TEXTURE9);  glBindTexture(GL_TEXTURE_2D, toneTex);
    glActiveTexture(GL_TEXTURE10); glBindTexture(GL_TEXTURE_2D, blackTex);
    glActiveTexture(GL_TEXTURE11); glBindTexture(GL_TEXTURE_2D, bloomTex);
    glActiveTexture(GL_TEXTURE12); glBindTexture(GL_TEXTURE_2D, blackTex);
    glActiveTexture(GL_TEXTURE13); glBindTexture(GL_TEXTURE_2D, blackTex);
    glActiveTexture(GL_TEXTURE14); glBindTexture(GL_TEXTURE_2D, blackTex);
    glActiveTexture(GL_TEXTURE15); glBindTexture(GL_TEXTURE_2D, blackTex);

    pushGradingUniforms(prog, sp, in);

    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glFinish();

    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, srcW, srcH, GL_RGBA, GL_UNSIGNED_BYTE, outRgba);
    const GLenum err = glGetError();
    const bool ok = (err == GL_NO_ERROR);
    if (!ok) LOGE("renderGradedToRgba8: readback GL err=0x%x", err);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &fboTex);
    releaseTexture(srcTex);
    if (bloomOwnedTex) glDeleteTextures(1, &bloomOwnedTex);
    if (lutTex) glDeleteTextures(1, &lutTex);
    if (toneReady) glDeleteTextures(1, &toneTex);
    if (subjOwned) glDeleteTextures(1, &subjOwned);
    glDeleteTextures(1, &blackTex);
    glDeleteTextures(1, &whiteMask);
    glDeleteVertexArrays(1, &vao);
    glDeleteProgram(prog);

    auto t1 = std::chrono::steady_clock::now();
    LOGI("renderGradedToRgba8: %s %dx%d in %lld ms (GPU canvas-matched export)",
         ok ? "ok" : "FAIL", srcW, srcH,
         (long long) std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count());
    return ok;
}

void OffscreenSaveRenderer::release() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (surface_ != EGL_NO_SURFACE) {
        eglDestroySurface(display_, surface_);
        surface_ = EGL_NO_SURFACE;
    }
    if (context_ != EGL_NO_CONTEXT) {
        eglDestroyContext(display_, context_);
        context_ = EGL_NO_CONTEXT;
    }
    if (display_ != EGL_NO_DISPLAY) {
        eglTerminate(display_);
        display_ = EGL_NO_DISPLAY;
    }
}

namespace {

inline float halfToFloatOff(uint16_t h) {
    const uint32_t sign  = uint32_t(h & 0x8000u) << 16;
    const uint32_t exp16 = (h >> 10) & 0x1Fu;
    const uint32_t mant  = h & 0x3FFu;
    uint32_t r32;
    if (exp16 == 0) {
        r32 = sign | (mant == 0 ? 0u : (((1u + 127u - 15u) << 23) | (mant << 13)));
    } else if (exp16 == 31) {
        r32 = sign | 0x7F800000u | (mant << 13);
    } else {
        r32 = sign | ((exp16 + (127u - 15u)) << 23) | (mant << 13);
    }
    float f;
    std::memcpy(&f, &r32, 4);
    return f;
}

inline uint16_t floatToHalfOff(float f) {
    uint32_t x;
    std::memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000u;
    int32_t exp = int32_t((x >> 23) & 0xFF) - 127 + 15;
    uint32_t mant = x & 0x7FFFFFu;
    if (exp <= 0) return uint16_t(sign);
    if (exp >= 0x1F) return uint16_t(sign | 0x7C00u);
    return uint16_t(sign | (uint32_t(exp) << 10) | (mant >> 13));
}

}  // namespace

bool downsampleRgbaFp16Bilinear(
        const uint16_t* src, int sw, int sh,
        uint16_t* dst, int dw, int dh) {
    if (!src || !dst || sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return false;
    if (dw > sw || dh > sh) return false;
    if (dw == sw && dh == sh) {
        std::memcpy(dst, src, size_t(sw) * size_t(sh) * 4 * sizeof(uint16_t));
        return true;
    }
    for (int y = 0; y < dh; ++y) {
        const float fy = (dh > 1)
            ? float(y) * float(sh - 1) / float(dh - 1) : 0.f;
        const int y0 = int(fy);
        const int y1 = std::min(y0 + 1, sh - 1);
        const float wy = fy - float(y0);
        for (int x = 0; x < dw; ++x) {
            const float fx = (dw > 1)
                ? float(x) * float(sw - 1) / float(dw - 1) : 0.f;
            const int x0 = int(fx);
            const int x1 = std::min(x0 + 1, sw - 1);
            const float wx = fx - float(x0);
            uint16_t* o = dst + (size_t(y) * dw + x) * 4;
            for (int c = 0; c < 4; ++c) {
                const float s00 = halfToFloatOff(src[(size_t(y0) * sw + x0) * 4 + c]);
                const float s10 = halfToFloatOff(src[(size_t(y0) * sw + x1) * 4 + c]);
                const float s01 = halfToFloatOff(src[(size_t(y1) * sw + x0) * 4 + c]);
                const float s11 = halfToFloatOff(src[(size_t(y1) * sw + x1) * 4 + c]);
                const float v =
                    (s00 * (1.f - wx) + s10 * wx) * (1.f - wy) +
                    (s01 * (1.f - wx) + s11 * wx) * wy;
                o[c] = floatToHalfOff(v);
            }
        }
    }
    return true;
}

bool downsampleStageATiffToSize(
        const std::string& srcPath,
        const std::string& dstPath,
        int dw, int dh) {
    if (dw <= 0 || dh <= 0 || srcPath.empty() || dstPath.empty()) return false;
    StageATiffReader* rd = openStageATiff(srcPath);
    if (!rd) {
        LOGE("downsampleStageATiffToSize: open failed %s", srcPath.c_str());
        return false;
    }
    const StageATiffHeader hdr = getStageATiffHeader(rd);
    const int sw = int(hdr.width), sh = int(hdr.height);
    if (sw <= 0 || sh <= 0 || !stageATiffPayloadInBounds(rd)) {
        closeStageATiff(rd);
        return false;
    }
    const int outW = std::min(dw, sw);
    const int outH = std::min(dh, sh);
    const uint16_t* fp16 = getStageATiffStrip(rd, 0);
    if (!fp16) {
        closeStageATiff(rd);
        return false;
    }
    std::vector<uint16_t> dst(size_t(outW) * size_t(outH) * 4);
    // Box/area average when reducing a lot (fewer ringing artefacts than
    // bilinear for CPU Stage C fallback); bilinear for mild shrinks.
    const float shrink = float(std::max(sw, sh)) / float(std::max(outW, outH));
    bool ok = false;
    if (shrink > 1.5f) {
        const uint32_t rps = hdr.rowsPerStrip ? hdr.rowsPerStrip : TIFF_ROWS_PER_STRIP;
        for (int dy = 0; dy < outH; ++dy) {
            const uint32_t y0 = uint32_t(uint64_t(dy) * sh / outH);
            uint32_t y1 = uint32_t(uint64_t(dy + 1) * sh / outH);
            if (y1 <= y0) y1 = y0 + 1;
            for (int dx = 0; dx < outW; ++dx) {
                const uint32_t x0 = uint32_t(uint64_t(dx) * sw / outW);
                uint32_t x1 = uint32_t(uint64_t(dx + 1) * sw / outW);
                if (x1 <= x0) x1 = x0 + 1;
                float acc[4] = {0, 0, 0, 0};
                uint32_t n = 0;
                for (uint32_t y = y0; y < y1; ++y) {
                    const uint16_t* strip = getStageATiffStrip(rd, y / rps);
                    if (!strip) continue;
                    const uint16_t* row = strip + size_t(y % rps) * size_t(sw) * 4;
                    for (uint32_t x = x0; x < x1; ++x) {
                        const uint16_t* px = row + size_t(x) * 4;
                        acc[0] += halfToFloatOff(px[0]);
                        acc[1] += halfToFloatOff(px[1]);
                        acc[2] += halfToFloatOff(px[2]);
                        acc[3] += halfToFloatOff(px[3]);
                        ++n;
                    }
                }
                uint16_t* o = dst.data() + (size_t(dy) * outW + dx) * 4;
                const float inv = n ? 1.f / float(n) : 0.f;
                o[0] = floatToHalfOff(acc[0] * inv);
                o[1] = floatToHalfOff(acc[1] * inv);
                o[2] = floatToHalfOff(acc[2] * inv);
                o[3] = floatToHalfOff(n ? acc[3] * inv : 1.f);
            }
        }
        ok = true;
    } else {
        ok = downsampleRgbaFp16Bilinear(fp16, sw, sh, dst.data(), outW, outH);
    }
    closeStageATiff(rd);
    if (!ok) return false;
    if (!writeStageATiff(dstPath, dst.data(), uint32_t(outW), uint32_t(outH))) {
        LOGE("downsampleStageATiffToSize: write failed %s", dstPath.c_str());
        return false;
    }
    LOGI("downsampleStageATiffToSize: %dx%d → %dx%d", sw, sh, outW, outH);
    return true;
}

}  // namespace raw_v3
