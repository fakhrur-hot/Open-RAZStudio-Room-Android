/*
 * razbatch — desktop CLI for the RAZStudio Room batch pipeline
 * ============================================================
 * Runs the SAME Stage A -> Stage C path the Android app's batch mode runs,
 * so a file processed here matches the phone.
 *
 * Contract (consumed by WinBatch\RAZBatch.ps1):
 *
 *   razbatch --input <file> --output <dir>
 *            [--route CameraColor|RawDepth]
 *            [--highlight-protection Off|Low|Standard|Strong]
 *            [--exif KeepAll|StripSensitive|NoneExceptSoftware]
 *            [--gpu auto|vulkan|opengl|cpu]
 *            [--lens-correction] [--ai-reconstruct] [--embed-icc]
 *            [--preset <preset.xml>]
 *            [--lensfun-db <dir>] [--format tiff16|png16] [--quiet]
 *
 * Exit codes:  0 ok | 1 bad usage | 2 Stage A failed | 3 Stage C failed
 *              4 unimplemented option supplied (see NOT-YET-IMPLEMENTED)
 *
 * NOT-YET-IMPLEMENTED (fails loudly rather than silently doing the wrong
 * thing — a wrong image the user trusts is worse than a clear error):
 *   --preset   The preset action stack must be flattened to the 410-float
 *              ShaderParams blob by a C++ port of RawV3ActionReplay.flatten.
 *              Until that exists this flag is rejected.
 *   --gpu      Only `cpu` is accepted. `cpu` is the parity reference; a GPU
 *              path must be validated against it before being offered.
 *   --exif / --embed-icc
 *              Metadata is not yet copied to the output.
 */

#include <cstdio>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <chrono>
#include <filesystem>

#include "v3/stage_a.h"
#include "v3/stage_c_export.h"
#include "v3/lut3d.h"          // parseCubeFile (GL half is RAZ_NO_EGL-guarded)
#include "v3/tiff_mmap_io.h"   // Stage A header dims for the 8-bit encode path
#include "shader_params_identity.h"
#include "srgb_icc_profile.h"
#include "wic_encoder.h"
#include "../libraw/libraw.h"   // --reference-libraw diagnostic decode

/* Defined here; declared by compat/android/log.h. --quiet clears it. */
extern "C" { int raz_log_enabled = 1; }

namespace fs = std::filesystem;

namespace {

struct Args {
    std::string input, outputDir, preset, presetBlob, lensfunDb, lut;
    std::string wb = "camera";   // camera | auto | daylight  (StageAOptions.wbSource)
    std::string route      = "RawDepth";
    std::string highlight  = "Standard";
    std::string exif       = "KeepAll";
    std::string gpu        = "cpu";
    std::string format     = "tiff16";   // tiff16 | jpg | png
    int         quality    = 92;         // JPEG only
    bool lensCorrection = false;
    bool aiReconstruct  = false;
    bool embedIcc       = false;
    bool quiet          = false;
    bool referenceLibRaw = false;   // diagnostic: stock-LibRaw decode only
    float dualThreshold  = -1.f;    // >=0 pins dualContrastThreshold (disables auto)
};

void usage() {
    fprintf(stderr,
        "razbatch — RAZStudio Room desktop batch engine\n\n"
        "  --input <file>                 RAW or JPEG source (required)\n"
        "  --output <dir>                 output directory (required)\n"
        "  --route CameraColor|RawDepth   colour pipeline (default RawDepth)\n"
        "  --highlight-protection Off|Low|Standard|Strong\n"
        "  --exif KeepAll|StripSensitive|NoneExceptSoftware\n"
        "  --gpu auto|vulkan|opengl|cpu   (only 'cpu' implemented)\n"
        "  --lens-correction              per-file lensfun correction\n"
        "  --ai-reconstruct               pre-demosaic HDR/shadow recovery\n"
        "  --embed-icc                    embed sRGB ICC profile\n"
        "  --preset-blob <f.razparams>    flattened preset (from razparams.jar)\n"
        "  --preset <file.xml>            rejected: flatten it first, see below\n"
        "  --lut <file.cube>              3D LUT applied in Stage C\n"
        "  --wb camera|auto|daylight      white-balance source (default camera)\n"
        "  --lensfun-db <dir>             lensfun XML dir (default: ./lensfun_db)\n"
        "  --format tiff16|jpg|png        output format (default tiff16)\n"
        "                                 tiff16 = 16-bit, ICC + full EXIF\n"
        "                                 jpg    = 8-bit, ICC + EXIF (via WIC)\n"
        "                                 png    = 8-bit, ICC only (no EXIF)\n"
        "  --quality <1..100>             JPEG quality (default 92)\n"
        "  --quiet                        suppress pipeline logging\n");
}

bool needsValue(const std::string& f) {
    return f == "--input" || f == "--output" || f == "--route" ||
           f == "--highlight-protection" || f == "--exif" || f == "--gpu" ||
           f == "--preset" || f == "--preset-blob" || f == "--lensfun-db" ||
           f == "--lut" || f == "--wb" || f == "--dual-threshold" ||
           f == "--format" || f == "--quality";
}

/*
 * Load a .razparams blob: kShaderParamsCount little-endian float32s produced
 * by razparams.jar (which runs the app's real RawV3ActionReplay.flatten).
 * Strict on size — a truncated or stale-ABI blob would be read as garbage
 * parameters and silently produce a wrong image.
 */
bool loadPresetBlob(const std::string& path, std::vector<float>& out) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) { fprintf(stderr, "razbatch: cannot open preset blob '%s'\n", path.c_str()); return false; }
    fseek(f, 0, SEEK_END);
    const long bytes = ftell(f);
    fseek(f, 0, SEEK_SET);
    const long want = static_cast<long>(raz::kShaderParamsCount) * 4;
    if (bytes != want) {
        fprintf(stderr,
                "razbatch: preset blob is %ld bytes, expected %ld (%d floats).\n"
                "  The blob was produced by a different ShaderParams version — "
                "regenerate it with razparams.jar against the current sources.\n",
                bytes, want, raz::kShaderParamsCount);
        fclose(f);
        return false;
    }
    out.resize(raz::kShaderParamsCount);
    const size_t got = fread(out.data(), 4, raz::kShaderParamsCount, f);
    fclose(f);
    if (got != static_cast<size_t>(raz::kShaderParamsCount)) {
        fprintf(stderr, "razbatch: short read on preset blob\n");
        return false;
    }
    return true;
}

/*
 * --reference-libraw : decode with STOCK LibRaw only and write a JPEG.
 *
 * Diagnostic, not a product feature. It bypasses Stage A's dual AMaZE+LMMSE
 * path, our normalisation and our rgb_cam application entirely, so it answers
 * one question that single-pixel logs could not: is a colour cast OURS or
 * LibRaw's/the profile's?
 *
 *   reference neutral + razbatch cast -> fault is in our dual path
 *   BOTH cast                         -> fault is upstream (camera matrix /
 *                                        profile), not our blend
 *
 * Settings deliberately mirror what stage_a.cpp pins for its AMaZE arm
 * (use_camera_wb, output_color=1 sRGB, sRGB transfer, no_auto_bright) so the
 * only differences left are the ones under investigation.
 */
bool referenceLibRawDecode(const std::string& in, const std::string& out) {
    LibRaw lr;
    lr.imgdata.params.use_camera_wb   = 1;
    lr.imgdata.params.use_auto_wb     = 0;
    lr.imgdata.params.output_color    = 1;      // sRGB primaries
    lr.imgdata.params.output_bps      = 8;
    lr.imgdata.params.gamm[0]         = 1.0 / 2.4;   // sRGB transfer, as stage_a
    lr.imgdata.params.gamm[1]         = 12.92;
    lr.imgdata.params.no_auto_bright  = 1;      // scene-linear, like our pipeline
    lr.imgdata.params.user_flip       = 0;
    lr.imgdata.params.highlight       = 0;

    int r = lr.open_file(in.c_str());
    if (r != LIBRAW_SUCCESS) {
        fprintf(stderr, "reference: open_file failed: %s\n", libraw_strerror(r));
        return false;
    }
    if ((r = lr.unpack()) != LIBRAW_SUCCESS) {
        fprintf(stderr, "reference: unpack failed: %s\n", libraw_strerror(r));
        return false;
    }
    if ((r = lr.dcraw_process()) != LIBRAW_SUCCESS) {
        fprintf(stderr, "reference: dcraw_process failed: %s\n", libraw_strerror(r));
        return false;
    }
    libraw_processed_image_t* img = lr.dcraw_make_mem_image(&r);
    if (!img) {
        fprintf(stderr, "reference: make_mem_image failed: %s\n", libraw_strerror(r));
        return false;
    }
    fprintf(stderr, "reference: LibRaw %dx%d colors=%d bits=%d\n",
            img->width, img->height, img->colors, img->bits);
    bool ok = false;
    if (img->colors == 3 && img->bits == 8) {
        // WIC encoder wants RGBA; LibRaw gives packed RGB.
        const size_t n = size_t(img->width) * img->height;
        std::vector<uint8_t> rgba(n * 4);
        for (size_t i = 0; i < n; ++i) {
            rgba[i * 4 + 0] = img->data[i * 3 + 0];
            rgba[i * 4 + 1] = img->data[i * 3 + 1];
            rgba[i * 4 + 2] = img->data[i * 3 + 2];
            rgba[i * 4 + 3] = 255;
        }
        raz::EncodeMeta meta{};
        std::string err;
        ok = raz::encodeWic(out, rgba.data(), img->width, img->height,
                            img->width * 4, raz::WicFormat::Jpeg, 92, meta, &err);
        if (!ok) fprintf(stderr, "reference: encode failed: %s\n", err.c_str());
    } else {
        fprintf(stderr, "reference: unexpected format colors=%d bits=%d\n",
                img->colors, img->bits);
    }
    LibRaw::dcraw_clear_mem(img);
    return ok;
}

bool parse(int argc, char** argv, Args& a) {
    for (int i = 1; i < argc; ++i) {
        const std::string f = argv[i];
        if (needsValue(f)) {
            if (i + 1 >= argc) { fprintf(stderr, "razbatch: %s needs a value\n", f.c_str()); return false; }
            const std::string v = argv[++i];
            if      (f == "--input")      a.input     = v;
            else if (f == "--output")     a.outputDir = v;
            else if (f == "--route")      a.route     = v;
            else if (f == "--highlight-protection") a.highlight = v;
            else if (f == "--exif")       a.exif      = v;
            else if (f == "--gpu")        a.gpu       = v;
            else if (f == "--preset")      a.preset     = v;
            else if (f == "--preset-blob") a.presetBlob = v;
            else if (f == "--lut")        a.lut       = v;
            else if (f == "--wb")         a.wb        = v;
            else if (f == "--dual-threshold") a.dualThreshold = float(atof(v.c_str()));
            else if (f == "--lensfun-db") a.lensfunDb = v;
            else if (f == "--format")      a.format    = v;
            else if (f == "--quality")     a.quality   = std::atoi(v.c_str());
        }
        else if (f == "--lens-correction") a.lensCorrection = true;
        else if (f == "--ai-reconstruct")  a.aiReconstruct  = true;
        else if (f == "--embed-icc")       a.embedIcc       = true;
        else if (f == "--quiet")           a.quiet          = true;
        else if (f == "--reference-libraw") a.referenceLibRaw = true;
        else if (f == "-h" || f == "--help") { usage(); return false; }
        else { fprintf(stderr, "razbatch: unknown option '%s'\n", f.c_str()); return false; }
    }
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    Args a;
    if (!parse(argc, argv, a)) return 1;
    if (a.input.empty() || a.outputDir.empty()) { usage(); return 1; }
    if (a.quiet) raz_log_enabled = 0;

    // ── Refuse the not-yet-implemented options explicitly ───────────────────
    if (!a.preset.empty()) {
        fprintf(stderr,
            "razbatch: --preset takes a pre-flattened blob, not the raw XML.\n"
            "  Convert it once with the JVM tool (which runs the app's own\n"
            "  RawV3ActionReplay.flatten, so the result matches the phone exactly):\n"
            "      java -jar razparams.jar \"%s\" preset.razparams\n"
            "  then re-run with:  --preset-blob preset.razparams\n",
            a.preset.c_str());
        return 4;
    }
    if (a.gpu != "cpu" && a.gpu != "auto") {
        fprintf(stderr, "razbatch: --gpu %s not implemented; only 'cpu' is available.\n", a.gpu.c_str());
        return 4;
    }
    if (a.gpu == "auto") fprintf(stderr, "razbatch: --gpu auto -> using cpu (no GPU backend built yet)\n");
    // (EXIF policy handled after Stage A, where the metadata exists.)

    std::error_code ec;
    if (!fs::exists(a.input)) { fprintf(stderr, "razbatch: input not found: %s\n", a.input.c_str()); return 1; }
    fs::create_directories(a.outputDir, ec);

    const fs::path inPath(a.input);
    const fs::path stem = inPath.stem();
    const fs::path work = fs::path(a.outputDir) / (stem.string() + ".stageA.tif");
    const bool isJpeg  = (a.format == "jpg"  || a.format == "jpeg");
    const bool isPng   = (a.format == "png");
    const bool isTiff  = (a.format == "tiff16" || a.format == "tif");
    if (!isJpeg && !isPng && !isTiff) {
        fprintf(stderr, "razbatch: unknown --format '%s' (expected tiff16, jpg or png)\n",
                a.format.c_str());
        return 1;
    }
    const fs::path outPath = fs::path(a.outputDir) /
        (stem.string() + (isJpeg ? "_RAZ.jpg" : isPng ? "_RAZ.png" : "_RAZ.tif"));

    const auto t0 = std::chrono::steady_clock::now();

    // Diagnostic short-circuit: stock-LibRaw decode, nothing of ours involved.
    if (a.referenceLibRaw) {
        const fs::path refPath = fs::path(a.outputDir) / (stem.string() + "_LIBRAW.jpg");
        fprintf(stderr, "razbatch: --reference-libraw -> %s\n", refPath.string().c_str());
        if (!referenceLibRawDecode(a.input, refPath.string())) return 2;
        fprintf(stderr, "REF %s\n", refPath.string().c_str());
        return 0;
    }

    // ── Stage A ─────────────────────────────────────────────────────────────
    raw_v3::StageAOptions sa;
    // White-balance source. 0=camera as-shot (default), 1=auto-from-image,
    // 2=daylight-neutral (no per-channel scaling). Exposed because it is the
    // cleanest way to tell a WB-source fault apart from a colour-matrix fault:
    // if 'auto' renders neutral but 'camera' is cast, the as-shot multipliers
    // are the problem; if BOTH are cast, the camera->sRGB matrix is.
    if      (a.wb == "camera")   sa.wbSource = 0;
    else if (a.wb == "auto")     sa.wbSource = 1;
    else if (a.wb == "daylight") sa.wbSource = 2;
    else {
        fprintf(stderr, "razbatch: ERROR unknown --wb '%s' (camera|auto|daylight)\n", a.wb.c_str());
        return 2;
    }
    // Diagnostic: pin the AMaZE<->LMMSE blend threshold to isolate the two
    // arms. The blend mask is a sigmoid on local contrast (0=AMaZE, 1=VNG/LMMSE)
    // centred on dualContrastThreshold, normally auto-picked per scene. Forcing
    // it to an extreme pushes almost every pixel to ONE decoder, which is the
    // only way to measure the arms separately — `useDualVng` is a dead variable
    // and the dual path is otherwise selected by data, not by a flag.
    if (a.dualThreshold >= 0.f) {
        sa.dualContrastThreshold = a.dualThreshold;
        sa.dualAutoContrast      = false;   // else the value is overwritten
        fprintf(stderr, "razbatch: dual blend threshold pinned to %.3f (auto OFF)\n",
                a.dualThreshold);
    }
    if (a.wb != "camera") {
        // Warn rather than pretend: Stage A's dual (AMaZE+LMMSE) path builds its
        // own multipliers from cam_mul and ignores wbSource entirely, so this
        // flag currently changes NOTHING for RAW files. Measured: 0/1/2 give
        // byte-identical output. See the note on StageAOptions::wbSource.
        fprintf(stderr,
                "razbatch: WARNING --wb %s (wbSource=%d) has NO EFFECT on RAW files.\n"
                "razbatch:          Stage A's dual demosaic path derives white balance from\n"
                "razbatch:          cam_mul and ignores wbSource. Output will match --wb camera.\n",
                a.wb.c_str(), sa.wbSource);
    }
    // AI Level Reconstruct is driven by the PRESENCE of the model bytes, not a
    // bool: StageAOptions takes hdrModelData / shadowModelData (the raw
    // contents of models/raw_hdr_recovery.bin and raw_shadow_recovery.bin,
    // which the Android app reads from its assets). Empty vector = feature off.
    if (a.aiReconstruct) {
        const fs::path modelDir = fs::path(argv[0]).parent_path() / "models";
        auto slurp = [](const fs::path& p) {
            std::vector<uint8_t> b;
            if (FILE* f = fopen(p.string().c_str(), "rb")) {
                fseek(f, 0, SEEK_END); const long n = ftell(f); fseek(f, 0, SEEK_SET);
                if (n > 0) { b.resize(static_cast<size_t>(n)); (void)fread(b.data(), 1, b.size(), f); }
                fclose(f);
            }
            return b;
        };
        sa.hdrModelData    = slurp(modelDir / "raw_hdr_recovery.bin");
        sa.shadowModelData = slurp(modelDir / "raw_shadow_recovery.bin");
        if (sa.hdrModelData.empty() && sa.shadowModelData.empty())
            fprintf(stderr, "razbatch: --ai-reconstruct requested but no models found in '%s' "
                            "— continuing without it\n", modelDir.string().c_str());
    }
    if (a.lensCorrection) {
        sa.lensfunDbDir = a.lensfunDb.empty()
            ? (fs::path(argv[0]).parent_path() / "lensfun_db").string()
            : a.lensfunDb;
        if (!fs::exists(sa.lensfunDbDir)) {
            fprintf(stderr, "razbatch: lensfun DB not found at '%s' — continuing WITHOUT lens correction\n",
                    sa.lensfunDbDir.c_str());
            sa.lensfunDbDir.clear();
        }
    }

    const raw_v3::StageAMetadata meta = raw_v3::runStageA(a.input, work.string(), sa);
    if (!meta.errorMessage.empty()) {
        fprintf(stderr, "razbatch: Stage A failed: %s\n", meta.errorMessage.c_str());
        fs::remove(work, ec);
        return 2;
    }
    fprintf(stderr, "razbatch: Stage A ok — %s %s, lens='%s'\n",
            meta.cameraMake.c_str(), meta.cameraModel.c_str(), meta.lensModel.c_str());

    // ── Stage C ─────────────────────────────────────────────────────────────
    // Stage C REJECTS a null params pointer ("params missing") — it is not a
    // neutral path. With --preset-blob we pass the flattened preset; otherwise
    // the identity blob (== Kotlin ShaderParams() defaults). An all-zero array
    // would be wrong either way, since ~39 slots default non-zero.
    std::vector<float> blob;
    raw_v3::StageCOptions sc;
    sc.format      = raw_v3::StageCFormat::Tiff16;
    sc.params      = raz::kShaderParamsIdentity;
    sc.paramsCount = raz::kShaderParamsCount;
    // ── EXIF ────────────────────────────────────────────────────────────────
    // LibRaw does NOT hand back a copy-able EXIF APP1 blob — it parses metadata
    // into typed fields, which runStageA surfaces as StageAMetadata. So rather
    // than memcpy an original blob, we re-emit the values as real TIFF tags.
    //   KeepAll             -> everything, including Artist/Copyright
    //   StripSensitive      -> drop Artist/Copyright (author-identifying)
    //   NoneExceptSoftware  -> no EXIF at all (no Software tag support yet)
    raw_v3::StageCExif exif;
    bool wantExif = (a.exif != "NoneExceptSoftware");
    if (wantExif) {
        exif.make             = meta.cameraMake;
        exif.model            = meta.cameraModel;
        exif.dateTime         = meta.dateTimeOriginal;
        exif.dateTimeOriginal = meta.dateTimeOriginal;
        exif.lensModel        = meta.lensModel;
        exif.iso              = meta.iso;
        exif.exposureTime     = meta.shutterSpeed;
        exif.fNumber          = meta.aperture;
        exif.focalLength      = meta.focalLength;
        // NOTE: --exif StripSensitive currently produces the SAME output as
        // KeepAll. StageAMetadata exposes no author-identifying fields at all
        // (no Artist, Copyright, body/lens serial, GPS or MakerNote), so there
        // is nothing for it to strip. Said out loud rather than silently
        // implying a privacy filter that does not exist. Add the fields to
        // StageAMetadata first if a real distinction is needed.
        if (a.exif == "StripSensitive") {
            fprintf(stderr, "razbatch: note — StripSensitive == KeepAll here; "
                            "no sensitive fields are extracted in the first place.\n");
        }
        fprintf(stderr,
                "razbatch: EXIF -> make='%s' model='%s' lens='%s' iso=%d "
                "exp=%.4fs f/%.1f focal=%.1fmm date='%s'\n",
                exif.make.c_str(), exif.model.c_str(), exif.lensModel.c_str(),
                exif.iso, exif.exposureTime, exif.fNumber, exif.focalLength,
                exif.dateTimeOriginal.c_str());
    } else {
        fprintf(stderr, "razbatch: --exif NoneExceptSoftware — writing no EXIF\n");
    }

    // ICC: Stage C's BigTIFF writer already emits TAG_ICC_PROFILE (34675) when
    // given bytes — no new dependency needed. The profile is the app's own
    // sRGB v2, generated from RawV3IccEmbed so it matches the phone exactly.
    if (wantExif) sc.exif = &exif;
    if (a.embedIcc) {
        sc.iccProfile     = raz::kSrgbIccProfile;
        sc.iccProfileSize = raz::kSrgbIccProfileSize;
        fprintf(stderr, "razbatch: embedding sRGB v2 ICC profile (%zu bytes)\n",
                raz::kSrgbIccProfileSize);
    }
    if (!a.presetBlob.empty()) {
        if (!loadPresetBlob(a.presetBlob, blob)) return 1;
        sc.params      = blob.data();
        sc.paramsCount = static_cast<int>(blob.size());
        int nz = 0;
        for (float v : blob) if (v != 0.f) ++nz;
        fprintf(stderr, "razbatch: preset blob loaded (%d slots, %d non-zero)\n",
                static_cast<int>(blob.size()), nz);
    }

    // ── 3D LUT ───────────────────────────────────────────────────────────────
    // Must outlive runStageC: StageCOptions holds a BORROWED pointer into
    // cube.rgb, so this has to stay in scope until the export completes.
    raw_v3::CubeLut cube;
    if (!a.lut.empty()) {
        cube = raw_v3::parseCubeFile(a.lut);
        if (cube.size <= 0 || cube.rgb.size() != size_t(cube.size) * cube.size * cube.size * 3) {
            fprintf(stderr, "razbatch: ERROR bad LUT '%s' (size=%d, %zu floats)\n",
                    a.lut.c_str(), cube.size, cube.rgb.size());
            return 1;                       // fail loudly — a silently skipped
        }                                   // LUT is a wrong-pixels bug
        sc.lutData = cube.rgb.data();
        sc.lutSize = cube.size;
        for (int i = 0; i < 3; ++i) {
            sc.lutDomainMin[i] = cube.domainMin[i];
            sc.lutDomainMax[i] = cube.domainMax[i];
        }

        // Handing Stage C lutData is NOT enough — the kernel gates the LUT on
        // ShaderParams slots [29] lutEnabled and [31] lutIntensity, both 0 in
        // the identity blob. Without this the LUT parses, logs, and changes
        // NOTHING (verified: a channel-swap LUT produced a byte-identical
        // file). Slots are documented in ShaderParams.kt.
        if (blob.empty()) {                       // no --preset-blob: copy identity
            blob.assign(sc.params, sc.params + sc.paramsCount);
        }
        constexpr int kSlotLutEnabled   = 29;
        constexpr int kSlotLutIntensity = 31;
        if (int(blob.size()) > kSlotLutIntensity) {
            blob[kSlotLutEnabled] = 1.f;
            // Respect an explicit intensity from a preset; only default it when
            // the preset left the LUT off (intensity 0 would be a no-op LUT).
            if (blob[kSlotLutIntensity] <= 0.f) blob[kSlotLutIntensity] = 1.f;
            sc.params      = blob.data();
            sc.paramsCount = static_cast<int>(blob.size());
        }
        fprintf(stderr, "razbatch: LUT '%s' %d^3 domain[%.3f..%.3f]\n",
                a.lut.c_str(), cube.size, cube.domainMin[0], cube.domainMax[0]);
    } else if (!blob.empty() && blob.size() > 31 && blob[29] > 0.f) {
        // The preset ENABLES a LUT but no --lut was given, so Stage C renders
        // with lut=0x0x0 (off) and exits 0 — silently dropping the preset's
        // entire look. Presets reference their cube by an ON-DEVICE path
        // (<lutCubeUri>/data/user/0/.../lut_cache/foo.cube</lutCubeUri>) which
        // cannot exist on Windows, and the flattened blob carries only the
        // enable/intensity slots, not the filename. So this is the normal case
        // for any film-emulation preset, and it MUST be loud: a batch of 200
        // photos silently missing its look is the worst possible outcome.
        fprintf(stderr,
                "razbatch: WARNING preset enables a 3D LUT (intensity %.3f) but no "
                "--lut was supplied.\n"
                "razbatch:          The LUT will NOT be applied — output will miss the "
                "preset's colour look.\n"
                "razbatch:          Pass --lut <file.cube> with the cube the preset was "
                "authored against.\n",
                blob[31]);
    }

    raw_v3::StageCResult r{};
    if (isTiff) {
        // 16-bit path: Stage C writes the file itself (and embeds ICC/EXIF via
        // the BigTIFF writer).
        r = raw_v3::runStageC(work.string(), outPath.string(), sc);
    } else {
        // 8-bit path (jpg / png): render to RGBA8 — the same kernel entry the
        // Android JPEG path uses — then encode with WIC. Stage C writes no file
        // here, so ICC/EXIF are handed to the encoder instead of the TIFF
        // writer. Pixel values come from the identical kernel either way.
        // runStageCToRGBA8 needs the destination sized up front, and neither it
        // nor StageAMetadata reports the dimensions beforehand (a null-buffer
        // "probe" call just fails). Read them from the Stage A TIFF header,
        // which is the authoritative source for what Stage C will render.
        uint32_t w = 0, h = 0;
        if (raw_v3::StageATiffReader* rd = raw_v3::openStageATiff(work.string())) {
            const raw_v3::StageATiffHeader& hd = raw_v3::getStageATiffHeader(rd);
            w = hd.width; h = hd.height;
            raw_v3::closeStageATiff(rd);
        }
        if (!w || !h) {
            fprintf(stderr, "razbatch: could not read Stage A dimensions for 8-bit encode\n");
            fs::remove(work, ec);
            return 3;
        }
        const uint32_t stride = w * 4;
        std::vector<uint8_t> rgba(size_t(stride) * h);
        r = raw_v3::runStageCToRGBA8(work.string(), sc, rgba.data(), stride);
        if (r.success) {
            raz::EncodeMeta em;
            if (a.embedIcc) { em.icc = raz::kSrgbIccProfile; em.iccSize = raz::kSrgbIccProfileSize; }
            if (wantExif) {
                em.make = exif.make; em.model = exif.model;
                em.dateTime = exif.dateTime; em.dateTimeOriginal = exif.dateTimeOriginal;
                em.lensModel = exif.lensModel; em.iso = exif.iso;
                em.exposureTime = exif.exposureTime; em.fNumber = exif.fNumber;
                em.focalLength = exif.focalLength;
            }
            if (isPng && wantExif)
                fprintf(stderr, "razbatch: note — PNG carries ICC but not EXIF "
                                "(WIC's PNG container has no APP1/IFD); use --format jpg or tiff16 "
                                "if you need embedded EXIF.\n");
            std::string encErr;
            if (!raz::encodeWic(outPath.string(), rgba.data(), w, h, stride,
                                isJpeg ? raz::WicFormat::Jpeg : raz::WicFormat::Png,
                                a.quality, em, &encErr)) {
                fprintf(stderr, "razbatch: %s encode failed: %s\n",
                        isJpeg ? "JPEG" : "PNG", encErr.c_str());
                fs::remove(work, ec);
                return 3;
            }
        }
    }
    fs::remove(work, ec);   // drop the Stage A intermediate

    if (!r.success) {
        fprintf(stderr, "razbatch: Stage C failed: %s\n", r.error.c_str());
        return 3;
    }

    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    // stdout = the machine-readable result line the GUI parses; logs go to stderr.
    printf("OK %s %ux%u %lldms\n", outPath.string().c_str(), r.outWidth, r.outHeight,
           static_cast<long long>(ms));
    return 0;
}
