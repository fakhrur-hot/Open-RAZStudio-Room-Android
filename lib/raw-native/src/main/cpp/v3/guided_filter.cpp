#include "guided_filter.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>

namespace v3 {

namespace {

// Separable box filter using summed-area-table-free running window.
// O(W*H), single-channel float, in-place safe (uses one scratch row).
void boxFilter(const float* src, float* dst, int w, int h, int r) {
    if (r <= 0) {
        std::memcpy(dst, src, sizeof(float) * size_t(w) * size_t(h));
        return;
    }
    std::unique_ptr<float[]> tmp(new float[size_t(w) * size_t(h)]);

    // Horizontal pass.
    for (int y = 0; y < h; ++y) {
        const float* srow = src + size_t(y) * w;
        float*       drow = tmp.get() + size_t(y) * w;
        float sum = 0.f;
        for (int x = 0; x <= r && x < w; ++x) sum += srow[x];
        int count = std::min(r + 1, w);
        for (int x = 0; x < w; ++x) {
            drow[x] = sum / float(count);
            int xAdd = x + r + 1;
            int xSub = x - r;
            if (xAdd < w) { sum += srow[xAdd]; ++count; }
            if (xSub >= 0) { sum -= srow[xSub]; --count; }
        }
    }

    // Vertical pass.
    for (int x = 0; x < w; ++x) {
        float sum = 0.f;
        for (int y = 0; y <= r && y < h; ++y) sum += tmp[size_t(y) * w + x];
        int count = std::min(r + 1, h);
        for (int y = 0; y < h; ++y) {
            dst[size_t(y) * w + x] = sum / float(count);
            int yAdd = y + r + 1;
            int ySub = y - r;
            if (yAdd < h) { sum += tmp[size_t(yAdd) * w + x]; ++count; }
            if (ySub >= 0) { sum -= tmp[size_t(ySub) * w + x]; --count; }
        }
    }
}

void mulPlane(const float* a, const float* b, float* dst, size_t n) {
    for (size_t i = 0; i < n; ++i) dst[i] = a[i] * b[i];
}

// Bilinear upsample plane[srcW x srcH] to plane[dstW x dstH].
void bilinearUpsample(const float* src, int srcW, int srcH,
                      float* dst, int dstW, int dstH) {
    const float sx = float(srcW - 1) / float(std::max(dstW - 1, 1));
    const float sy = float(srcH - 1) / float(std::max(dstH - 1, 1));
    for (int y = 0; y < dstH; ++y) {
        const float fy = float(y) * sy;
        const int   y0 = int(fy);
        const int   y1 = std::min(y0 + 1, srcH - 1);
        const float ty = fy - float(y0);
        const float* r0 = src + size_t(y0) * srcW;
        const float* r1 = src + size_t(y1) * srcW;
        float* drow = dst + size_t(y) * dstW;
        for (int x = 0; x < dstW; ++x) {
            const float fx = float(x) * sx;
            const int   x0 = int(fx);
            const int   x1 = std::min(x0 + 1, srcW - 1);
            const float tx = fx - float(x0);
            const float p00 = r0[x0], p01 = r0[x1];
            const float p10 = r1[x0], p11 = r1[x1];
            const float a = p00 * (1.f - tx) + p01 * tx;
            const float b = p10 * (1.f - tx) + p11 * tx;
            drow[x] = a * (1.f - ty) + b * ty;
        }
    }
}

// Bilinear downsample by integer factor.
void bilinearDownsample(const float* src, int srcW, int srcH,
                        float* dst, int dstW, int dstH) {
    bilinearUpsample(src, srcW, srcH, dst, dstW, dstH);
}

} // namespace

void guidedFilter(
    const float* I,
    const float* p,
    float*       q,
    int          w,
    int          h,
    int          r,
    float        eps)
{
    const size_t N = size_t(w) * size_t(h);

    std::unique_ptr<float[]> meanI (new float[N]);
    std::unique_ptr<float[]> meanP (new float[N]);
    std::unique_ptr<float[]> corrI (new float[N]);
    std::unique_ptr<float[]> corrIp(new float[N]);
    std::unique_ptr<float[]> tmp   (new float[N]);

    boxFilter(I, meanI.get(),  w, h, r);
    boxFilter(p, meanP.get(),  w, h, r);

    mulPlane(I, I, tmp.get(), N);
    boxFilter(tmp.get(), corrI.get(),  w, h, r);

    mulPlane(I, p, tmp.get(), N);
    boxFilter(tmp.get(), corrIp.get(), w, h, r);

    // Reuse buffers: a in corrIp (overwritten), b in corrI.
    for (size_t i = 0; i < N; ++i) {
        const float varI  = corrI[i]  - meanI[i] * meanI[i];
        const float covIp = corrIp[i] - meanI[i] * meanP[i];
        const float a = covIp / (varI + eps);
        const float b = meanP[i] - a * meanI[i];
        corrIp[i] = a;
        corrI[i]  = b;
    }

    boxFilter(corrIp.get(), meanP.get(), w, h, r); // meanA
    boxFilter(corrI.get(),  tmp.get(),   w, h, r); // meanB

    for (size_t i = 0; i < N; ++i) {
        float v = meanP[i] * I[i] + tmp[i];
        if (v < 0.f) v = 0.f;
        else if (v > 1.f) v = 1.f;
        q[i] = v;
    }
}

void guidedFilterFast(
    const float* I,
    const float* p,
    float*       q,
    int          w,
    int          h,
    int          r,
    float        eps,
    int          scale)
{
    if (scale <= 1) {
        guidedFilter(I, p, q, w, h, r, eps);
        return;
    }
    const int sw = std::max(1, w / scale);
    const int sh = std::max(1, h / scale);
    const int sr = std::max(1, r / scale);

    std::unique_ptr<float[]> Is(new float[size_t(sw) * sh]);
    std::unique_ptr<float[]> ps(new float[size_t(sw) * sh]);
    bilinearDownsample(I, w, h, Is.get(), sw, sh);
    bilinearDownsample(p, w, h, ps.get(), sw, sh);

    const size_t Ns = size_t(sw) * size_t(sh);
    std::unique_ptr<float[]> meanI (new float[Ns]);
    std::unique_ptr<float[]> meanP (new float[Ns]);
    std::unique_ptr<float[]> corrI (new float[Ns]);
    std::unique_ptr<float[]> corrIp(new float[Ns]);
    std::unique_ptr<float[]> tmp   (new float[Ns]);

    boxFilter(Is.get(), meanI.get(),  sw, sh, sr);
    boxFilter(ps.get(), meanP.get(),  sw, sh, sr);

    mulPlane(Is.get(), Is.get(), tmp.get(), Ns);
    boxFilter(tmp.get(), corrI.get(),  sw, sh, sr);

    mulPlane(Is.get(), ps.get(), tmp.get(), Ns);
    boxFilter(tmp.get(), corrIp.get(), sw, sh, sr);

    // a -> corrIp, b -> corrI.
    for (size_t i = 0; i < Ns; ++i) {
        const float varI  = corrI[i]  - meanI[i] * meanI[i];
        const float covIp = corrIp[i] - meanI[i] * meanP[i];
        const float a = covIp / (varI + eps);
        const float b = meanP[i] - a * meanI[i];
        corrIp[i] = a;
        corrI[i]  = b;
    }

    boxFilter(corrIp.get(), meanP.get(), sw, sh, sr); // meanA at low res
    boxFilter(corrI.get(),  tmp.get(),   sw, sh, sr); // meanB at low res

    // Upsample a,b to full res and reconstruct q against full-res guide.
    std::unique_ptr<float[]> aFull(new float[size_t(w) * h]);
    std::unique_ptr<float[]> bFull(new float[size_t(w) * h]);
    bilinearUpsample(meanP.get(), sw, sh, aFull.get(), w, h);
    bilinearUpsample(tmp.get(),   sw, sh, bFull.get(), w, h);

    const size_t N = size_t(w) * size_t(h);
    for (size_t i = 0; i < N; ++i) {
        float v = aFull[i] * I[i] + bFull[i];
        if (v < 0.f) v = 0.f;
        else if (v > 1.f) v = 1.f;
        q[i] = v;
    }
}

} // namespace v3
