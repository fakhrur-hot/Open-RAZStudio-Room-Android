/*
 * raz_engine.cpp — see raz_engine.h.
 *
 * The only pixel math here is the preview-only proxy downsample (a box/area
 * average). Everything that touches export pixels is delegated to the engine
 * kernel (runStageA / runStageC / runStageCToRGBA8) so preview and export
 * cannot diverge (CLAUDE.md #1).
 */
#include "raz_engine.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "v3/tiff_mmap_io.h"                // Stage-A BigTIFF reader/writer
#include "v3/lut3d.h"                       // parseCubeFile
#include "desktop/wic_encoder.h"            // raz::encodeWic (8-bit JPEG/PNG)
#include "desktop/shader_params_identity.h" // raz::kShaderParamsCount
#include "lr_preset_converter.h"            // Lightroom preset→cube conversion
#include "lensfun_android.h"                // lfa_cached_database / lfa_match_strict
#include "libraw/libraw.h"                  // identify-only pre-open probe
#include <fstream>
#include <sstream>

namespace razgui
{

    // ── half<->float ─────────────────────────────────────────────────────────
    // Stage-A pixels are IEEE-754 binary16 bit patterns. The desktop build enables
    // F16C (see CMakeLists-desktop.txt), so __fp16 conversions lower to native
    // vcvt instructions — bit-identical to the phone's ARM __fp16.
    static inline float halfToFloat(uint16_t h)
    {
        __fp16 v;
        std::memcpy(&v, &h, sizeof(v));
        return static_cast<float>(v);
    }
    static inline uint16_t floatToHalf(float f)
    {
        __fp16 v = static_cast<__fp16>(f);
        uint16_t h;
        std::memcpy(&h, &v, sizeof(h));
        return h;
    }

    // ── small helpers ──────────────────────────────────────────────────────────
    static std::string dirOf(const std::string &p)
    {
        size_t s = p.find_last_of("/\\");
        return (s == std::string::npos) ? std::string(".") : p.substr(0, s);
    }
    static std::string baseNoExt(const std::string &p)
    {
        size_t s = p.find_last_of("/\\");
        std::string name = (s == std::string::npos) ? p : p.substr(s + 1);
        size_t d = name.find_last_of('.');
        return (d == std::string::npos) ? name : name.substr(0, d);
    }

    static void buildToneCurveLut(const float *params, int count, std::vector<uint8_t> &out)
    {
        constexpr int curveBases[] = {293, 309, 325};
        bool hasExplicitCurve = false;
        bool changed = false;
        for (int base : curveBases)
        {
            for (int i = 0; i < 8; ++i)
            {
                const int ySlot = base + i * 2 + 1;
                if (ySlot < count && std::fabs(params[ySlot]) > 0.002f)
                    hasExplicitCurve = true;
                if (ySlot < count && std::fabs(params[ySlot] - i / 7.f) > 0.002f)
                    changed = true;
            }
        }
        if (!hasExplicitCurve || !changed)
        {
            out.clear();
            return;
        }
        out.resize(256 * 3);
        for (int x = 0; x < 256; ++x)
        {
            const float t = x / 255.f;
            const int segment = std::min(6, static_cast<int>(t * 7.f));
            const float local = t * 7.f - segment;
            for (int channel = 0; channel < 3; ++channel)
            {
                const int base = curveBases[channel];
                const float y0 = params[base + segment * 2 + 1];
                const float y1 = params[base + (segment + 1) * 2 + 1];
                const float value = std::clamp(y0 + (y1 - y0) * local, 0.f, 1.f);
                out[x * 3 + channel] = static_cast<uint8_t>(std::lround(value * 255.f));
            }
        }
    }

    bool loadCubeLut(const std::string &cubePath, LutData &out, std::string &err)
    {
        raw_v3::CubeLut c = raw_v3::parseCubeFile(cubePath);
        if (c.size <= 0 || c.rgb.empty())
        {
            err = "parseCubeFile failed or empty: " + cubePath;
            return false;
        }
        out.rgb = std::move(c.rgb);
        out.size = c.size;
        for (int i = 0; i < 3; ++i)
        {
            out.domainMin[i] = c.domainMin[i];
            out.domainMax[i] = c.domainMax[i];
        }
        return true;
    }

    bool loadLutFile(const std::string &lutPath, LutData &out, std::string &err)
    {
        // Check file extension
        size_t dot = lutPath.find_last_of('.');
        if (dot == std::string::npos)
        {
            err = "No file extension: " + lutPath;
            return false;
        }
        std::string ext = lutPath.substr(dot + 1);
        // Lowercase
        for (auto &c : ext)
            c = std::tolower((unsigned char)c);

        // If .cube, load directly
        if (ext == "cube")
        {
            return loadCubeLut(lutPath, out, err);
        }

        // If .xmp or .lrtemplate, convert to cube first
        if (ext == "xmp" || ext == "lrtemplate")
        {
            // Read preset file
            std::ifstream file(lutPath, std::ios::binary);
            if (!file.is_open())
            {
                err = "Cannot open: " + lutPath;
                return false;
            }
            std::stringstream buffer;
            buffer << file.rdbuf();
            std::string presetText = buffer.str();
            file.close();

            // Convert to cube string
            std::string cubeString = LrPresetConverter::convertToCubeString(presetText);
            if (cubeString.empty())
            {
                err = "Failed to convert Lightroom preset: " + lutPath;
                return false;
            }

            // Write cube string to a temporary file
            std::string tempPath = lutPath + ".tmp.cube";
            std::ofstream tempFile(tempPath, std::ios::binary);
            if (!tempFile.is_open())
            {
                err = "Cannot write temp file: " + tempPath;
                return false;
            }
            tempFile << cubeString;
            tempFile.close();

            // Load the cube
            bool ok = loadCubeLut(tempPath, out, err);

            // Delete temp file
            std::remove(tempPath.c_str());

            return ok;
        }

        err = "Unsupported LUT format: " + ext + " (expected .cube, .xmp, or .lrtemplate)";
        return false;
    }

    // ── proxy downsample ─────────────────────────────────────────────────────
    // Area-average the full-res Stage-A BigTIFF into a Stage-A BigTIFF of the
    // proxy dims. Reads via the mmap reader (no full-float allocation); samples
    // each source pixel straight from its strip. Preview-only; approximate by
    // design (export uses the full-res cache).
    static bool downsampleStageA(const std::string &srcTif, const std::string &dstTif,
                                 int longSide, uint32_t &outW, uint32_t &outH,
                                 std::string &err)
    {
        raw_v3::StageATiffReader *r = raw_v3::openStageATiff(srcTif);
        if (!r)
        {
            err = "openStageATiff failed: " + srcTif;
            return false;
        }
        const raw_v3::StageATiffHeader &h = raw_v3::getStageATiffHeader(r);
        const uint32_t sw = h.width, sh = h.height;
        if (sw == 0 || sh == 0)
        {
            raw_v3::closeStageATiff(r);
            err = "zero source dims";
            return false;
        }

        // Target dims preserve aspect; never upscale.
        double scale = std::min(1.0, static_cast<double>(longSide) / std::max(sw, sh));
        uint32_t dw = std::max<uint32_t>(1, static_cast<uint32_t>(std::lround(sw * scale)));
        uint32_t dh = std::max<uint32_t>(1, static_cast<uint32_t>(std::lround(sh * scale)));

        const uint32_t rps = h.rowsPerStrip ? h.rowsPerStrip : raw_v3::TIFF_ROWS_PER_STRIP;

        std::vector<uint16_t> dst(static_cast<size_t>(dw) * dh * 4);

        for (uint32_t dy = 0; dy < dh; ++dy)
        {
            // source row span [y0,y1) covered by this proxy row
            uint32_t y0 = static_cast<uint32_t>(static_cast<uint64_t>(dy) * sh / dh);
            uint32_t y1 = static_cast<uint32_t>(static_cast<uint64_t>(dy + 1) * sh / dh);
            if (y1 <= y0)
                y1 = y0 + 1;
            for (uint32_t dx = 0; dx < dw; ++dx)
            {
                uint32_t x0 = static_cast<uint32_t>(static_cast<uint64_t>(dx) * sw / dw);
                uint32_t x1 = static_cast<uint32_t>(static_cast<uint64_t>(dx + 1) * sw / dw);
                if (x1 <= x0)
                    x1 = x0 + 1;
                float acc[4] = {0, 0, 0, 0};
                uint32_t n = 0;
                for (uint32_t y = y0; y < y1; ++y)
                {
                    const uint16_t *strip = raw_v3::getStageATiffStrip(r, y / rps);
                    if (!strip)
                        continue;
                    const uint32_t rowInStrip = y % rps;
                    const uint16_t *row = strip + static_cast<size_t>(rowInStrip) * sw * 4;
                    for (uint32_t x = x0; x < x1; ++x)
                    {
                        const uint16_t *px = row + static_cast<size_t>(x) * 4;
                        acc[0] += halfToFloat(px[0]);
                        acc[1] += halfToFloat(px[1]);
                        acc[2] += halfToFloat(px[2]);
                        acc[3] += halfToFloat(px[3]);
                        ++n;
                    }
                }
                uint16_t *o = dst.data() + (static_cast<size_t>(dy) * dw + dx) * 4;
                const float inv = n ? 1.0f / n : 0.0f;
                o[0] = floatToHalf(acc[0] * inv);
                o[1] = floatToHalf(acc[1] * inv);
                o[2] = floatToHalf(acc[2] * inv);
                o[3] = floatToHalf(n ? acc[3] * inv : 1.0f);
            }
        }
        raw_v3::closeStageATiff(r);

        if (!raw_v3::writeStageATiff(dstTif, dst.data(), dw, dh))
        {
            err = "writeStageATiff failed: " + dstTif;
            return false;
        }
        outW = dw;
        outH = dh;
        return true;
    }

    // ── Lens-correction pre-open probe ───────────────────────────────────────────

    LensProbeResult probeLensProfile(const std::string &inputPath, const std::string &lensfunDbDir)
    {
        LensProbeResult r;

        size_t dot = inputPath.find_last_of('.');
        if (dot != std::string::npos)
        {
            r.formatExt = inputPath.substr(dot + 1);
            for (auto &c : r.formatExt)
                c = (char)tolower((unsigned char)c);
        }

        LibRaw raw;
        // open_file() only parses headers/EXIF; no unpack()/dcraw_process() call
        // follows, so this is the same cheap "identify" pass Android's
        // nativeLensfunProbe uses (v3_jni.cpp) — safe to run on every file the
        // user picks, RAW or JPEG, before committing to a full decode.
        int rc = raw.open_file(inputPath.c_str());
        if (rc != LIBRAW_SUCCESS)
        {
            r.ok = false;
            r.error = libraw_strerror(rc);
            return r;
        }
        r.ok = true;
        // raw_count is LibRaw's count of actual RAW frames found in the file;
        // 0 for a plain JPEG/PNG that LibRaw can still open for EXIF but has no
        // Bayer/X-Trans data to decode.
        r.isRaw = raw.imgdata.idata.raw_count > 0;

        r.cameraMake = raw.imgdata.idata.make ? raw.imgdata.idata.make : "";
        r.cameraModel = raw.imgdata.idata.model ? raw.imgdata.idata.model : "";
        r.lensMake = raw.imgdata.lens.LensMake ? raw.imgdata.lens.LensMake : "";
        r.lensModel = raw.imgdata.lens.Lens ? raw.imgdata.lens.Lens : "";
        r.focalMm = raw.imgdata.other.focal_len;
        r.aperture = raw.imgdata.other.aperture;
        raw.recycle();

        if (!lensfunDbDir.empty())
        {
            const LfDatabase *db = lfa_cached_database(lensfunDbDir.c_str());
            if (db)
            {
                LfaMatch m = lfa_match_strict(*db,
                                              r.cameraMake.c_str(), r.cameraModel.c_str(),
                                              r.lensMake.c_str(), r.lensModel.c_str());
                // `matched` still means "a correction can be applied" (needs BOTH),
                // but report each half independently so the caller can show the
                // resolved body even when the lens didn't match — parity with the
                // Android workspace UI (see nativeLensfunMatch).
                r.matched = m.ok();
                if (m.cam)
                {
                    r.matchedCameraModel = m.cam->model;
                    r.matchedCropFactor = m.cam->cropFactor;
                }
                if (m.lens)
                    r.matchedLensModel = m.lens->model;
            }
        }
        return r;
    }

    std::vector<LensfunDbEntry> lensfunCameraList(const std::string &lensfunDbDir)
    {
        std::vector<LensfunDbEntry> out;
        if (lensfunDbDir.empty())
            return out;
        const LfDatabase *db = lfa_cached_database(lensfunDbDir.c_str());
        if (!db)
            return out;
        out.reserve(db->cameras.size());
        for (auto &c : db->cameras)
            out.push_back({c.maker, c.model, c.cropFactor});
        return out;
    }

    std::vector<LensfunDbEntry> lensfunLensList(const std::string &lensfunDbDir)
    {
        std::vector<LensfunDbEntry> out;
        if (lensfunDbDir.empty())
            return out;
        const LfDatabase *db = lfa_cached_database(lensfunDbDir.c_str());
        if (!db)
            return out;
        out.reserve(db->lenses.size());
        for (auto &l : db->lenses)
            out.push_back({l.maker, l.model, 0.f});
        return out;
    }

    // ── RazEngine ──────────────────────────────────────────────────────────────
    RazEngine::~RazEngine() { close(); }

    void RazEngine::close()
    {
        if (!workTif_.empty())
            std::remove(workTif_.c_str());
        if (!proxyTif_.empty())
            std::remove(proxyTif_.c_str());
        workTif_.clear();
        proxyTif_.clear();
        srcW_ = srcH_ = proxyW_ = proxyH_ = 0;
        meta_ = raw_v3::StageAMetadata{};
        // aiProbe_ is intentionally NOT reset here: the ONNX session is a
        // long-lived model load, independent of any single open image.
    }

    void RazEngine::initAiProbe(const std::string &modelPath)
    {
        aiProbe_ = ZeroDceProbe::create(modelPath);
        // create() never returns null (see zero_dce_probe.cpp); isReady() is
        // false if the model file was missing or ORT init failed. Either way,
        // probeImage() below degrades to -1 rather than throwing.
    }

    float RazEngine::probeImage(const uint8_t *rgba, int width, int height)
    {
        if (!aiProbe_ || !aiProbe_->isReady())
        {
            lastAiScore_ = -1.f;
            return -1.f;
        }
        lastAiScore_ = aiProbe_->probeAverageLift(rgba, width, height);
        return lastAiScore_;
    }

    OpenResult RazEngine::open(const std::string &inputPath, int proxyLongSide,
                               const raw_v3::StageAOptions &sa, const std::string &cacheDir)
    {
        close();
        OpenResult res{};
        cacheDir_ = cacheDir.empty() ? dirOf(inputPath) : cacheDir;
        const std::string stem = cacheDir_ + "/." + baseNoExt(inputPath) + "_raz";
        workTif_ = stem + "_A.tif";
        proxyTif_ = stem + "_proxy.tif";

        meta_ = raw_v3::runStageA(inputPath, workTif_, sa);
        if (!meta_.success)
        {
            res.error = meta_.errorMessage.empty() ? "runStageA failed" : meta_.errorMessage;
            workTif_.clear();
            return res;
        }
        srcW_ = meta_.width;
        srcH_ = meta_.height;

        std::string derr;
        if (proxyLongSide > 0 &&
            downsampleStageA(workTif_, proxyTif_, proxyLongSide, proxyW_, proxyH_, derr))
        {
            // proxy ready
        }
        else
        {
            // Fall back to full-res as the "proxy" (preview will be slow but correct).
            std::remove(proxyTif_.c_str());
            proxyTif_ = workTif_;
            proxyW_ = srcW_;
            proxyH_ = srcH_;
        }

        res.ok = true;
        res.meta = meta_;
        return res;
    }

    raw_v3::StageCOptions RazEngine::makeOptions(const float *params, int count,
                                                 const LutData *lut,
                                                 raw_v3::StageCExif &exif) const
    {
        raw_v3::StageCOptions o{};
        o.params = params;
        o.paramsCount = count;
        buildToneCurveLut(params, count, toneCurveLut_);
        o.toneCurveLut = toneCurveLut_.empty() ? nullptr : toneCurveLut_.data();
        if (lut && !lut->empty())
        {
            o.lutData = lut->rgb.data();
            o.lutSize = lut->size;
            for (int i = 0; i < 3; ++i)
            {
                o.lutDomainMin[i] = lut->domainMin[i];
                o.lutDomainMax[i] = lut->domainMax[i];
            }
        }
        // EXIF carried from the decode so exports keep camera/lens/shot data.
        exif.make = meta_.cameraMake;
        exif.model = meta_.cameraModel;
        exif.lensModel = meta_.lensModel;
        exif.dateTimeOriginal = meta_.dateTimeOriginal;
        exif.dateTime = meta_.dateTimeOriginal;
        exif.iso = meta_.iso;
        exif.exposureTime = meta_.shutterSpeed;
        exif.fNumber = meta_.aperture;
        exif.focalLength = meta_.focalLength;
        o.exif = &exif;
        return o;
    }

    bool RazEngine::renderProxy(const float *params, int count, const LutData *lut,
                                std::vector<uint8_t> &outRGBA, uint32_t &outW, uint32_t &outH,
                                int64_t &durationMs, std::string &err)
    {
        if (proxyTif_.empty())
        {
            err = "no image open";
            return false;
        }
        raw_v3::StageCExif exif{};
        raw_v3::StageCOptions o = makeOptions(params, count, lut, exif);
        o.exif = nullptr; // preview: skip EXIF work (RGBA8 path ignores it anyway)
        o.format = raw_v3::StageCFormat::Bitmap8888;

        outW = proxyW_;
        outH = proxyH_;
        const uint32_t stride = outW * 4;
        outRGBA.assign(static_cast<size_t>(stride) * outH, 0);

        raw_v3::StageCResult sc = raw_v3::runStageCToRGBA8(proxyTif_, o, outRGBA.data(), stride);
        if (!sc.success)
        {
            err = sc.error.empty() ? "runStageCToRGBA8 failed" : sc.error;
            return false;
        }
        durationMs = sc.durationMs;
        return true;
    }

    bool RazEngine::exportTiff16(const float *params, int count, const LutData *lut,
                                 const std::string &outPath, std::string &err)
    {
        if (workTif_.empty())
        {
            err = "no image open";
            return false;
        }
        raw_v3::StageCExif exif{};
        raw_v3::StageCOptions o = makeOptions(params, count, lut, exif);
        o.format = raw_v3::StageCFormat::Tiff16;
        raw_v3::StageCResult sc = raw_v3::runStageC(workTif_, outPath, o);
        if (!sc.success)
        {
            err = sc.error.empty() ? "runStageC failed" : sc.error;
            return false;
        }
        return true;
    }

    bool RazEngine::exportJpegOrPng(const float *params, int count, const LutData *lut,
                                    const std::string &outPath, bool png, int jpegQuality,
                                    std::string &err)
    {
        if (workTif_.empty())
        {
            err = "no image open";
            return false;
        }
        raw_v3::StageCExif exif{};
        raw_v3::StageCOptions o = makeOptions(params, count, lut, exif);
        o.exif = nullptr; // RGBA8 path ignores StageCOptions::exif; we pass EXIF via WIC below.
        o.format = raw_v3::StageCFormat::Bitmap8888;

        const uint32_t w = srcW_, h = srcH_, stride = w * 4;
        std::vector<uint8_t> rgba(static_cast<size_t>(stride) * h, 0);
        raw_v3::StageCResult sc = raw_v3::runStageCToRGBA8(workTif_, o, rgba.data(), stride);
        if (!sc.success)
        {
            err = sc.error.empty() ? "runStageCToRGBA8 failed" : sc.error;
            return false;
        }

        raz::EncodeMeta m{};
        m.make = meta_.cameraMake;
        m.model = meta_.cameraModel;
        m.lensModel = meta_.lensModel;
        m.dateTimeOriginal = meta_.dateTimeOriginal;
        m.dateTime = meta_.dateTimeOriginal;
        m.iso = meta_.iso;
        m.exposureTime = meta_.shutterSpeed;
        m.fNumber = meta_.aperture;
        m.focalLength = meta_.focalLength;
        return raz::encodeWic(outPath, rgba.data(), w, h, stride,
                              png ? raz::WicFormat::Png : raz::WicFormat::Jpeg,
                              jpegQuality, m, &err);
    }

} // namespace razgui
