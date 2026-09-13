/*
 * harness_main — P0 console harness for the desktop GUI facade.
 *
 * No ImGui, no window. It exercises the exact call path the GUI will drive:
 *   open() → renderProxy() (real apply_macro on the downscaled cache) → save
 *   the proxy PNG, then exportTiff16()/exportJpeg() at full res.
 *
 * Purpose: prove the load-bearing seam compiles and runs under the razbatch
 * toolchain (clang-cl + RAZ_NO_EGL + F16C) BEFORE any UI code exists.
 *
 * Usage:
 *   razstudio_harness <input.CR2|.tif> [--out <dir>] [--ev <stops>]
 *                     [--lut <file.cube>] [--lut-strength <0..1>]
 *                     [--proxy <longSidePx>] [--lensfun-db <dir>]
 */
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "raz_engine.h"
#include "desktop/shader_params_identity.h"
#include "desktop/wic_encoder.h"

using razgui::RazEngine;
using razgui::LutData;

// The compat android/log.h shim (compat/android/log.h) routes __android_log_print
// through this global gate. razbatch_main.cpp owns the definition for the CLI;
// this harness excludes that file, so it must provide its own. 1 = logs on.
extern "C" { int raz_log_enabled = 1; }

static const char* arg(int argc, char** argv, const char* key, const char* dflt) {
    for (int i = 1; i < argc - 1; ++i)
        if (std::strcmp(argv[i], key) == 0) return argv[i + 1];
    return dflt;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr,
            "usage: %s <input> [--out dir] [--ev stops] [--lut f.cube]\n"
            "          [--lut-strength 0..1] [--proxy longSide] [--lensfun-db dir]\n", argv[0]);
        return 2;
    }
    const std::string input   = argv[1];
    const std::string outDir  = arg(argc, argv, "--out", ".");
    const float ev            = static_cast<float>(std::atof(arg(argc, argv, "--ev", "0")));
    const std::string lutPath = arg(argc, argv, "--lut", "");
    const float lutStrength   = static_cast<float>(std::atof(arg(argc, argv, "--lut-strength", "1")));
    const int  proxyLong      = std::atoi(arg(argc, argv, "--proxy", "1600"));
    const float clarity       = static_cast<float>(std::atof(arg(argc, argv, "--clarity", "0")));
    const float clarityLift   = static_cast<float>(std::atof(arg(argc, argv, "--clarity-lift", "0")));
    const float ambiance      = static_cast<float>(std::atof(arg(argc, argv, "--ambiance", "0")));
    const float bloom         = static_cast<float>(std::atof(arg(argc, argv, "--bloom", "0")));
    const std::string lensDb  = arg(argc, argv, "--lensfun-db", "");

    // ── Optional 3D LUT ────────────────────────────────────────────────────
    LutData lut; std::string err;
    const bool haveLut = !lutPath.empty();
    if (haveLut && !razgui::loadCubeLut(lutPath, lut, err)) {
        std::fprintf(stderr, "LUT load failed: %s\n", err.c_str());
        return 1;
    }

    // ── Decode + proxy build ────────────────────────────────────────────────
    raw_v3::StageAOptions sa;
    sa.demosaicAlgorithm = -3;         // dual AMaZE+LMMSE, as razbatch's default
    if (!lensDb.empty()) sa.lensfunDbDir = lensDb;

    RazEngine eng;
    auto op = eng.open(input, proxyLong, sa, outDir);
    if (!op.ok) { std::fprintf(stderr, "open failed: %s\n", op.error.c_str()); return 1; }
    std::printf("opened %s  src=%ux%u  proxy=%ux%u  cam=%s %s  iso=%d\n",
                input.c_str(), eng.srcWidth(), eng.srcHeight(),
                eng.proxyWidth(), eng.proxyHeight(),
                op.meta.cameraMake.c_str(), op.meta.cameraModel.c_str(), op.meta.iso);

    // ── Build the 410-float blob from identity + our two knobs ──────────────
    std::vector<float> params(raz::kShaderParamsIdentity,
                              raz::kShaderParamsIdentity + raz::kShaderParamsCount);
    params[0] = ev;                    // slot [0] = exposure (EV stops)
    params[151] = clarity;             // slot [151] = clarity (local contrast)
    params[408] = clarityLift;         // slot [408] = clarity midtone-pop lift
    params[208] = ambiance;            // slot [208] = ambiance (Snapseed-style)
    params[209] = bloom;               // slot [209] = Orton/bloom (Glamour Glow screen)
    if (haveLut) {
        params[29] = 1.0f;             // [29] lutEnabled — kernel gates on this
        params[31] = lutStrength;      // [31] lutIntensity
    }
    const LutData* lp = haveLut ? &lut : nullptr;

    // ── Proxy preview render (the per-tick call) ────────────────────────────
    std::vector<uint8_t> rgba; uint32_t w = 0, h = 0; int64_t ms = 0;
    if (!eng.renderProxy(params.data(), (int)params.size(), lp, rgba, w, h, ms, err)) {
        std::fprintf(stderr, "renderProxy failed: %s\n", err.c_str());
        return 1;
    }
    std::printf("proxy rendered %ux%u in %lld ms\n", w, h, (long long)ms);

    const std::string previewPng = outDir + "/harness_preview.png";
    raz::EncodeMeta pm{};
    if (!raz::encodeWic(previewPng, rgba.data(), w, h, w * 4, raz::WicFormat::Png, 100, pm, &err)) {
        std::fprintf(stderr, "preview PNG write failed: %s\n", err.c_str());
        return 1;
    }
    std::printf("wrote %s\n", previewPng.c_str());

    // ── Full-res exports (truth) ────────────────────────────────────────────
    const std::string jpg = outDir + "/harness_export.jpg";
    if (!eng.exportJpegOrPng(params.data(), (int)params.size(), lp, jpg, /*png=*/false, 92, err)) {
        std::fprintf(stderr, "JPEG export failed: %s\n", err.c_str());
        return 1;
    }
    std::printf("wrote %s (full-res %ux%u)\n", jpg.c_str(), eng.srcWidth(), eng.srcHeight());

    const std::string tif = outDir + "/harness_export.tif";
    if (!eng.exportTiff16(params.data(), (int)params.size(), lp, tif, err)) {
        std::fprintf(stderr, "TIFF16 export failed: %s\n", err.c_str());
        return 1;
    }
    std::printf("wrote %s (16-bit)\n", tif.c_str());

    std::printf("OK\n");
    return 0;
}
