/*
 * razparity — GLSL-preview ↔ CPU-export parity harness (hard rule #1, as a tool).
 *
 * Runs the SAME ShaderParams float blob through both grading engines:
 *   GPU: kFragSrc (shader_sources.cpp) + pushGradingUniforms (grading_uniforms.cpp)
 *        in a headless ANGLE pbuffer context (OffscreenSaveRenderer::init).
 *   CPU: ApplyMacroParams::fromFloatArray + applyMacroPixel (apply_macro.cpp).
 * ...against a synthetic 256×256 hue×luma sweep, then compares outputs with
 * MaxAbsError / MeanAbsError / PSNR. On failure it dumps an amplified diff-map
 * PNG (via the existing WIC encoder) plus both renders, for eyeballing.
 *
 * SCOPE (v1) — the deterministic grading core:
 *   exposure/contrast/tone, WB/tint, sat/vibrance, HSL, color-grade wheels,
 *   dehaze, vignette, tonal-zone WB trims, gamut compress, filmRolloff,
 *   center pop, colorDensity/skintone, pushPull, highlightRecovery.
 * EXCLUDED (stochastic, blur-dependent, or textured — zeroed in every test):
 *   film grain/dust (RNG), dither, LUT (GPU trilinear vs CPU interpolation),
 *   ambiance/Orton/mist/glow/FX blurs (need uBlurTex/uBloomTex inputs),
 *   bokeh, tone curves (uCurvesEnabled=0 path), clarity/clarityLift (spatial
 *   pre-pass — same C++ on both paths, nothing to compare), masks.
 *
 * Thresholds: MaxAbsError < 0.005 in the shader's output domain (gamma sRGB,
 * [0,1]) and PSNR > 45 dB — perceptually lossless. GPU FMA/fast-transcendental
 * drift sits well under this; a missed mirror shows up orders of magnitude over.
 *
 * Exit code: 0 = all tests pass, 1 = any failure, 2 = harness/GL setup error.
 */

#include "v3/offscreen_save_renderer.h"
#include "v3/shader_sources.h"
#include "v3/grading_uniforms.h"
#include "v3/gles_renderer.h"     // ShaderParams::fromFloatArray
#include "v3/apply_macro.h"
#include "desktop/wic_encoder.h"

#include <GLES3/gl3.h>

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

extern "C" bool razparity_load_angle();

// compat/android/log.h routes __android_log_print through this gate; every
// desktop exe defines it once (razbatch_main / harness_main do the same).
extern "C" { int raz_log_enabled = 1; }

namespace {

constexpr int W = 256;
constexpr int H = 256;
// MUST track ShaderParams.kt FLOAT_COUNT. At 410 it was 25 slots short of
// what Kotlin already sent: a test writing a[435] overran `float
// blob[kParamCount]` on the stack, and every param above 409 silently read
// back as 0 — so a case exercising one of them PASSED while testing
// nothing at all (both sides were no-ops and therefore agreed).
constexpr int kParamCount = 451;   // ShaderParams.kt FLOAT_COUNT

// ── Synthetic test image ─────────────────────────────────────────────────────
// Gamma-encoded sRGB in [0,1], the domain Stage A's FP16 cache holds and both
// engines expect. Rows 0..191: hue×luma sweep (S=0.85). Rows 192..223: neutral
// gray ramp. Rows 224..239: R↔B cross-fade at G=0.5. Rows 240..255: histogram
// extremes (near-black / near-white ramps) — where AE/tone bugs hide.
void hsvToRgb(float h, float s, float v, float& r, float& g, float& b) {
    h = h - std::floor(h);
    float i = std::floor(h * 6.f);
    float f = h * 6.f - i;
    float p = v * (1.f - s);
    float q = v * (1.f - f * s);
    float t = v * (1.f - (1.f - f) * s);
    switch (((int) i) % 6) {
        case 0: r = v; g = t; b = p; break;
        case 1: r = q; g = v; b = p; break;
        case 2: r = p; g = v; b = t; break;
        case 3: r = p; g = q; b = v; break;
        case 4: r = t; g = p; b = v; break;
        default: r = v; g = p; b = q; break;
    }
}

void buildSweep(std::vector<float>& rgbaF32) {
    rgbaF32.assign((size_t) W * H * 4, 1.f);
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            float u = (x + 0.5f) / W;
            float r, g, b;
            if (y < 192) {
                hsvToRgb(u, 0.85f, (y + 0.5f) / 192.f, r, g, b);
            } else if (y < 224) {
                r = g = b = u;
            } else if (y < 240) {
                r = u; g = 0.5f; b = 1.f - u;
            } else if (y < 248) {
                r = g = b = 0.004f + 0.06f * u;       // deep shadows
            } else {
                r = g = b = 0.94f + 0.06f * u;        // near-clip highlights
            }
            float* px = &rgbaF32[((size_t) y * W + x) * 4];
            px[0] = r; px[1] = g; px[2] = b; px[3] = 1.f;
        }
    }
}

void toFp16(const std::vector<float>& src, std::vector<uint16_t>& dst) {
    dst.resize(src.size());
    for (size_t i = 0; i < src.size(); ++i) {
        __fp16 h = (__fp16) src[i];
        uint16_t bits;
        std::memcpy(&bits, &h, sizeof(bits));
        dst[i] = bits;
    }
}

// ── Neutral params blob ──────────────────────────────────────────────────────
// Functionally-neutral 410-float blob. Parity holds for ANY blob (both engines
// read the same array), but a black render proves nothing — so every scale/
// opacity/identity slot gets its real neutral, mirroring ShaderParams.kt
// defaults. Slots not listed are neutral at 0.
void setNeutral(float* a) {
    std::memset(a, 0, sizeof(float) * kParamCount);
    a[57] = a[58] = a[59] = 1.f;          // light/color/xmp tab opacities
    a[62] = a[63] = 0.5f;                 // vignette center
    a[64] = 0.5f;                         // vignette feather
    a[65] = 1.f;                          // vignette intensity
    a[67] = 1.f;                          // vig tab opacity
    a[129] = 1.f;                         // gradient tab opacity
    a[140] = 1.f;                         // mask tab opacity (layer 0)
    a[163] = a[170] = a[177] = 1.f;       // mask layers 1..3 opacity
    a[154] = 0.5f;                        // grain size (inert at grain=0)
    a[206] = 1.f;                         // bloomShape
    a[235] = 1.f;                         // workspaceSpace = sRGB
    a[288 - 0] = 0.f;                     // (documentation anchor — no-op)
    a[358] = a[359] = 0.5f;               // fxRadBlur center
    a[361] = a[362] = 0.5f;               // fxZoomBlur center
    a[376] = 1.f;                         // haxGrainScale
    a[377] = 1.f;                         // haxGrainLumaAmp
    a[380] = 1.f;                         // viewZoom — 0 would collapse UVs
    a[386] = a[388] = a[390] = 1.f;       // smartWb maxes
    // Color-grade wheels: 0.5 = no shift.
    for (int s : {240, 241, 242, 244, 245, 246, 248, 249, 250}) a[s] = 0.5f;
    // Curve control points: identity ((i/2)/7 pairs), matching fromFloatArray's
    // absent-slot default. The blob carries them EXPLICITLY because at
    // count=410 the fallback never triggers.
    for (int i = 0; i < 16; ++i) {
        float def = (float) (i / 2) / 7.f;
        a[277 + i] = def; a[293 + i] = def; a[309 + i] = def; a[325 + i] = def;
    }
}

struct TestCase {
    const char* name;
    void (*mutate)(float* a);
    // Whether this case feeds the 256-entry tone curve to BOTH engines. It is
    // not a params slot: the shader takes it from GradingInputs.toneCurveReady
    // and the CPU kernel from ApplyMacroParams::toneCurveLut, both set by the
    // caller (Stage C in production, this harness here).
    bool toneCurve = false;
};

// Values are in the WIRE format ShaderParams.kt/RawV3ActionReplay emit:
// exposure = raw EV [-4..4]; contrast/tone/sat/vib/HSL sat+lum/dehaze/vignette
// normalised to [-1..1] (UI /100); WB Kelvin-delta /2500; tint /200; HSL hue
// /180. Out-of-domain values hit each engine's clamps differently and prove
// nothing — keep every slot inside its production range.
const TestCase kTests[] = {
    {"neutral-passthrough", [](float*) {}},
    {"exposure+contrast", [](float* a) {
        a[0] = 1.6f;      // exposure, raw EV
        a[1] = 0.45f;     // contrast
    }},
    {"tone-extremes", [](float* a) {
        a[2] = -0.8f;     // highlights
        a[3] = 0.7f;      // shadows
        a[4] = 0.3f;      // whites
        a[5] = -0.35f;    // blacks
    }},
    {"whitebalance", [](float* a) { a[8] = -0.36f; }},
    {"tint",         [](float* a) { a[9] = 0.125f; }},
    {"saturation",   [](float* a) { a[6] = 0.45f; }},
    {"vibrance",     [](float* a) { a[7] = 0.55f; }},
    {"hsl-bands", [](float* a) {
        a[10] = 30.f / 180.f;  a[11] = -0.20f; a[12] = 0.15f;   // band 0 h/s/l
        a[16] = -40.f / 180.f; a[17] = 0.35f;  a[18] = -0.25f;  // band 2
        a[22] = 20.f / 180.f;  a[23] = 0.50f;  a[24] = 0.10f;   // band 4
    }},
    {"cg-shadows", [](float* a) {
        a[240] = 0.62f; a[241] = 0.48f; a[242] = 0.40f; a[243] = 0.6f;
    }},
    {"cg-midtones", [](float* a) {
        a[244] = 0.50f; a[245] = 0.55f; a[246] = 0.45f; a[247] = 0.4f;
    }},
    {"cg-highlights", [](float* a) {
        a[248] = 0.42f; a[249] = 0.50f; a[250] = 0.60f; a[251] = 0.5f;
    }},
    {"dehaze", [](float* a) {
        a[60] = 0.4f;     // dehaze (per-pixel dark-channel model — no blur dep)
    }},
    {"vignette", [](float* a) {
        a[61] = -0.55f;   // vignette amount
    }},
    {"zone-wb-trims+gamut", [](float* a) {
        a[201] = 0.4f;    // highlightTemperature
        a[202] = -0.3f;   // highlightTint
        a[203] = -0.35f;  // shadowTemperature
        a[204] = 0.25f;   // shadowTint
        a[237] = 1.f;     // gamutCompress
    }},
    // centerPop [252] deliberately absent: it samples uBlurTex (tonalBlur),
    // which the harness does not populate — blur-dependent ops are out of
    // v1 scope alongside ambiance/Orton/mist/glow.
    {"film-rolloff", [](float* a) {
        a[207] = 0.7f;    // filmRolloff
    }},
    {"color-density", [](float* a) { a[343] = 0.6f; }},
    {"push-pull",     [](float* a) { a[349] = 0.8f; }},
    {"hl-recovery",   [](float* a) { a[348] = 0.7f; }},
    // Tone curve — slot [210] picks per-channel vs luma; the curve itself rides
    // the TestCase::toneCurve flag (there is no "enabled" slot). Added
    // 2026-09-07 with the CPU luma-curve fix: the suite had no curve case at
    // all, so apply_macro.cpp silently lacked the shader's luma branch.
    {"tone-curve-per-channel", [](float* a) { a[210] = 0.f; }, true},
    {"tone-curve-luma",        [](float* a) { a[210] = 1.f; }, true},
    // Film response (LUT tab): Adobe Recovery [435] / FillLight [436] and the
    // B&W GrayMixer [437 gate, 438..445 bands]. Both directions are covered
    // because the controls are bipolar (Adobe's own are one-directional).
    {"film-recovery-fill",  [](float* a) { a[436] =  0.7f; a[437] =  0.6f; }},
    {"film-contrast",       [](float* a) { a[436] = -0.7f; a[437] = -0.6f; }},
    // Mixer channels are deliberately asymmetric and span the hue circle, so a
    // band-weight regression (wrong centres, or hard binning instead of the
    // cosine blend) cannot pass by accident.
    {"film-graymixer",      [](float* a) {
        a[438] = 1.f;
        a[439] =  0.8f;  a[440] = -0.5f; a[441] =  0.35f; a[442] = -0.7f;
        a[443] =  0.55f; a[444] = -0.9f; a[445] =  0.25f; a[446] = -0.4f;
    }},
};

// 256-entry RGB tone curve (interleaved R,G,B — the shipping layout). A gentle
// S-curve, per-channel asymmetric so a luma-mode regression cannot pass by
// accident: with the LUMA branch every channel shifts by the SAME delta, so an
// implementation that fell back to per-channel would diverge visibly here.
const uint8_t* toneCurveLut() {
    static uint8_t lut[256 * 3];
    static bool built = false;
    if (!built) {
        for (int i = 0; i < 256; ++i) {
            const float x = i / 255.f;
            const float s = x * x * (3.f - 2.f * x);            // smoothstep S
            const float r = s * 1.00f;
            const float g = x + (s - x) * 0.60f;                // weaker S
            const float b = x + (s - x) * 1.20f;                // stronger S
            auto q = [](float v) -> uint8_t {
                const float c = v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
                return (uint8_t) (c * 255.f + 0.5f);
            };
            lut[i * 3 + 0] = q(r);
            lut[i * 3 + 1] = q(g);
            lut[i * 3 + 2] = q(b);
        }
        built = true;
    }
    return lut;
}

/** Set for the duration of one case by main(); see TestCase::toneCurve. */
bool g_toneCurveCase = false;

// ── GL render of one params blob ─────────────────────────────────────────────
GLuint compileShader(GLenum type, const char* src) {
    GLuint sh = glCreateShader(type);
    glShaderSource(sh, 1, &src, nullptr);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4096];
        glGetShaderInfoLog(sh, sizeof(log), nullptr, log);
        std::fprintf(stderr, "[gl] shader compile failed:\n%s\n", log);
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

struct GlHarness {
    raw_v3::OffscreenSaveRenderer renderer;
    GLuint prog = 0;
    GLuint vao = 0;
    GLuint srcTex = 0;
    GLuint fbo = 0;
    GLuint fboTex = 0;

    bool init(const std::vector<uint16_t>& srcFp16) {
        if (!renderer.init(W, H)) {
            std::fprintf(stderr, "[gl] OffscreenSaveRenderer::init failed\n");
            return false;
        }
        std::printf("[gl] GL_VERSION:  %s\n", (const char*) glGetString(GL_VERSION));
        std::printf("[gl] GL_RENDERER: %s\n", (const char*) glGetString(GL_RENDERER));

        GLuint vs = compileShader(GL_VERTEX_SHADER, kVertSrcSnapshot);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragSrc);
        if (!vs || !fs) return false;
        prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);
        glDeleteShader(vs);
        glDeleteShader(fs);
        GLint linked = 0;
        glGetProgramiv(prog, GL_LINK_STATUS, &linked);
        if (!linked) {
            char log[4096];
            glGetProgramInfoLog(prog, sizeof(log), nullptr, log);
            std::fprintf(stderr, "[gl] link failed:\n%s\n", log);
            return false;
        }

        glGenVertexArrays(1, &vao);

        // Sampler-unit assignments, mirroring GlesRenderer (gles_renderer.cpp
        // ~2090). Without these every sampler defaults to unit 0, and a
        // sampler2D + sampler3D sharing a unit is GL_INVALID_OPERATION at
        // draw time even when the LUT branch never executes.
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

        srcTex = renderer.uploadSourceFp16(srcFp16.data(), W, H);
        if (!srcTex) {
            std::fprintf(stderr, "[gl] uploadSourceFp16 failed\n");
            return false;
        }

        // FP16 color attachment so the comparison isn't quantised to 8 bits.
        // Requires EXT_color_buffer_float (ANGLE ES3 exposes it).
        glGenTextures(1, &fboTex);
        glBindTexture(GL_TEXTURE_2D, fboTex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, W, H);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, fboTex, 0);
        GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            std::fprintf(stderr, "[gl] FP16 FBO incomplete (0x%x) — is "
                                 "EXT_color_buffer_float missing?\n", status);
            return false;
        }
        return true;
    }

    GLuint toneCurveTex = 0;   // lazily created 256x1 RGB8 curve

    bool render(const float* blob, std::vector<float>& outRgba) {
        raw_v3::ShaderParams sp =
            raw_v3::ShaderParams::fromFloatArray(blob, kParamCount);
        raw_v3::GradingInputs in;   // defaults: no LUT/masks/curves/tone-curve
        in.texW = W;
        in.texH = H;
        // Tone-curve cases: upload the 256x1 RGB curve on the unit
        // pushGradingUniforms points uToneCurveTex at (9), and flag it ready so
        // uToneCurveEnabled goes high. Filtering/format mirror the production
        // renderer so the comparison measures the KERNELS, not the sampler.
        if (g_toneCurveCase) {
            if (!toneCurveTex) {
                glGenTextures(1, &toneCurveTex);
                glActiveTexture(GL_TEXTURE9);
                glBindTexture(GL_TEXTURE_2D, toneCurveTex);
                // GL_LINEAR, matching gles_renderer.cpp — the CPU kernel
                // lerps between adjacent LUT entries, so NEAREST here made the
                // harness disagree with itself by a texel (~0.006) rather than
                // measuring the kernels.
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, 256, 1, 0,
                             GL_RGB, GL_UNSIGNED_BYTE, toneCurveLut());
            }
            glActiveTexture(GL_TEXTURE9);
            glBindTexture(GL_TEXTURE_2D, toneCurveTex);
            in.toneCurveReady = true;
        }

        auto check = [](const char* stage) -> bool {
            GLenum err = glGetError();
            if (err != GL_NO_ERROR) {
                std::fprintf(stderr, "[gl] glGetError=0x%x at %s\n", err, stage);
                return false;
            }
            return true;
        };
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, W, H);
        glUseProgram(prog);
        glBindVertexArray(vao);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, srcTex);
        GLint texLoc = glGetUniformLocation(prog, "uTex");
        if (texLoc >= 0) glUniform1i(texLoc, 0);
        if (!check("setup")) return false;
        raw_v3::pushGradingUniforms(prog, sp, in);
        if (!check("pushGradingUniforms")) return false;
        glClearColor(0.f, 0.f, 0.f, 1.f);
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glFinish();
        if (!check("draw")) return false;

        outRgba.assign((size_t) W * H * 4, 0.f);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_FLOAT, outRgba.data());
        if (!check("readback")) return false;
        return true;
    }
};

// ── CPU reference ────────────────────────────────────────────────────────────
void renderCpu(const std::vector<float>& srcRgba, const float* blob,
               std::vector<float>& outRgba) {
    raw_v3::ApplyMacroParams p =
        raw_v3::ApplyMacroParams::fromFloatArray(blob, kParamCount);
    // Stage C sets this from StageCOptions, not from the params blob — so the
    // harness has to wire it the same way or the CPU side skips the curve while
    // the GPU applies it (and every curve case would "fail" for the wrong reason).
    if (g_toneCurveCase) p.toneCurveLut = toneCurveLut();
    outRgba.assign((size_t) W * H * 4, 1.f);
    for (int y = 0; y < H; ++y) {
        float v = (y + 0.5f) / H;
        for (int x = 0; x < W; ++x) {
            float u = (x + 0.5f) / W;
            const float* s = &srcRgba[((size_t) y * W + x) * 4];
            float io[3] = {s[0], s[1], s[2]};
            raw_v3::applyMacroPixel(io, u, v, p, nullptr);
            float* d = &outRgba[((size_t) y * W + x) * 4];
            d[0] = io[0]; d[1] = io[1]; d[2] = io[2]; d[3] = 1.f;
        }
    }
}

// ── Comparison + artifacts ───────────────────────────────────────────────────
struct Stats {
    float maxAbs = 0.f;
    double meanAbs = 0.0;
    double psnr = 0.0;
    int maxX = 0, maxY = 0;
};

Stats compare(const std::vector<float>& a, const std::vector<float>& b) {
    Stats st;
    double sumSq = 0.0, sumAbs = 0.0;
    size_t n = 0;
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            for (int c = 0; c < 3; ++c) {   // ignore alpha
                size_t i = ((size_t) y * W + x) * 4 + c;
                float d = std::fabs(a[i] - b[i]);
                sumAbs += d;
                sumSq += (double) d * d;
                ++n;
                if (d > st.maxAbs) { st.maxAbs = d; st.maxX = x; st.maxY = y; }
            }
        }
    }
    st.meanAbs = sumAbs / (double) n;
    double mse = sumSq / (double) n;
    st.psnr = mse <= 0.0 ? 99.0 : 10.0 * std::log10(1.0 / mse);
    return st;
}

void writePng(const std::string& path, const std::vector<uint8_t>& rgba) {
    std::string err;
    if (!raz::encodeWic(path, rgba.data(), W, H, W * 4,
                        raz::WicFormat::Png, 100, raz::EncodeMeta{}, &err)) {
        std::fprintf(stderr, "[png] %s: %s\n", path.c_str(), err.c_str());
    } else {
        std::printf("[png] wrote %s\n", path.c_str());
    }
}

void dumpFloatPng(const std::string& path, const std::vector<float>& img,
                  float amp) {
    std::vector<uint8_t> rgba((size_t) W * H * 4);
    for (size_t i = 0; i < rgba.size(); ++i) {
        if (i % 4 == 3) { rgba[i] = 255; continue; }
        float v = img[i] * amp;
        v = v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
        rgba[i] = (uint8_t) (v * 255.f + 0.5f);
    }
    writePng(path, rgba);
}

void dumpDiffPng(const std::string& path, const std::vector<float>& a,
                 const std::vector<float>& b, float amp) {
    std::vector<float> diff(a.size());
    for (size_t i = 0; i < a.size(); ++i) diff[i] = std::fabs(a[i] - b[i]);
    dumpFloatPng(path, diff, amp);
}

}  // namespace

int main(int argc, char** argv) {
    const float kMaxAbsThreshold = 0.005f;
    const double kPsnrThreshold = 45.0;
    bool dumpAll = false;
    for (int i = 1; i < argc; ++i)
        if (std::strcmp(argv[i], "--dump-all") == 0) dumpAll = true;

    if (!razparity_load_angle()) return 2;

    std::vector<float> src;
    buildSweep(src);
    std::vector<uint16_t> srcFp16;
    toFp16(src, srcFp16);
    // The CPU reference must see exactly what the GPU samples: round the
    // float source through FP16 first, or the comparison starts half an
    // FP16 ULP apart before any grading runs.
    std::vector<float> srcQuantised(src.size());
    for (size_t i = 0; i < src.size(); ++i) {
        __fp16 h;
        std::memcpy(&h, &srcFp16[i], sizeof(uint16_t));
        srcQuantised[i] = (float) h;
    }

    GlHarness gl;
    if (!gl.init(srcFp16)) return 2;

    int failures = 0;
    float blob[kParamCount];
    std::vector<float> gpuOut, cpuOut;

    for (const TestCase& tc : kTests) {
        setNeutral(blob);
        tc.mutate(blob);
        // Both engines read this, not the blob — see TestCase::toneCurve.
        g_toneCurveCase = tc.toneCurve;

        if (!gl.render(blob, gpuOut)) {
            std::printf("[FAIL] %-32s GL render error\n", tc.name);
            ++failures;
            continue;
        }
        renderCpu(srcQuantised, blob, cpuOut);

        // Clamp both to [0,1] before comparing: every real consumer clamps
        // (the on-device preview surface is RGBA8, the export encodes to
        // 8/16-bit). The FP16 FBO here is the only unclamped surface in
        // either pipeline, and out-of-range overshoot (e.g. saturation
        // pushing a channel to 1.35) is invisible everywhere real.
        for (float& f : gpuOut) f = f < 0.f ? 0.f : (f > 1.f ? 1.f : f);
        for (float& f : cpuOut) f = f < 0.f ? 0.f : (f > 1.f ? 1.f : f);
        Stats st = compare(gpuOut, cpuOut);
        bool pass = st.maxAbs < kMaxAbsThreshold && st.psnr > kPsnrThreshold;
        std::printf("[%s] %-32s maxAbs=%.6f @(%d,%d)  meanAbs=%.7f  PSNR=%.2f dB\n",
                    pass ? "PASS" : "FAIL", tc.name,
                    st.maxAbs, st.maxX, st.maxY, st.meanAbs, st.psnr);
        if (!pass) {
            size_t i = ((size_t) st.maxY * W + st.maxX) * 4;
            std::printf("       src=(%.4f %.4f %.4f)  gpu=(%.4f %.4f %.4f)  cpu=(%.4f %.4f %.4f)\n",
                        srcQuantised[i], srcQuantised[i + 1], srcQuantised[i + 2],
                        gpuOut[i], gpuOut[i + 1], gpuOut[i + 2],
                        cpuOut[i], cpuOut[i + 1], cpuOut[i + 2]);
        }
        if (!pass || dumpAll) {
            std::string base = std::string("parity_") + tc.name;
            dumpDiffPng(base + "_diff_x50.png", gpuOut, cpuOut, 50.f);
            dumpFloatPng(base + "_gpu.png", gpuOut, 1.f);
            dumpFloatPng(base + "_cpu.png", cpuOut, 1.f);
        }
        if (!pass) ++failures;
    }

    std::printf("\n%d/%d tests passed\n",
                (int) (sizeof(kTests) / sizeof(kTests[0])) - failures,
                (int) (sizeof(kTests) / sizeof(kTests[0])));
    return failures == 0 ? 0 : 1;
}
