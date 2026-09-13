/*
 * raz_engine — desktop GUI facade over the RAW pipeline.
 *
 * This is the SINGLE seam the Windows GUI (Dear ImGui phase) drives. It wraps
 * the exact same engine razbatch.exe links — raw_v3::runStageA / apply_macro /
 * runStageC(ToRGBA8) — so preview and export come from one kernel and cannot
 * drift (CLAUDE.md hard rule #1).
 *
 * Contract, matching Android's Stage B (proxy preview) vs Stage C (export):
 *   open()        — decode the RAW/TIFF ONCE into a full-res Stage-A cache, and
 *                   area-downsample that into a small Stage-A proxy cache.
 *   renderProxy() — run the real apply_macro kernel on the PROXY (fast, per
 *                   slider tick). Preview may approximate; it is not "truth".
 *   exportTiff16()/exportJpeg()/exportPng()
 *                 — run the real kernel on the FULL-RES cache. This is truth.
 *
 * The 410-float ShaderParams blob is owned by the caller (start from
 * raz::kShaderParamsIdentity). The engine sources are compiled UNMODIFIED; this
 * file adds no pixel math of its own except the preview-only proxy downsample.
 *
 * Windows-only today (WIC encoder + compat shims), same as razbatch.
 */
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "v3/stage_a.h"          // raw_v3::StageAOptions / StageAMetadata
#include "v3/stage_c_export.h"   // raw_v3::StageCOptions / runStageC(ToRGBA8)
#include "zero_dce_probe.h"      // AI: Zero-DCE low-light detection

namespace razgui {

// A parsed 3D LUT ready to feed StageCOptions. Empty = no LUT.
struct LutData {
    std::vector<float> rgb;              // size³ × 3, interleaved RGB
    int   size = 0;
    float domainMin[3] = {0.f, 0.f, 0.f};
    float domainMax[3] = {1.f, 1.f, 1.f};
    bool  empty() const { return size <= 0 || rgb.empty(); }
};

// Parse a .cube file into LutData (wraps raw_v3::parseCubeFile). false on error.
bool loadCubeLut(const std::string& cubePath, LutData& out, std::string& err);

// Load any LUT format: .cube, .xmp (Lightroom preset), or .lrtemplate (Lightroom template).
// XMP/LRTEMPLATE are converted to cube on-the-fly. false on error.
bool loadLutFile(const std::string& lutPath, LutData& out, std::string& err);

struct OpenResult {
    bool        ok = false;
    std::string error;
    raw_v3::StageAMetadata meta;   // dims / camera / lens / exif for the status bar
};

// ── Lens-correction pre-open probe ───────────────────────────────────────────
// A cheap EXIF-only read (LibRaw open_file/identify, no unpack/decode) so the
// "before you open" dialog can show camera/lens/focal/aperture and an
// auto-matched Lensfun profile before paying for the real decode. Mirrors the
// Android `nativeLensfunProbe` JNI call (v3_jni.cpp) — same LibRaw fields,
// same lfa_match_strict call — just without the JNI marshalling.
struct LensProbeResult {
    bool        ok = false;      // false = file couldn't even be opened for identify
    std::string error;

    std::string formatExt;       // lowercase extension, e.g. "cr2", "jpg"
    bool        isRaw = false;   // heuristic: LibRaw reports a Bayer/X-Trans pattern

    // Raw EXIF strings/numbers as LibRaw parsed them (pre-match, pre-override).
    std::string cameraMake, cameraModel;
    std::string lensMake, lensModel;
    float       focalMm  = 0.f;  // 0 = not reported (manual/adapted lens)
    float       aperture = 0.f;

    // Result of matching the above against the Lensfun DB (lensfunDbDir).
    // Empty dbDir or no confident match => matched=false; correction would be
    // skipped exactly as it is in runStageA's own internal match.
    bool        matched = false;
    std::string matchedCameraModel;
    std::string matchedLensModel;
    float       matchedCropFactor = 0.f;
};

// EXIF-only probe: opens inputPath via LibRaw (identify pass, no decode) and,
// if lensfunDbDir is non-empty, resolves it against the Lensfun DB with the
// SAME lfa_match_strict Stage A itself uses. ok=false only on an unreadable
// file; a failed lens match still returns ok=true with matched=false.
LensProbeResult probeLensProfile(const std::string& inputPath, const std::string& lensfunDbDir);

// One entry from the Lensfun DB, for a manual-override picker list.
struct LensfunDbEntry {
    std::string maker;
    std::string model;
    float       cropFactor = 0.f;  // cameras only; 0 for lens entries
};

// Enumerate every camera/lens profile in the DB (for the manual-override
// browser). Empty on a bad dbDir. Cheap after the first call — the DB itself
// is process-wide cached by lfa_cached_database.
std::vector<LensfunDbEntry> lensfunCameraList(const std::string& lensfunDbDir);
std::vector<LensfunDbEntry> lensfunLensList(const std::string& lensfunDbDir);

class RazEngine {
public:
    RazEngine() = default;
    ~RazEngine();
    RazEngine(const RazEngine&) = delete;
    RazEngine& operator=(const RazEngine&) = delete;

    /*
     * Decode inputPath (RAW or TIFF) via runStageA into a full-res Stage-A
     * cache, then area-downsample to a proxy whose long side is proxyLongSide
     * px (source dims if smaller). sa carries decode-time options (lensfun DB
     * dir, AI-recovery model bytes, dual-threshold, ...). Overwrites any prior
     * open. Caches live in cacheDir (defaults to a temp dir beside inputPath).
     */
    OpenResult open(const std::string& inputPath,
                    int proxyLongSide,
                    const raw_v3::StageAOptions& sa,
                    const std::string& cacheDir = "");

    bool isOpen() const { return !workTif_.empty(); }
    uint32_t srcWidth()  const { return srcW_; }
    uint32_t srcHeight() const { return srcH_; }
    uint32_t proxyWidth()  const { return proxyW_; }
    uint32_t proxyHeight() const { return proxyH_; }
    const raw_v3::StageAMetadata& metadata() const { return meta_; }

    /*
     * Render the PROXY through apply_macro. params/count = the 410-float blob.
     * lut may be null (no 3D LUT); when set, the caller must also have set
     * slots [29] lutEnabled + [31] lutIntensity in the blob (the kernel gates
     * on those, not on lutData presence — GOTCHAS). outRGBA is resized to
     * proxyW*proxyH*4. durationMs receives the kernel time.
     */
    bool renderProxy(const float* params, int count,
                     const LutData* lut,
                     std::vector<uint8_t>& outRGBA,
                     uint32_t& outW, uint32_t& outH,
                     int64_t& durationMs,
                     std::string& err);

    // Full-res 16-bit TIFF export (raw_v3::runStageC). Embeds EXIF from open().
    bool exportTiff16(const float* params, int count,
                      const LutData* lut,
                      const std::string& outPath,
                      std::string& err);

    // Full-res 8-bit export via runStageCToRGBA8 + WIC. png=false → JPEG.
    bool exportJpegOrPng(const float* params, int count,
                         const LutData* lut,
                         const std::string& outPath,
                         bool png, int jpegQuality,
                         std::string& err);

    // Delete the on-disk caches. Called by the destructor.
    void close();

    /*
     * AI: initialize the Zero-DCE low-light probe from a model file path
     * (e.g. "engine/models/zero_dce.onnx", resolved relative to the exe).
     * Safe to call once at startup; a no-op if the model can't be found —
     * probeImage() then returns -1 and callers should treat AI features as
     * unavailable rather than failing the whole app.
     */
    void initAiProbe(const std::string& modelPath);

    /*
     * AI: run the Zero-DCE probe on an already-rendered proxy RGBA8 buffer
     * (the SAME buffer renderProxy() just produced — no extra render pass).
     * Returns the average-lift score in [0,1], or -1 if the probe isn't
     * ready. Caches the last score (lastAiScore()) for status-bar display.
     */
    float probeImage(const uint8_t* rgba, int width, int height);

    bool  aiProbeReady() const { return aiProbe_ && aiProbe_->isReady(); }
    float lastAiScore()  const { return lastAiScore_; }

private:
    // Fill a StageCOptions with the LUT + EXIF wiring shared by all render
    // paths. `exifStore` must outlive the returned options (it points into it).
    raw_v3::StageCOptions makeOptions(const float* params, int count,
                                      const LutData* lut,
                                      raw_v3::StageCExif& exifStore) const;

    std::string workTif_;    // full-res Stage-A cache (truth)
    std::string proxyTif_;   // downscaled Stage-A cache (preview)
    std::string cacheDir_;
    uint32_t srcW_ = 0, srcH_ = 0;
    uint32_t proxyW_ = 0, proxyH_ = 0;
    raw_v3::StageAMetadata meta_;

    std::unique_ptr<ZeroDceProbe> aiProbe_;
    float lastAiScore_ = -1.f;
};

}  // namespace razgui
