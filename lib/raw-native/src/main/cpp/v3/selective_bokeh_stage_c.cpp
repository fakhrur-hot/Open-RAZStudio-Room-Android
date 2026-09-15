#include "selective_bokeh_stage_c.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace raw_v3 {
namespace {

inline float cl01(float x) { return x < 0.f ? 0.f : (x > 1.f ? 1.f : x); }
inline float smoothstep(float e0, float e1, float x) {
    float t = cl01((x - e0) / (e1 - e0));
    return t * t * (3.f - 2.f * t);
}
inline float sampleMask(const ApplyMacroSubjectMask* m, float u, float v) {
    if (!m || !m->data || m->w <= 0 || m->h <= 0) return 0.f;
    const float mu = m->rectU0 + (m->rectU1 - m->rectU0) * u;
    const float mv = m->rectV0 + (m->rectV1 - m->rectV0) * v;
    int mx = int(mu * float(m->w - 1) + 0.5f);
    int my = int(mv * float(m->h - 1) + 0.5f);
    if (mx < 0) mx = 0; else if (mx > m->w - 1) mx = m->w - 1;
    if (my < 0) my = 0; else if (my > m->h - 1) my = m->h - 1;
    return cl01(m->data[my * m->w + mx]);
}
inline float sampleDepth(const float* d, int dw, int dh, float u, float v) {
    if (!d || dw <= 0 || dh <= 0) return 0.f;
    int x = int(u * float(dw - 1) + 0.5f);
    int y = int(v * float(dh - 1) + 0.5f);
    if (x < 0) x = 0; else if (x > dw - 1) x = dw - 1;
    if (y < 0) y = 0; else if (y > dh - 1) y = dh - 1;
    return cl01(d[y * dw + x]);
}
// subjectGate(power): (1-mask)^power — mask=1 subject → gate 0
inline float subjectGate(float mask, float power) {
    float bg = 1.f - mask;
    if (bg < 0.f) bg = 0.f;
    if (power == 2.f) return bg * bg;
    return std::pow(bg, power);
}

template <typename T>
void discPass(T* pix, int w, int h, int nCh, float scale, const SelectiveBokehInputs& in) {
    if (!pix || w < 2 || h < 2 || in.bokehBlur <= 0.f) return;
    if (!in.subject || !in.subject->data) return; // selective needs subject
    const bool depthOn = in.depthMap && in.depthW > 0 && in.depthH > 0;
    const float blurAmt = cl01(in.bokehBlur);
    const float spread  = cl01(in.bokehSpread);
    float balls = in.bokehBalls;
    // auto balls removed

    const size_t n = size_t(w) * size_t(h) * size_t(nCh);
    std::vector<float> src(n);
    for (size_t i = 0; i < n; ++i) src[i] = float(pix[i]) * scale;

    auto sampleSrc = [&](float u, float v, float& r, float& g, float& b) {
        int x = int(u * float(w - 1) + 0.5f);
        int y = int(v * float(h - 1) + 0.5f);
        if (x < 0) x = 0; else if (x > w - 1) x = w - 1;
        if (y < 0) y = 0; else if (y > h - 1) y = h - 1;
        const size_t i = (size_t(y) * size_t(w) + size_t(x)) * size_t(nCh);
        r = src[i]; g = src[i + 1]; b = src[i + 2];
    };

    constexpr int N = 16;
    constexpr float GOLDEN = 2.399963229728653f;

    for (int y = 0; y < h; ++y) {
        const float v = (float(y) + 0.5f) / float(h);
        for (int x = 0; x < w; ++x) {
            const float u = (float(x) + 0.5f) / float(w);
            float bgGate = subjectGate(sampleMask(in.subject, u, v), 2.f);
            if (in.atten && in.atten->data && bgGate > 0.f) {
                const float att = sampleMask(in.atten, u, v);
                bgGate *= (1.f - 0.75f * att);
            }
            float nearGate = 0.f, farGate = 0.f;
            if (depthOn && bgGate > 0.f) {
                const float depth = sampleDepth(in.depthMap, in.depthW, in.depthH, u, v);
                const float cocSigned = depth - in.focusDepth; // Phase 3
                const float coc = std::fabs(cocSigned);       // LOCKED abs
                bgGate *= smoothstep(0.02f, 0.55f, coc);
                if (cocSigned >= 0.f) farGate = bgGate;
                else                 nearGate = bgGate;
            } else {
                farGate = bgGate;
            }
            size_t o = (size_t(y) * size_t(w) + size_t(x)) * size_t(nCh);
            float cR = src[o], cG = src[o + 1], cB = src[o + 2];
            if (bgGate <= 0.f) {
                // leave sharp
            } else if (depthOn) {
                // Phase 3: separate near/far Vogel radii (Phase 2 disc kept).
                const float rFar  = (0.022f + 0.100f * spread) * farGate  * blurAmt;
                const float rNear = (0.018f + 0.080f * spread) * nearGate * blurAmt;
                const float rUV = (rFar > rNear) ? rFar : rNear;
                float accR = cR, accG = cG, accB = cB;
                if (rUV > 1e-5f) {
                    for (int i = 1; i < N; ++i) {
                        const float fi = float(i);
                        const float rr = rUV * std::sqrt(fi / float(N - 1));
                        const float ang = fi * GOLDEN;
                        float sR, sG, sB;
                        sampleSrc(cl01(u + std::cos(ang) * rr), cl01(v + std::sin(ang) * rr), sR, sG, sB);
                        accR += sR; accG += sG; accB += sB;
                    }
                    accR /= float(N); accG /= float(N); accB /= float(N);
                }
                cR = accR; cG = accG; cB = accB;
            } else {
                // Depth-off: no uBlurTex on Stage C — approximate with small disc
                const float rUV = (0.006f + 0.040f * spread) * bgGate * blurAmt;
                float accR = cR, accG = cG, accB = cB;
                if (rUV > 1e-5f) {
                    for (int i = 1; i < N; ++i) {
                        const float fi = float(i);
                        const float rr = rUV * std::sqrt(fi / float(N - 1));
                        const float ang = fi * GOLDEN;
                        float sR, sG, sB;
                        sampleSrc(cl01(u + std::cos(ang) * rr), cl01(v + std::sin(ang) * rr), sR, sG, sB);
                        accR += sR; accG += sG; accB += sB;
                    }
                    accR /= float(N); accG /= float(N); accB /= float(N);
                }
                const float m = cl01(blurAmt * bgGate);
                cR = cR + (accR - cR) * m;
                cG = cG + (accG - cG) * m;
                cB = cB + (accB - cB) * m;
            }
            if (balls > 0.f && bgGate > 0.f) {
                const float bl = 0.2627f * cR + 0.6780f * cG + 0.0593f * cB;
                const float thr = 0.78f + (0.55f - 0.78f) * spread; // mix(0.78,0.55,spread)
                const float bloom = smoothstep(thr, 1.f, bl);
                const float liftR = cR * bloom * balls * bgGate;
                const float liftG = cG * bloom * balls * bgGate;
                const float liftB = cB * bloom * balls * bgGate;
                cR = 1.f - (1.f - cR) * (1.f - liftR);
                cG = 1.f - (1.f - cG) * (1.f - liftG);
                cB = 1.f - (1.f - cB) * (1.f - liftB);
            }
            const float inv = 1.f / scale;
            auto toT = [&](float x) -> T {
                float q = x * inv;
                if (q < 0.f) q = 0.f;
                if (nCh == 4 || scale < 1.f) {
                    // rgba8 path scale=1/255
                }
                if (scale < 1.f) {
                    if (q > 255.f) q = 255.f;
                    return T(int(std::round(q)));
                } else {
                    if (q > 65535.f) q = 65535.f;
                    return T(int(std::round(q)));
                }
            };
            if (scale < 1.f) {
                pix[o] = T(int(std::round(cl01(cR) * 255.f)));
                pix[o+1] = T(int(std::round(cl01(cG) * 255.f)));
                pix[o+2] = T(int(std::round(cl01(cB) * 255.f)));
            } else {
                pix[o] = T(int(std::round(cl01(cR) * 65535.f)));
                pix[o+1] = T(int(std::round(cl01(cG) * 65535.f)));
                pix[o+2] = T(int(std::round(cl01(cB) * 65535.f)));
            }
            (void)toT;
        }
    }
}

} // namespace

void applySelectiveBokehDiscRGBA8(uint8_t* rgba, int w, int h, int strideBytes,
                                  const SelectiveBokehInputs& in) {
    if (!rgba || strideBytes < w * 4) return;
    // Pack tightly if stride == w*4; else copy rows
    if (strideBytes == w * 4) {
        discPass(rgba, w, h, 4, 1.f / 255.f, in);
        return;
    }
    std::vector<uint8_t> tight(size_t(w) * h * 4);
    for (int y = 0; y < h; ++y)
        std::memcpy(tight.data() + size_t(y) * w * 4, rgba + size_t(y) * strideBytes, size_t(w) * 4);
    discPass(tight.data(), w, h, 4, 1.f / 255.f, in);
    for (int y = 0; y < h; ++y)
        std::memcpy(rgba + size_t(y) * strideBytes, tight.data() + size_t(y) * w * 4, size_t(w) * 4);
}

void applySelectiveBokehDiscRGB16(uint16_t* rgb, int w, int h,
                                  const SelectiveBokehInputs& in) {
    discPass(rgb, w, h, 3, 1.f / 65535.f, in);
}

} // namespace raw_v3
