#include "dng_gainmap.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

#define LOG_TAG "RawV3.DngGainMap"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace raw_v3 {
namespace {

// ── Byte readers ───────────────────────────────────────────────────────────
// TIFF structure follows the file's own endianness; opcode payloads are ALWAYS
// big-endian (DNG spec 1.4, "Digital Negative Opcodes").

inline uint16_t rdU16(const uint8_t* p, bool le) {
    return le ? uint16_t(p[0] | (p[1] << 8)) : uint16_t((p[0] << 8) | p[1]);
}
inline uint32_t rdU32(const uint8_t* p, bool le) {
    return le ? (uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) |
                 (uint32_t(p[3]) << 24))
              : (uint32_t(p[3]) | (uint32_t(p[2]) << 8) | (uint32_t(p[1]) << 16) |
                 (uint32_t(p[0]) << 24));
}
inline uint32_t rdU32BE(const uint8_t* p) { return rdU32(p, false); }

inline float rdF32BE(const uint8_t* p) {
    uint32_t v = rdU32BE(p);
    float f;
    std::memcpy(&f, &v, 4);
    return f;
}

inline double rdF64BE(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v = (v << 8) | p[i];
    double d;
    std::memcpy(&d, &v, 8);
    return d;
}

/** One decoded GainMap opcode. */
struct GainMap {
    uint32_t top = 0, left = 0, bottom = 0, right = 0;
    uint32_t plane = 0, planes = 1, rowPitch = 1, colPitch = 1;
    uint32_t pointsV = 0, pointsH = 0, mapPlanes = 1;
    double spacingV = 0, spacingH = 0, originV = 0, originH = 0;
    std::vector<float> gains;

    /** Bilinearly sample the map at image pixel (x, y), in ACTIVE-AREA coords. */
    float sample(int x, int y) const {
        if (pointsV == 0 || pointsH == 0 || gains.empty()) return 1.f;
        // MapSpacing/MapOrigin are NORMALISED against the opcode's own
        // rectangle (DNG 1.4): a 17-point map spans the rectangle with
        // spacing 1/16 = 0.0625, NOT 0.0625 pixels. Feeding a raw pixel row
        // in here divides 3000 by 0.0625, clamps to the last index, and hands
        // every pixel the bottom-right CORNER gain — a uniform ~3x lift with
        // the corner's colour skew smeared over the frame (the "hazy magenta"
        // reported 2026-09-07). Normalise to the rectangle first.
        const double h = double(bottom) - double(top);
        const double w = double(right) - double(left);
        const double normV = (h > 0) ? (double(y) - double(top)) / h : 0.0;
        const double normH = (w > 0) ? (double(x) - double(left)) / w : 0.0;
        const double relV = (spacingV > 0) ? ((normV - originV) / spacingV) : 0.0;
        const double relH = (spacingH > 0) ? ((normH - originH) / spacingH) : 0.0;
        double fv = std::clamp(relV, 0.0, double(pointsV - 1));
        double fh = std::clamp(relH, 0.0, double(pointsH - 1));
        const int v0 = int(fv), h0 = int(fh);
        const int v1 = std::min(v0 + 1, int(pointsV) - 1);
        const int h1 = std::min(h0 + 1, int(pointsH) - 1);
        const double tv = fv - v0, th = fh - h0;
        auto at = [&](int vi, int hi) -> float {
            const size_t idx = (size_t(vi) * pointsH + hi) * mapPlanes;
            return idx < gains.size() ? gains[idx] : 1.f;
        };
        const double a = at(v0, h0) * (1 - th) + at(v0, h1) * th;
        const double b = at(v1, h0) * (1 - th) + at(v1, h1) * th;
        return float(a * (1 - tv) + b * tv);
    }
};

/** Locate OpcodeList2 (tag 51009) in IFD0 or any SubIFD. */
bool findOpcodeList2(const std::vector<uint8_t>& d, size_t& outOff, size_t& outLen) {
    if (d.size() < 8) return false;
    const bool le = (d[0] == 'I' && d[1] == 'I');
    if (!le && !(d[0] == 'M' && d[1] == 'M')) return false;

    std::vector<uint32_t> ifds;
    ifds.push_back(rdU32(&d[4], le));

    for (size_t guard = 0; guard < 16 && !ifds.empty(); ++guard) {
        const uint32_t off = ifds.back();
        ifds.pop_back();
        if (off == 0 || off + 2 > d.size()) continue;
        const uint16_t n = rdU16(&d[off], le);
        if (off + 2 + size_t(n) * 12 > d.size()) continue;
        for (uint16_t k = 0; k < n; ++k) {
            const size_t e = off + 2 + size_t(k) * 12;
            const uint16_t tag = rdU16(&d[e], le);
            const uint32_t cnt = rdU32(&d[e + 4], le);
            if (tag == 51009) {                       // OpcodeList2
                const uint32_t ptr = rdU32(&d[e + 8], le);
                if (ptr + cnt <= d.size()) {
                    outOff = ptr;
                    outLen = cnt;
                    return true;
                }
            } else if (tag == 330) {                  // SubIFDs
                const uint32_t ptr = rdU32(&d[e + 8], le);
                if (cnt == 1) {
                    ifds.push_back(ptr);
                } else if (ptr + cnt * 4u <= d.size()) {
                    for (uint32_t i = 0; i < cnt && i < 8; ++i)
                        ifds.push_back(rdU32(&d[ptr + i * 4], le));
                }
            }
        }
    }
    return false;
}

std::vector<GainMap> parseGainMaps(const std::vector<uint8_t>& d, size_t off, size_t len,
                                   int& outFound) {
    std::vector<GainMap> maps;
    outFound = 0;
    if (len < 4) return maps;
    const uint8_t* p = &d[off];
    const uint32_t count = rdU32BE(p);
    size_t pos = 4;
    for (uint32_t i = 0; i < count && pos + 16 <= len; ++i) {
        const uint32_t id = rdU32BE(p + pos);
        const uint32_t sz = rdU32BE(p + pos + 12);
        const size_t body = pos + 16;
        if (body + sz > len) break;
        if (id == 9) {                                 // GainMap
            ++outFound;
            if (sz >= 76) {
                GainMap m;
                const uint8_t* b = p + body;
                m.top = rdU32BE(b + 0);   m.left  = rdU32BE(b + 4);
                m.bottom = rdU32BE(b + 8); m.right = rdU32BE(b + 12);
                m.plane = rdU32BE(b + 16); m.planes = rdU32BE(b + 20);
                m.rowPitch = rdU32BE(b + 24); m.colPitch = rdU32BE(b + 28);
                m.pointsV = rdU32BE(b + 32);  m.pointsH = rdU32BE(b + 36);
                m.spacingV = rdF64BE(b + 40); m.spacingH = rdF64BE(b + 48);
                m.originV = rdF64BE(b + 56);  m.originH = rdF64BE(b + 64);
                m.mapPlanes = rdU32BE(b + 72);
                const size_t need = size_t(m.pointsV) * m.pointsH * m.mapPlanes;
                if (need > 0 && 76 + need * 4 <= sz &&
                    m.rowPitch > 0 && m.colPitch > 0) {
                    m.gains.resize(need);
                    for (size_t g = 0; g < need; ++g)
                        m.gains[g] = rdF32BE(b + 76 + g * 4);
                    maps.push_back(std::move(m));
                }
            }
        }
        pos = body + sz;
    }
    return maps;
}

}  // namespace

DngGainMapResult applyDngGainMaps(const std::string& dngPath,
                                  uint16_t* rawImage,
                                  int rawWidth,
                                  int rawHeight,
                                  int leftMargin,
                                  int topMargin,
                                  float black,
                                  float white) {
    DngGainMapResult res;
    if (!rawImage || rawWidth <= 0 || rawHeight <= 0) {
        res.note = "no raw buffer";
        return res;
    }

    std::FILE* f = std::fopen(dngPath.c_str(), "rb");
    if (!f) { res.note = "open failed"; return res; }
    std::fseek(f, 0, SEEK_END);
    const long fileLen = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    // The opcode list lives in the metadata, which sits near the head or tail;
    // reading the whole file would mean 24 MB for a phone DNG on every open.
    // Read a bounded window from each end and search both.
    const size_t kWindow = 4u * 1024u * 1024u;
    std::vector<uint8_t> buf;
    bool wholeFile = false;
    if (fileLen > 0 && size_t(fileLen) <= kWindow) {
        buf.resize(size_t(fileLen));
        wholeFile = std::fread(buf.data(), 1, buf.size(), f) == buf.size();
    } else if (fileLen > 0) {
        // IFD offsets are absolute, so a partial read only works if we keep the
        // original addressing: allocate the full length but populate the head
        // and tail windows. 24 MB of address space, ~8 MB actually touched.
        buf.assign(size_t(fileLen), 0);
        wholeFile = std::fread(buf.data(), 1, kWindow, f) == kWindow;
        if (wholeFile) {
            std::fseek(f, long(size_t(fileLen) - kWindow), SEEK_SET);
            wholeFile = std::fread(buf.data() + size_t(fileLen) - kWindow, 1, kWindow, f) == kWindow;
        }
    }
    std::fclose(f);
    if (!wholeFile) { res.note = "read failed"; return res; }

    size_t off = 0, len = 0;
    if (!findOpcodeList2(buf, off, len)) { res.note = "no OpcodeList2"; return res; }

    int found = 0;
    std::vector<GainMap> maps = parseGainMaps(buf, off, len, found);
    res.mapsFound = found;
    if (maps.empty()) { res.note = "no usable GainMap"; return res; }

    const float span = std::max(1.f, white - black);
    float lo = 1e9f, hi = -1e9f;
    for (const GainMap& m : maps) {
        // Each map covers one CFA phase: rows top, top+rowPitch, … and columns
        // left, left+colPitch, … Applying it to the wrong phase would swap the
        // per-channel corrections and make the cast worse, not better.
        // Opcode rectangles are ACTIVE-AREA coordinates; raw_image is the whole
        // sensor plane, so shift by the margins LibRaw reports. Adding the
        // margin also carries the CFA phase across correctly when a margin is
        // odd — applying a map to the wrong phase would swap the per-channel
        // corrections and deepen the cast instead of removing it.
        const int y0 = int(m.top) + topMargin, x0 = int(m.left) + leftMargin;
        const int y1 = std::min<int>(int(m.bottom) + topMargin, rawHeight);
        const int x1 = std::min<int>(int(m.right) + leftMargin, rawWidth);
        if (y0 >= y1 || x0 >= x1) continue;
        for (int y = y0; y < y1; y += int(m.rowPitch)) {
            uint16_t* row = rawImage + size_t(y) * rawWidth;
            for (int x = x0; x < x1; x += int(m.colPitch)) {
                const float g = m.sample(x - leftMargin, y - topMargin);
                if (g <= 0.f) continue;
                lo = std::min(lo, g);
                hi = std::max(hi, g);
                // Gain the SIGNAL, not the pedestal: lifting black would raise
                // the floor and grey out the shadows.
                const float sig = (float(row[x]) - black) * g;
                const float outv = black + sig;
                row[x] = uint16_t(std::clamp(outv, 0.f, black + span));
            }
        }
        ++res.mapsApplied;
    }

    if (res.mapsApplied > 0) {
        res.applied = true;
        res.minGain = lo;
        res.maxGain = hi;
        LOGI("applied %d/%d GainMap(s) — gains %.3f..%.3f (lens-shading; LibRaw "
             "does not run DNG opcodes without the Adobe SDK)",
             res.mapsApplied, res.mapsFound, lo, hi);
    } else {
        res.note = "maps out of bounds";
        LOGW("OpcodeList2 had %d GainMap(s) but none applied (%s)",
             found, res.note.c_str());
    }
    return res;
}

}  // namespace raw_v3
