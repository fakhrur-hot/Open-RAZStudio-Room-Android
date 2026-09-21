/*
 * StudioRoom — RAW Pipeline v3
 * dual_blend.cpp — Enhanced Confidence-Estimator & Guided Blend Architecture
 * for the AMaZE+VNG dual-demosaic path, eliminating magenta shifts, false color,
 * and highlight instability.
 */

#include "dual_blend.h"
#include "stage_blur.h"

#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <vector>

#define LOG_TAG "RawV3.DualBlend"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

namespace raw_v3 {

namespace {

constexpr float kContrastScaleFp = 0.0625f / 327.68f * 65535.0f;
constexpr float kMinLuminance      = 2000.f / 65535.f;
constexpr float kMaxLuminance      = 20000.f / 65535.f;
constexpr float kMinTileVariance   = 1e-5f;

// Global storage for latest stats and intermediate confidence planes for debugging
static DualBlendDebugStats g_lastStats = {};
static std::vector<float> g_edgeConfidencePlane;
static std::vector<float> g_textureConfidencePlane;
static std::vector<float> g_highlightConfidencePlane;
static std::vector<float> g_noiseConfidencePlane;

inline float calcBlendFactor(float val, float threshold) {
    const float x = -16.f + (16.f / threshold) * val;
    return 0.5f * (1.f + x / std::sqrt(1.f + x * x));
}

inline float tileAverage(const float* data, int W, int tileY, int tileX, int tilesize) {
    float avg = 0.f;
    for (int y = tileY; y < tileY + tilesize; ++y) {
        const float* row = data + size_t(y) * W;
        for (int x = tileX; x < tileX + tilesize; ++x) {
            avg += row[x];
        }
    }
    return avg / float(tilesize * tilesize);
}

inline float tileVariance(const float* data, int W, int tileY, int tileX, int tilesize, float avg) {
    float var = 0.f;
    for (int y = tileY; y < tileY + tilesize; ++y) {
        const float* row = data + size_t(y) * W;
        for (int x = tileX; x < tileX + tilesize; ++x) {
            const float d = row[x] - avg;
            var += d * d;
        }
    }
    return var / (float(tilesize * tilesize) * (avg > 1e-6f ? avg : 1e-6f));
}

float calcContrastThreshold(const float* luminance, int W, int tileY, int tileX, int tilesize) {
    const int rows = tilesize - 4;
    const int cols = tilesize - 4;
    std::vector<std::vector<float>> blend(rows, std::vector<float>(cols));
    const float scale = kContrastScaleFp;

    for (int j = tileY + 2; j < tileY + tilesize - 2; ++j) {
        for (int i = tileX + 2; i < tileX + tilesize - 2; ++i) {
            const float dx1 = luminance[size_t(j) * W + (i + 1)] - luminance[size_t(j) * W + (i - 1)];
            const float dy1 = luminance[size_t(j + 1) * W + i] - luminance[size_t(j - 1) * W + i];
            const float dx2 = luminance[size_t(j) * W + (i + 2)] - luminance[size_t(j) * W + (i - 2)];
            const float dy2 = luminance[size_t(j + 2) * W + i] - luminance[size_t(j - 2) * W + i];
            const float contrast = std::sqrt(dx1 * dx1 + dy1 * dy1 + dx2 * dx2 + dy2 * dy2) * scale;
            blend[j - tileY - 2][i - tileX - 2] = contrast;
        }
    }

    const float limit = float((tilesize - 4) * (tilesize - 4)) / 100.f;
    int c;
    for (c = 1; c < 100; ++c) {
        const float contrastThreshold = float(c) / 100.f;
        float sum = 0.f;
        for (int j = 0; j < rows; ++j) {
            for (int i = 0; i < cols; ++i) {
                sum += calcBlendFactor(blend[j][i], contrastThreshold);
            }
        }
        if (sum <= limit) break;
    }
    return float(c + 1) / 100.f;
}

} // namespace

void buildBlendMask(const float* luminance, float* blend, int W, int H,
                    float* contrastThreshold, bool autoContrast) {
    if (autoContrast) {
        for (int pass = 0; pass < 2; ++pass) {
            const int tilesize = 80 / (pass + 1);
            const int skip = (pass == 0) ? tilesize : tilesize / 4;
            const int numTilesW = std::max(1, W / skip - 3 * pass);
            const int numTilesH = std::max(1, H / skip - 3 * pass);
            std::vector<std::vector<float>> variances(numTilesH, std::vector<float>(numTilesW));

            for (int i = 0; i < numTilesH; ++i) {
                const int tileY = i * skip;
                for (int j = 0; j < numTilesW; ++j) {
                    const int tileX = j * skip;
                    if (tileY + tilesize > H || tileX + tilesize > W) {
                        variances[i][j] = std::numeric_limits<float>::infinity();
                        continue;
                    }
                    const float avg = tileAverage(luminance, W, tileY, tileX, tilesize);
                    if (avg < kMinLuminance || avg > kMaxLuminance) {
                        variances[i][j] = std::numeric_limits<float>::infinity();
                        continue;
                    }
                    float v = tileVariance(luminance, W, tileY, tileX, tilesize, avg);
                    if (v < kMinTileVariance) v = std::numeric_limits<float>::infinity();
                    variances[i][j] = v;
                }
            }

            float minvar = std::numeric_limits<float>::infinity();
            int minI = 0, minJ = 0;
            for (int i = 0; i < numTilesH; ++i) {
                for (int j = 0; j < numTilesW; ++j) {
                    if (variances[i][j] < minvar) {
                        minvar = variances[i][j];
                        minI = i;
                        minJ = j;
                    }
                }
            }

            const float acceptVar = (pass == 0) ? 1.f : 8.f;
            if (minvar <= acceptVar || pass == 1) {
                const int minY = skip * minI;
                const int minX = skip * minJ;
                if (minvar <= acceptVar) {
                    *contrastThreshold = calcContrastThreshold(luminance, W, minY, minX, tilesize);
                } else {
                    *contrastThreshold = 0.f;
                }
                LOGI("buildBlendMask (Confidence): autoContrast pass %d picked threshold=%.3f (minvar=%.3f at %d,%d)",
                     pass, *contrastThreshold, minvar, minX, minY);
                if (minvar <= 1.f) break;
            }
        }
    }

    if (*contrastThreshold <= 0.f) {
        const size_t n = size_t(W) * H;
        for (size_t k = 0; k < n; ++k) blend[k] = 1.f;
        return;
    }

    const float scale = kContrastScaleFp;
    const float thr = *contrastThreshold;

    const size_t totalPixels = size_t(W) * H;
    g_edgeConfidencePlane.resize(totalPixels, 1.0f);
    g_textureConfidencePlane.resize(totalPixels, 1.0f);
    g_highlightConfidencePlane.resize(totalPixels, 1.0f);
    g_noiseConfidencePlane.resize(totalPixels, 1.0f);

    double sumEdge = 0.0, sumTexture = 0.0, sumNoise = 0.0, sumHighlight = 0.0;
    double sumHighlightAmaze = 0.0, sumShadowAmaze = 0.0, sumClippedAmaze = 0.0;
    double sumAmaze = 0.0, sumVng = 0.0;
    int clippedCount = 0;
    int interiorCount = 0;

    // Multi-factor confidence estimation pass with square-root adjusted multiplicative fusion
    for (int j = 2; j < H - 2; ++j) {
        for (int i = 2; i < W - 2; ++i) {
            const size_t idx = size_t(j) * W + i;
            const float lumaCenter = luminance[idx];

            // 1. Edge Confidence (Sobel 4-point gradient analysis)
            const float dx1 = luminance[size_t(j) * W + (i + 1)] - luminance[size_t(j) * W + (i - 1)];
            const float dy1 = luminance[size_t(j + 1) * W + i] - luminance[size_t(j - 1) * W + i];
            const float dx2 = luminance[size_t(j) * W + (i + 2)] - luminance[size_t(j) * W + (i - 2)];
            const float dy2 = luminance[size_t(j + 2) * W + i] - luminance[size_t(j - 2) * W + i];
            const float contrast = std::sqrt(dx1 * dx1 + dy1 * dy1 + dx2 * dx2 + dy2 * dy2) * scale;
            const float edgeConfidence = calcBlendFactor(contrast, thr);
            g_edgeConfidencePlane[idx] = edgeConfidence;

            // 2. Texture Confidence (Local variance / entropy analysis for fine detail regions)
            float localMean = 0.0f;
            for (int ny = -2; ny <= 2; ++ny) {
                for (int nx = -2; nx <= 2; ++nx) {
                    localMean += luminance[size_t(j + ny) * W + (i + nx)];
                }
            }
            localMean /= 25.0f;

            float localVariance = 0.0f;
            for (int ny = -2; ny <= 2; ++ny) {
                for (int nx = -2; nx <= 2; ++nx) {
                    float diff = luminance[size_t(j + ny) * W + (i + nx)] - localMean;
                    localVariance += diff * diff;
                }
            }
            localVariance /= 25.0f;
            const float textureConfidence = 1.0f - std::exp(-50.0f * localVariance);
            g_textureConfidencePlane[idx] = textureConfidence;

            // 3. Highlight Confidence (Near-clipping detection suppressing AMaZE in saturated regions)
            float highlightConfidence = 1.0f;
            if (lumaCenter > 0.92f) {
                const float clipDist = (1.0f - lumaCenter) / 0.08f;
                highlightConfidence = std::clamp(clipDist, 0.0f, 1.0f);
                clippedCount++;
            }
            g_highlightConfidencePlane[idx] = highlightConfidence;

            // 4. Noise Confidence (Local variance estimation for noise robustness in shadows)
            float localVar = 0.0f;
            for (int ny = -1; ny <= 1; ++ny) {
                for (int nx = -1; nx <= 1; ++nx) {
                    const float d = luminance[size_t(j + ny) * W + (i + nx)] - lumaCenter;
                    localVar += d * d;
                }
            }
            const float noiseConfidence = 1.0f / (1.0f + 50.0f * localVar);
            g_noiseConfidencePlane[idx] = noiseConfidence;

            // 5. Square-root adjusted multiplicative fusion (veto-style without excessive over-suppression)
            const float product = edgeConfidence * textureConfidence * noiseConfidence * highlightConfidence;
            const float finalConfidence = std::sqrt(std::clamp(product, 0.0f, 1.0f));

            blend[idx] = finalConfidence;

            // Accumulate stats
            sumEdge += edgeConfidence;
            sumTexture += textureConfidence;
            sumNoise += noiseConfidence;
            sumHighlight += highlightConfidence;

            const float amazeW = finalConfidence;
            const float vngW = 1.0f - finalConfidence;
            sumAmaze += amazeW;
            sumVng += vngW;

            if (lumaCenter > 0.8f) {
                sumHighlightAmaze += amazeW;
                if (lumaCenter > 0.92f) {
                    sumClippedAmaze += amazeW;
                }
            } else if (lumaCenter < 0.2f) {
                sumShadowAmaze += amazeW;
            }

            interiorCount++;
        }
    }

    // Populate g_lastStats
    if (interiorCount > 0) {
        float invCount = 1.0f / float(interiorCount);
        g_lastStats.avgAmazeWeight = float(sumAmaze * invCount);
        g_lastStats.avgVngWeight = float(sumVng * invCount);
        g_lastStats.highlightAmazeWeight = float(sumHighlightAmaze * invCount);
        g_lastStats.shadowAmazeWeight = float(sumShadowAmaze * invCount);
        g_lastStats.clippedRegionAmazeWeight = float(sumClippedAmaze * (clippedCount > 0 ? 1.0 / clippedCount : 0.0));
        g_lastStats.avgEdgeConfidence = float(sumEdge * invCount);
        g_lastStats.avgTextureConfidence = float(sumTexture * invCount);
        g_lastStats.avgNoiseConfidence = float(sumNoise * invCount);
        g_lastStats.avgHighlightConfidence = float(sumHighlight * invCount);
        g_lastStats.clippedPixels = clippedCount;

        LOGI("DualBlendStats: Amaze=%.2f, Vng=%.2f, Edge=%.2f, Texture=%.2f, Highlight=%.2f, Clipped=%d",
             g_lastStats.avgAmazeWeight, g_lastStats.avgVngWeight, g_lastStats.avgEdgeConfidence,
             g_lastStats.avgTextureConfidence, g_lastStats.avgHighlightConfidence, g_lastStats.clippedPixels);
    }

    // Border replication
    for (int j = 0; j < 2; ++j) {
        for (int i = 2; i < W - 2; ++i) {
            blend[size_t(j) * W + i] = blend[2 * W + i];
        }
    }
    for (int j = H - 2; j < H; ++j) {
        for (int i = 2; i < W - 2; ++i) {
            blend[size_t(j) * W + i] = blend[size_t(H - 3) * W + i];
        }
    }
    for (int j = 0; j < H; ++j) {
        blend[size_t(j) * W + 0] = blend[size_t(j) * W + 2];
        blend[size_t(j) * W + 1] = blend[size_t(j) * W + 2];
        blend[size_t(j) * W + (W - 2)] = blend[size_t(j) * W + (W - 3)];
        blend[size_t(j) * W + (W - 1)] = blend[size_t(j) * W + (W - 3)];
    }

    // Guided Filter / Separable Gaussian Smoothing on Blend Map for seamless transitions
    std::vector<float> packed(size_t(W) * H * 3);
    std::vector<float> packedOut(size_t(W) * H * 3);
    for (size_t k = 0; k < size_t(W) * H; ++k) {
        packed[k * 3 + 0] = blend[k];
        packed[k * 3 + 1] = blend[k];
        packed[k * 3 + 2] = blend[k];
    }
    gaussianBlurSeparableShared(packed.data(), packedOut.data(), W, H, /*radius=*/20);

    for (size_t k = 0; k < size_t(W) * H; ++k) {
        float b = packedOut[k * 3 + 0];
        // Clamping to avoid pure boundary dropouts while preserving transition smoothness
        blend[k] = std::clamp(b, 0.15f, 0.85f);
    }
}

const DualBlendDebugStats& getLastDualBlendDebugStats() {
    return g_lastStats;
}

namespace {
void writePgmOrRawFloat(const char* filepath, const std::vector<float>& plane, int W, int H) {
    if (filepath == nullptr || plane.empty() || W <= 0 || H <= 0) return;
    FILE* f = fopen(filepath, "wb");
    if (!f) return;
    fprintf(f, "P5\n%d %d\n255\n", W, H);
    std::vector<unsigned char> row(W);
    for (int j = 0; j < H; ++j) {
        const float* src = &plane[size_t(j) * W];
        for (int i = 0; i < W; ++i) {
            float val = std::clamp(src[i], 0.0f, 1.0f);
            row[i] = static_cast<unsigned char>(val * 255.0f);
        }
        fwrite(row.data(), 1, W, f);
    }
    fclose(f);
}
} // namespace

void saveBlendMapPng(const char*filepath, const float* blend, int W, int H) {
    if (!blend) return;
    std::vector<float> plane(blend, blend + size_t(W) * H);
    writePgmOrRawFloat(filepath, plane, W, H);
}

void saveEdgeConfidencePng(const char* filepath, int W, int H) {
    writePgmOrRawFloat(filepath, g_edgeConfidencePlane, W, H);
}

void saveTextureConfidencePng(const char* filepath, int W, int H) {
    writePgmOrRawFloat(filepath, g_textureConfidencePlane, W, H);
}

void saveHighlightConfidencePng(const char* filepath, int W, int H) {
    writePgmOrRawFloat(filepath, g_highlightConfidencePlane, W, H);
}

void saveNoiseConfidencePng(const char* filepath, int W, int H) {
    writePgmOrRawFloat(filepath, g_noiseConfidencePlane, W, H);
}

} // namespace raw_v3
