/*
 * StudioRoom — RAW Pipeline v3 — 3D LUT loader.
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

#include "lut3d.h"

#include <android/log.h>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>

#define LOG_TAG "RawV3.Lut3D"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw_v3 {

namespace {

std::string strip(const std::string& s) {
    auto a = s.find_first_not_of(" \t\r\n");
    auto b = s.find_last_not_of(" \t\r\n");
    if (a == std::string::npos) return {};
    return s.substr(a, b - a + 1);
}

bool startsWithCi(const std::string& s, const char* prefix) {
    size_t n = std::strlen(prefix);
    if (s.size() < n) return false;
    for (size_t i = 0; i < n; ++i) {
        if (std::tolower(uint8_t(s[i])) != std::tolower(uint8_t(prefix[i]))) return false;
    }
    return true;
}

// ── smol-cube (.smcube) binary reader ───────────────────────────────────────
// Binary equivalent of a .cube (aras-p/smol-cube, MIT/Unlicense). ~4.5× smaller
// than ASCII at fp16 and far faster to parse (no text scanning). Layout:
//   "SML1" magic, then chunks { FOURCC[4], u64 size (LE), payload[size] }.
//   "ALut" chunk payload = 28-byte header (7× u32 LE:
//     channels, dimension, data_type, filter, size_x, size_y, size_z)
//     then row-major data (R fastest — same order as .cube). data_type: 0=f32,
//     1=f16. filter: 0=none, 1=byte-delta. We read 3-channel 3D, filter=none,
//     f16 or f32 (the subset our converter emits); anything else is rejected.
inline uint32_t rdU32LE(const uint8_t* p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}
inline uint64_t rdU64LE(const uint8_t* p) {
    uint64_t v = 0; for (int i = 0; i < 8; ++i) v |= uint64_t(p[i]) << (8 * i); return v;
}
inline float half2float(uint16_t h) {
    uint32_t sign = uint32_t(h & 0x8000u) << 16;
    uint32_t exp  = (h >> 10) & 0x1Fu;
    uint32_t mant = h & 0x3FFu;
    uint32_t f;
    if (exp == 0) {
        if (mant == 0) { f = sign; }                       // ±0
        else {                                             // subnormal → normalise
            int e = -1;
            do { mant <<= 1; ++e; } while ((mant & 0x400u) == 0);
            mant &= 0x3FFu;
            f = sign | (uint32_t(127 - 15 - e) << 23) | (mant << 13);
        }
    } else if (exp == 0x1F) {                               // inf / nan
        f = sign | 0x7F800000u | (mant << 13);
    } else {                                                // normal
        f = sign | ((exp - 15 + 127) << 23) | (mant << 13);
    }
    float out; std::memcpy(&out, &f, 4); return out;
}

CubeLut parseSmcube(const std::string& path) {
    CubeLut lut;
    std::ifstream in(path, std::ios::binary);
    if (!in) { LOGE("parseSmcube: cannot open %s", path.c_str()); return lut; }
    std::vector<uint8_t> buf((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    if (buf.size() < 4 || std::memcmp(buf.data(), "SML1", 4) != 0) {
        LOGE("parseSmcube: bad magic in %s", path.c_str()); return lut;
    }
    size_t off = 4;
    while (off + 12 <= buf.size()) {
        char fourcc[4]; std::memcpy(fourcc, &buf[off], 4);
        uint64_t sz = rdU64LE(&buf[off + 4]);
        size_t data = off + 12;
        if (sz > buf.size() || data + sz > buf.size()) { LOGE("parseSmcube: chunk overflow"); break; }
        if (std::memcmp(fourcc, "ALut", 4) == 0) {
            if (sz < 28) { LOGE("parseSmcube: ALut header short"); return lut; }
            const uint8_t* h = &buf[data];
            const uint32_t channels  = rdU32LE(h + 0);
            const uint32_t dimension = rdU32LE(h + 4);
            const uint32_t dtype     = rdU32LE(h + 8);
            const uint32_t filter    = rdU32LE(h + 12);
            const uint32_t sx = rdU32LE(h + 16), sy = rdU32LE(h + 20), sz3 = rdU32LE(h + 24);
            if (channels != 3 || dimension != 3) {
                LOGE("parseSmcube: only 3-channel 3D supported (ch=%u dim=%u)", channels, dimension); return lut;
            }
            if (filter != 0) { LOGE("parseSmcube: byte-delta filter unsupported"); return lut; }
            if (sx != sy || sy != sz3 || sx < 2 || sx > 256) { LOGE("parseSmcube: bad dims %u", sx); return lut; }
            const uint8_t* pay = h + 28;
            const uint64_t paySz = sz - 28;
            const size_t count = size_t(sx) * sy * sz3 * 3;
            lut.rgb.resize(count);
            if (dtype == 1) {            // Float16
                if (paySz < uint64_t(count) * 2) { LOGE("parseSmcube: f16 payload short"); return {}; }
                for (size_t i = 0; i < count; ++i)
                    lut.rgb[i] = half2float(uint16_t(pay[i * 2]) | (uint16_t(pay[i * 2 + 1]) << 8));
            } else if (dtype == 0) {     // Float32 (arm64 is little-endian → direct copy)
                if (paySz < uint64_t(count) * 4) { LOGE("parseSmcube: f32 payload short"); return {}; }
                std::memcpy(lut.rgb.data(), pay, count * 4);
            } else { LOGE("parseSmcube: unknown data_type %u", dtype); return {}; }
            lut.size = int(sx);   // domain defaults to 0..1 (smol-cube stores none)
            LOGI("parseSmcube: loaded %d^3 (dtype=%u) from %s", lut.size, dtype, path.c_str());
            return lut;
        }
        off = data + sz;
    }
    LOGE("parseSmcube: no ALut chunk in %s", path.c_str());
    return lut;
}

// Expand a 1D .cube LUT (three separable per-channel curves, N entries each) into
// a small 3D cube so the rest of the pipeline (GL sampler3D + CPU export) treats
// it identically to a 3D LUT. 1D LUTs are separable, so the 3D result is exact at
// the grid points; a modest grid (≤65) is plenty for the smooth tone curves 1D
// LUTs encode. `oneD` holds N RGB triplets in input order (index i = level
// i/(N-1)); [domainMin]/[domainMax] carry through unchanged (the shader applies
// them to the lookup input just as for a 3D LUT).
CubeLut expand1dTo3d(const std::vector<float>& oneD, int n,
                     const float domainMin[3], const float domainMax[3],
                     const std::string& title) {
    CubeLut out;
    out.title = title;
    for (int c = 0; c < 3; ++c) { out.domainMin[c] = domainMin[c]; out.domainMax[c] = domainMax[c]; }
    const int S = std::min(std::max(n, 2), 65);   // 3D grid side (cap memory: 65³ = 1.65 MB f16)
    out.size = S;
    out.rgb.resize(size_t(S) * S * S * 3);

    // Linear-interpolate channel c of the 1D table at normalized input t∈[0,1].
    auto curve = [&](int c, float t) -> float {
        float x = t * float(n - 1);
        int i0 = int(std::floor(x));
        int i1 = std::min(i0 + 1, n - 1);
        i0 = std::min(std::max(i0, 0), n - 1);
        float f = x - float(i0);
        float v0 = oneD[size_t(i0) * 3 + c];
        float v1 = oneD[size_t(i1) * 3 + c];
        return v0 * (1.0f - f) + v1 * f;
    };

    // .cube ordering: red index fastest, then green, then blue.
    const float inv = (S > 1) ? 1.0f / float(S - 1) : 0.0f;
    for (int b = 0; b < S; ++b)
        for (int g = 0; g < S; ++g)
            for (int r = 0; r < S; ++r) {
                size_t idx = ((size_t(b) * S + g) * S + r) * 3;
                out.rgb[idx + 0] = curve(0, float(r) * inv);
                out.rgb[idx + 1] = curve(1, float(g) * inv);
                out.rgb[idx + 2] = curve(2, float(b) * inv);
            }
    return out;
}

}  // anonymous namespace

CubeLut parseCubeFile(const std::string& path) {
    // Binary smol-cube (.smcube) dispatch — sniff the "SML1" magic so callers
    // need not care about the format (asset materialisation keeps the extension,
    // native picks the parser by content). Falls through to ASCII .cube otherwise.
    {
        std::ifstream probe(path, std::ios::binary);
        char m[4] = {0};
        if (probe && probe.read(m, 4) && std::memcmp(m, "SML1", 4) == 0) {
            return parseSmcube(path);
        }
    }
    CubeLut lut;
    int oneDSize = 0;   // >0 once LUT_1D_SIZE is seen → 1D LUT (expanded to 3D at the end)
    std::ifstream in(path);
    if (!in) {
        LOGE("parseCubeFile: cannot open %s", path.c_str());
        return lut;
    }

    std::string line;
    while (std::getline(in, line)) {
        auto s = strip(line);
        if (s.empty() || s[0] == '#') continue;

        if (startsWithCi(s, "TITLE")) {
            auto q1 = s.find('"');
            auto q2 = s.rfind('"');
            if (q1 != std::string::npos && q2 != std::string::npos && q2 > q1) {
                lut.title = s.substr(q1 + 1, q2 - q1 - 1);
            }
            continue;
        }
        if (startsWithCi(s, "LUT_3D_SIZE")) {
            std::stringstream ss(s.substr(11));
            ss >> lut.size;
            if (lut.size < 2 || lut.size > 256) {
                LOGE("parseCubeFile: invalid LUT_3D_SIZE=%d", lut.size);
                lut.size = 0;
                return lut;
            }
            lut.rgb.reserve(size_t(lut.size) * lut.size * lut.size * 3);
            continue;
        }
        if (startsWithCi(s, "LUT_1D_SIZE")) {
            std::stringstream ss(s.substr(11));
            ss >> oneDSize;
            if (oneDSize < 2 || oneDSize > 65536) {
                LOGE("parseCubeFile: invalid LUT_1D_SIZE=%d", oneDSize);
                lut.size = 0;
                return lut;
            }
            lut.rgb.reserve(size_t(oneDSize) * 3);   // 1D table read into rgb, expanded below
            continue;
        }
        if (startsWithCi(s, "DOMAIN_MIN")) {
            std::stringstream ss(s.substr(10));
            ss >> lut.domainMin[0] >> lut.domainMin[1] >> lut.domainMin[2];
            continue;
        }
        if (startsWithCi(s, "DOMAIN_MAX")) {
            std::stringstream ss(s.substr(10));
            ss >> lut.domainMax[0] >> lut.domainMax[1] >> lut.domainMax[2];
            continue;
        }

        // Data row: three floats.
        float r, g, b;
        std::stringstream ss(s);
        if ((ss >> r >> g >> b)) {
            lut.rgb.push_back(r);
            lut.rgb.push_back(g);
            lut.rgb.push_back(b);
        }
    }

    // 1D LUT → validate the N-entry table and expand to a 3D cube.
    if (oneDSize > 0) {
        const size_t expected1d = size_t(oneDSize) * 3;
        if (lut.rgb.size() != expected1d) {
            LOGE("parseCubeFile: 1D data mismatch — got %zu floats, expected %zu (size=%d)",
                 lut.rgb.size(), expected1d, oneDSize);
            lut.size = 0; lut.rgb.clear();
            return lut;
        }
        CubeLut out = expand1dTo3d(lut.rgb, oneDSize, lut.domainMin, lut.domainMax, lut.title);
        LOGI("parseCubeFile: loaded 1D \"%s\" size=%d → expanded to %d^3 cube",
             out.title.c_str(), oneDSize, out.size);
        return out;
    }

    if (lut.size == 0) {
        LOGE("parseCubeFile: missing LUT_3D_SIZE");
        return lut;
    }
    const size_t expected = size_t(lut.size) * lut.size * lut.size * 3;
    if (lut.rgb.size() != expected) {
        LOGE("parseCubeFile: data size mismatch — got %zu floats, expected %zu (size=%d)",
             lut.rgb.size(), expected, lut.size);
        lut.size = 0;
        lut.rgb.clear();
        return lut;
    }
    LOGI("parseCubeFile: loaded \"%s\" size=%d^3 (%zu triplets) domain=[%.2f..%.2f]",
         lut.title.c_str(), lut.size, lut.rgb.size() / 3,
         lut.domainMin[0], lut.domainMax[0]);
    return lut;
}

// GL half — compiled out for the desktop batch engine, which consumes the
// parsed CubeLut on the CPU via StageCOptions::lutData and never uploads it.
#ifndef RAZ_NO_EGL
GLuint uploadCubeLutAsTexture3D(const CubeLut& lut) {
    if (lut.size == 0) return 0;

    GLuint tex = 0;
    glGenTextures(1, &tex);
    if (!tex) {
        LOGE("uploadCubeLutAsTexture3D: glGenTextures returned 0");
        return 0;
    }
    glBindTexture(GL_TEXTURE_3D, tex);

    // Trilinear interpolation, clamp at edges (no wrap — the LUT covers [0,1]).
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S,     GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T,     GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R,     GL_CLAMP_TO_EDGE);

    // GLES 3.0 supports GL_RGB16F for sized internal formats. RGB16F gives us
    // adequate precision for the LUT samples (the .cube floats are in [0,1]
    // and 11-bit mantissa is plenty) at half the memory of RGB32F.
    //   65³ × 3 × 2 B = 1.65 MB per LUT.
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage3D(GL_TEXTURE_3D,
                 0,
                 GL_RGB16F,
                 lut.size, lut.size, lut.size,
                 0,
                 GL_RGB,
                 GL_FLOAT,
                 lut.rgb.data());

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("uploadCubeLutAsTexture3D: glTexImage3D err=0x%x", err);
        glDeleteTextures(1, &tex);
        return 0;
    }
    LOGI("uploadCubeLutAsTexture3D: ok tex=%u size=%d^3", tex, lut.size);
    return tex;
}
#endif  // RAZ_NO_EGL

}  // namespace raw_v3
