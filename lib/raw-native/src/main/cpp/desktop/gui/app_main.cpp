/*
 * app_main — RAZStudio Desktop: full adjustment panel matching the Android RAWEditor.
 *
 * Tab structure mirrors RawAdjustmentPanel.kt:
 *   Tone     — Exposure, Contrast, Highlights, Shadows, Whites, Blacks, Dehaze,
 *              WB (K delta), Tint; Tonemap sub (TM Exp/Hi/Sh); Tonal zone WB
 *   Color    — Saturation, Vibrance, Color Density + HSL 6-anchor (R/O/Y/G/A/B)
 *   Detail   — Sharpness, Smart Sharpness, Texture;
 *              Noise Reduction (Luma/Color/Blue/Red, Smooth Background);
 *              Film Grain (Amount/Size/Wash)
 *   FX       — Bloom (Strength/Radius/Shape); Bokeh blur; Mist (Strength/Warmth);
 *              Vintage (Strength/Fade); Film (Push/Pull, Film Rolloff); Ambiance
 *   Vignette — Amount, Intensity, Feather, Center X/Y
 *   Gradient — Angle + 4 sides (Top/Bottom/Left/Right): Intensity/Length/Feather per side
 *   LUT      — .cube file picker, Strength, Highlight Vibrancy
 *   LUT ADJ  — CLAHE (on/off, Shadows/Highlights boost); Hi/Sh Temp/Tint;
 *              Skin Tone (Warm/Smooth/Luma); Push/Pull
 *   Export   — TIFF-16, JPEG, PNG
 *
 * All parameters write into the 410-float ShaderParams blob (kShaderParamsIdentity base).
 * Slot indices match ShaderParams.kt exactly. Ranges match the Android slider definitions.
 *
 * Key slots used:
 *   [0] exposure        [-4..4 EV]
 *   [1] contrast        [-1..1]
 *   [2] highlights      [-1..1]
 *   [3] shadows         [-1..1]
 *   [4] whites          [-1..1]
 *   [5] blacks          [-1..1]
 *   [6] saturation      [-1..1]    (Android: -100..100 / 100)
 *   [7] vibrance        [-1..1]    (Android: -100..100 / 100)
 *   [8] whiteBalance    float      (Android: Kelvin delta -3500..6500, passed directly)
 *   [9] tint            float      (Android: -200..200, passed directly)
 *  [10..27] hsl         (R/O/Y/G/A/B × H/S/L) — Android: H -180..180, S/L -100..100,
 *                       all divided by 100 before storing in params
 *  [28] ditherStrength  1 = on (proxy), 0 = off (16-bit export)
 *  [29] lutEnabled      0/1
 *  [31] lutIntensity    0..1
 *  [57] lightTabOpacity [58] colorTabOpacity  [60] dehaze [-1..1]
 *  [61..67] vignette block
 *  [68] gradAngle; [69..128] gradTop/Bottom/Left/Right (15 floats each)
 *  [141..143] tonemapExp/Hi/Sh
 *  [144..146] claheEnabled/ShadowsBoost/HighlightsBoost
 *  [147..148] luminanceNR / colorNR
 *  [149..156] detail ops
 *  [178] detailSmoothBackground
 *  [179] bokehBlur
 *  [200] lutHighlightVibrancy
 *  [201..204] Hi/Sh Temp/Tint
 *  [205..207] bloomRadius / bloomShape / filmRolloff
 *  [208] ambiance
 *  [209] ortonStrength  (bloom/Orton blend strength; Android bloomUiToMacro: UI 0..100 → 0..10)
 *  [233] blueNR  [234] redNR
 *  [343] colorDensity
 *  [344] skintoneWarm   [345] skintoneSmooth  [346] skintoneLuma
 *  [349] pushPull       [-3..3]
 *  [365] fxMist  [366] fxMistWarmth
 *  [369] fxVintageStrength  [370] fxVintageFade
 */
#include <windows.h>
#include <commdlg.h>
#include <GL/gl.h>

#ifndef GL_CLAMP_TO_EDGE
#define GL_CLAMP_TO_EDGE 0x812F
#endif

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <cmath>

#include "imgui.h"
#include "backends/imgui_impl_glfw.h"
#include "backends/imgui_impl_opengl2.h"
#include <GLFW/glfw3.h>

#include "raz_engine.h"
#include "desktop/shader_params_identity.h"
#include "lr_preset_converter.h"
#include "ai_macro_generators.h"

extern "C" { int raz_log_enabled = 0; }

// ── AI helpers ──────────────────────────────────────────────────────────────
static void applyAiAdjustments(std::vector<float>& params,
                               const std::vector<std::pair<int, float>>& adjustments) {
    for (auto& [slot, value] : adjustments) {
        if (slot >= 0 && slot < (int)params.size()) params[slot] += value;
    }
}

// Fraction of pixels at or above 90% brightness on all 3 channels — a cheap
// stand-in for "blown highlights" that only needs the already-rendered
// proxy RGBA8 buffer (no extra kernel pass).
static float detectBlownFraction(const uint8_t* rgba, int width, int height) {
    if (!rgba || width <= 0 || height <= 0) return 0.f;
    const uint8_t thresh = 230;  // ~90% of 255
    int64_t blown = 0;
    int64_t total = (int64_t)width * height;
    for (int64_t i = 0; i < total; ++i) {
        const uint8_t* p = rgba + i * 4;
        if (p[0] >= thresh && p[1] >= thresh && p[2] >= thresh) blown++;
    }
    return total > 0 ? (float)((double)blown / (double)total) : 0.f;
}

using razgui::RazEngine;
using razgui::LutData;

// ── GL texture ───────────────────────────────────────────────────────────────
struct GlTexture {
    GLuint id = 0; int w = 0, h = 0;
    void upload(const uint8_t* rgba, int width, int height) {
        if (!id) glGenTextures(1, &id);
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        w = width; h = height;
    }
    void destroy() { if (id) { glDeleteTextures(1, &id); id = 0; } }
};

// ── File dialogs ─────────────────────────────────────────────────────────────
static std::string openDialog(const char* filter) {
    char path[MAX_PATH] = {};
    OPENFILENAMEA ofn = {};
    ofn.lStructSize = sizeof(ofn); ofn.lpstrFilter = filter;
    ofn.lpstrFile = path; ofn.nMaxFile = MAX_PATH;
    ofn.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;
    return GetOpenFileNameA(&ofn) ? std::string(path) : std::string();
}
static std::string saveDialog(const char* filter, const char* defExt) {
    char path[MAX_PATH] = {};
    OPENFILENAMEA ofn = {};
    ofn.lStructSize = sizeof(ofn); ofn.lpstrFilter = filter;
    ofn.lpstrFile = path; ofn.nMaxFile = MAX_PATH; ofn.lpstrDefExt = defExt;
    ofn.Flags = OFN_OVERWRITEPROMPT | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;
    return GetSaveFileNameA(&ofn) ? std::string(path) : std::string();
}

// ── Slider helpers ────────────────────────────────────────────────────────────
// Writes directly into the params blob. Returns true every frame while editing (live update).
static bool SlotSlider(const char* label, float* params, int slot,
                       float vmin, float vmax, const char* fmt = "%.2f") {
    ImGui::SliderFloat(label, &params[slot], vmin, vmax, fmt);
    return ImGui::IsItemEdited();
}
// Checkbox for bool-encoded float slot. Returns true on change (immediate).
static bool SlotCheck(const char* label, float* params, int slot) {
    bool v = params[slot] > 0.5f;
    if (ImGui::Checkbox(label, &v)) { params[slot] = v ? 1.f : 0.f; return true; }
    return false;
}

// ── LUT library browser ───────────────────────────────────────────────────────
// Scans engine\luts\ (beside the exe) for category subdirs matching the APK
// asset layout: luts/<Category>/<name>.cube
struct LutEntry { std::string name; std::string path; };
struct LutCategory { std::string name; std::vector<LutEntry> entries; };

static std::string exeDir() {
    char buf[MAX_PATH] = {};
    GetModuleFileNameA(nullptr, buf, MAX_PATH);
    std::string p(buf);
    size_t s = p.find_last_of("/\\");
    return (s == std::string::npos) ? std::string(".") : p.substr(0, s);
}

static std::vector<LutCategory> scanLutLibrary() {
    std::vector<LutCategory> cats;
    std::string lutRoot = exeDir() + "\\luts";

    WIN32_FIND_DATAA fd;
    // Enumerate category subdirectories
    HANDLE hCat = FindFirstFileA((lutRoot + "\\*").c_str(), &fd);
    if (hCat == INVALID_HANDLE_VALUE) return cats;
    do {
        if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
        if (fd.cFileName[0] == '.') continue;
        LutCategory cat;
        cat.name = fd.cFileName;
        std::string catPath = lutRoot + "\\" + fd.cFileName;
        // Enumerate .cube files in this category
        HANDLE hF = FindFirstFileA((catPath + "\\*.cube").c_str(), &fd);
        if (hF != INVALID_HANDLE_VALUE) {
            do {
                if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
                LutEntry e;
                e.name = fd.cFileName;
                // Strip .cube suffix for display
                if (e.name.size() > 5)
                    e.name = e.name.substr(0, e.name.size() - 5);
                e.path = catPath + "\\" + fd.cFileName;
                cat.entries.push_back(e);
            } while (FindNextFileA(hF, &fd));
            FindClose(hF);
        }
        if (!cat.entries.empty()) cats.push_back(cat);
    } while (FindNextFileA(hCat, &fd));
    FindClose(hCat);

    // Also scan root-level .cube files (identity_17, warm_sepia, etc.)
    HANDLE hR = FindFirstFileA((lutRoot + "\\*.cube").c_str(), &fd);
    if (hR != INVALID_HANDLE_VALUE) {
        LutCategory misc; misc.name = "(misc)";
        do {
            if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
            LutEntry e;
            e.name = fd.cFileName;
            if (e.name.size() > 5) e.name = e.name.substr(0, e.name.size() - 5);
            e.path = lutRoot + "\\" + fd.cFileName;
            misc.entries.push_back(e);
        } while (FindNextFileA(hR, &fd));
        FindClose(hR);
        if (!misc.entries.empty()) cats.insert(cats.begin(), misc);
    }
    return cats;
}

// ── App state ─────────────────────────────────────────────────────────────────
struct App {
    RazEngine engine;
    LutData lut;
    bool haveLut = false;
    std::string lutName = "(none)";

    // LUT library (loaded once on first open of the LUT tab)
    std::vector<LutCategory> lutLib;
    bool lutLibLoaded = false;
    int  lutLibCat   = 0;   // selected category index in the browser
    char lutSearch[128] = {};  // filter textbox

    // 410-float ShaderParams blob
    std::vector<float> params{raz::kShaderParamsIdentity,
                              raz::kShaderParamsIdentity + raz::kShaderParamsCount};

    GlTexture texAfter, texBefore;
    bool showBefore = false, haveBefore = false;
    float zoom = 0.f;
    ImVec2 pan{0, 0};

    int64_t lastMs = 0;
    std::string status = "Open a RAW or TIFF to begin.";
    bool imageOpen = false;
    std::string currentImagePath;  // track current image for re-open on settings change

    // AI / recovery features
    // Highlight recovery mode: 0=Off (hard clip, no color cast, no recovery),
    // 1=Blend (LibRaw highlight=2 — recovers detail but can leave a magenta/
    // pink cast in blown clouds/hotspots), 2=Safe (blend + post-demosaic
    // chroma desaturation toward neutral in near-clipped zones — recovers
    // the same detail without the color cast). Safe is the default since
    // it's strictly better than Blend for the cost of one desaturate pass.
    int highlightRecoveryMode = 2;
    bool aiColorEnhanceEnabled = false;    // AI Color Enhance toggle in Color tab
    float dceScore = -1.f;                 // Zero-DCE lightness score (cached for display); -1 = not probed / unavailable
    bool aiProbeReady = false;             // set once at startup from engine.aiProbeReady()

    // AI Expose: [-] undoes the currently-applied adjustment, the center
    // button (re)probes and applies fresh (undoing any prior application
    // first, so repeated clicks never stack), [+] re-applies the last
    // computed adjustment without re-probing (redo after an undo).
    std::vector<std::pair<int, float>> aiExposeApplied;       // currently baked into a.params, or empty
    std::vector<std::pair<int, float>> aiExposeLastComputed;  // most recent probe result, for redo

    // Cache of the LAST proxy render, so AI features can probe the same
    // pixels the user is looking at without triggering a second kernel pass.
    std::vector<uint8_t> lastProxyRgba;
    uint32_t lastProxyW = 0, lastProxyH = 0;

    // AI Color Enhance is a persistent toggle (unlike the one-shot AI Expose
    // button), so unchecking it must undo exactly what checking it applied.
    std::vector<std::pair<int, float>> aiColorEnhanceApplied;

    // Lens correction / format-check dialog, shown between "Open image..."
    // and the actual doOpen() call. Mirrors the Android WorkspaceSelectorSheet:
    // EXIF is probed cheaply (no decode) to auto-match a Lensfun profile;
    // camera/lens model text is editable and wins over the auto match.
    bool lensCorrectionEnabled = true;   // default matches Android
    char lensCameraBuf[128] = {};
    char lensLensBuf[128] = {};
    float lensFocalOverrideMm = 0.f;
    char lensFocalBuf[16] = "0";
    std::string pendingOpenPath;         // file picked, awaiting the dialog's Open/Cancel
    razgui::LensProbeResult lensProbe;
    bool lensListsLoaded = false;
    std::vector<razgui::LensfunDbEntry> lensCameraList, lensLensList;

    int jpegQuality = 92;
    char jpegQualityBuf[8] = "92";
};

static void syncLut(App& a) {
    a.params[29] = a.haveLut ? 1.f : 0.f;
}

static void rerender(App& a) {
    if (!a.imageOpen) return;
    syncLut(a);
    std::vector<uint8_t> rgba; uint32_t w = 0, h = 0; int64_t ms = 0; std::string err;
    if (!a.engine.renderProxy(a.params.data(), (int)a.params.size(),
                              a.haveLut ? &a.lut : nullptr, rgba, w, h, ms, err)) {
        a.status = "render failed: " + err; return;
    }
    a.texAfter.upload(rgba.data(), (int)w, (int)h);
    a.lastMs = ms;
    a.status = "proxy " + std::to_string(w) + "x" + std::to_string(h) +
               "  " + std::to_string(ms) + " ms";
    a.lastProxyRgba = std::move(rgba);
    a.lastProxyW = w; a.lastProxyH = h;
}

// Probes the LAST rendered proxy buffer (rerender() must have run at least
// once) and bakes the resulting adjustments into a.params. Both AI features
// share this probe — only the macro generator called on the score differs.
static bool runAiProbe(App& a, float& outScore, float& outBlowFraction) {
    if (a.lastProxyRgba.empty() || a.lastProxyW == 0 || a.lastProxyH == 0) {
        a.status = "AI: no rendered preview to probe yet";
        return false;
    }
    outScore = a.engine.probeImage(a.lastProxyRgba.data(), (int)a.lastProxyW, (int)a.lastProxyH);
    if (outScore < 0.f) {
        a.status = "AI: model unavailable (ONNX Runtime not loaded — see AI_FEATURES_INTEGRATION.md)";
        return false;
    }
    outBlowFraction = detectBlownFraction(a.lastProxyRgba.data(), (int)a.lastProxyW, (int)a.lastProxyH);
    a.dceScore = outScore;
    return true;
}

static void renderBefore(App& a) {
    if (!a.imageOpen) return;
    std::vector<float> id(raz::kShaderParamsIdentity,
                          raz::kShaderParamsIdentity + raz::kShaderParamsCount);
    std::vector<uint8_t> rgba; uint32_t w = 0, h = 0; int64_t ms = 0; std::string err;
    if (a.engine.renderProxy(id.data(), (int)id.size(), nullptr, rgba, w, h, ms, err)) {
        a.texBefore.upload(rgba.data(), (int)w, (int)h);
        a.haveBefore = true;
    }
}

static void doOpen(App& a, const std::string& path) {
    if (path.empty()) return;
    raw_v3::StageAOptions sa;
    sa.demosaicAlgorithm = -3;
    switch (a.highlightRecoveryMode) {
        case 0:  sa.highlightMode = 0; sa.highlightDesaturateStrength = 0.f; break;  // Off
        case 1:  sa.highlightMode = 2; sa.highlightDesaturateStrength = 0.f; break;  // Blend
        default: sa.highlightMode = 2; sa.highlightDesaturateStrength = 1.f; break;  // Safe
    }
    if (a.lensCorrectionEnabled) {
        sa.lensfunDbDir = exeDir() + "\\lensfun_db";
        // Non-empty here always wins over Stage A's own EXIF read (see
        // stage_a.cpp's ovCam/ovLens handling) — harmless when the dialog's
        // fields still hold the auto-detected EXIF values verbatim, since
        // that resolves to the identical match Stage A would have found on
        // its own.
        sa.lensfunCameraId = a.lensCameraBuf;
        sa.lensfunLensId   = a.lensLensBuf;
        sa.lensfunFocalOverrideMm = a.lensFocalOverrideMm;
    }
    auto op = a.engine.open(path, 1600, sa);
    if (!op.ok) { a.status = "open failed: " + op.error; a.imageOpen = false; return; }
    a.imageOpen = true;
    a.currentImagePath = path;
    a.zoom = 0.f; a.pan = ImVec2(0, 0);
    a.status = std::string(op.meta.cameraMake) + " " + op.meta.cameraModel +
               "  " + std::to_string(a.engine.srcWidth()) + "x" + std::to_string(a.engine.srcHeight());
    rerender(a);
    renderBefore(a);
}

// ── Gradient side helper ──────────────────────────────────────────────────────
// Each side occupies 15 floats starting at baseSlot (see ShaderParams.kt layout):
//   +0 intensity1  +1 length1   +2 feather1
//   +7 enable2     +8 intensity2 +9 length2  +10 feather2
// (tint RGB/lum at +3..+6 and +11..+14 omitted — no color picker in ImGui for now)
static bool drawGradientSide(const char* sideId, float* params, int baseSlot) {
    bool dirty = false;
    char lbl[64];

    std::snprintf(lbl, 64, "Intensity##gi%s", sideId);
    dirty |= SlotSlider(lbl, params, baseSlot + 0, 0.f, 1.f, "%.2f");
    std::snprintf(lbl, 64, "Length##gl%s", sideId);
    dirty |= SlotSlider(lbl, params, baseSlot + 1, 0.f, 1.f, "%.2f");
    std::snprintf(lbl, 64, "Feather##gf%s", sideId);
    dirty |= SlotSlider(lbl, params, baseSlot + 2, 0.f, 1.f, "%.2f");

    ImGui::Spacing();
    std::snprintf(lbl, 64, "2nd gradient##ge2%s", sideId);
    dirty |= SlotCheck(lbl, params, baseSlot + 7);
    if (params[baseSlot + 7] > 0.5f) {
        std::snprintf(lbl, 64, "Intensity 2##gi2%s", sideId);
        dirty |= SlotSlider(lbl, params, baseSlot + 8, 0.f, 1.f, "%.2f");
        std::snprintf(lbl, 64, "Length 2##gl2%s", sideId);
        dirty |= SlotSlider(lbl, params, baseSlot + 9, 0.f, 1.f, "%.2f");
        std::snprintf(lbl, 64, "Feather 2##gf2%s", sideId);
        dirty |= SlotSlider(lbl, params, baseSlot + 10, 0.f, 1.f, "%.2f");
    }
    return dirty;
}

// ── Main adjustment panel ─────────────────────────────────────────────────────
static bool drawAdjustmentPanel(App& a) {
    bool dirty = false;

    if (ImGui::Button("Open image...")) {
        std::string p = openDialog(
            "Images\0*.cr2;*.cr3;*.nef;*.arw;*.dng;*.raf;*.rw2;*.orf;*.pef;"
            "*.tif;*.tiff;*.jpg;*.jpeg;*.png\0All\0*.*\0");
        if (!p.empty()) {
            // Cheap EXIF-only probe (no decode) drives the lens/format check
            // dialog below; the real doOpen() happens only when the user
            // confirms it, so it always sees whatever the dialog resolved.
            a.pendingOpenPath = p;
            std::string dbDir = exeDir() + "\\lensfun_db";
            a.lensProbe = razgui::probeLensProfile(p, dbDir);
            std::snprintf(a.lensCameraBuf, sizeof(a.lensCameraBuf), "%s",
                          a.lensProbe.cameraModel.c_str());
            std::snprintf(a.lensLensBuf, sizeof(a.lensLensBuf), "%s",
                          a.lensProbe.lensModel.c_str());
            a.lensFocalOverrideMm = 0.f;
            std::snprintf(a.lensFocalBuf, sizeof(a.lensFocalBuf), "0");
            if (!a.lensListsLoaded) {
                a.lensCameraList = razgui::lensfunCameraList(dbDir);
                a.lensLensList   = razgui::lensfunLensList(dbDir);
                a.lensListsLoaded = true;
            }
            ImGui::OpenPopup("Lens & Format Check");
        }
    }

    // ── Lens / format check dialog ────────────────────────────────────────
    // Blocks the actual open until the user confirms — mirrors Android's
    // WorkspaceSelectorSheet: auto-detected camera/lens/focal/aperture shown
    // up front, both model fields editable with a searchable DB browser,
    // lens correction can be disabled entirely for this import.
    if (ImGui::BeginPopupModal("Lens & Format Check", nullptr, ImGuiWindowFlags_AlwaysAutoResize)) {
        size_t fs = a.pendingOpenPath.find_last_of("/\\");
        std::string fname = (fs == std::string::npos) ? a.pendingOpenPath : a.pendingOpenPath.substr(fs + 1);
        ImGui::Text("File: %s", fname.c_str());

        if (!a.lensProbe.ok) {
            ImGui::TextColored(ImVec4(0.9f, 0.5f, 0.2f, 1.f),
                                "Could not read metadata: %s", a.lensProbe.error.c_str());
            ImGui::TextDisabled("You can still open the file without lens correction.");
        } else {
            ImGui::Text("Format: %s (%s)", a.lensProbe.formatExt.c_str(),
                        a.lensProbe.isRaw ? "RAW" : "non-RAW");
            ImGui::Separator();
            ImGui::TextDisabled("Detected from EXIF");
            ImGui::Text("Camera: %s %s", a.lensProbe.cameraMake.c_str(), a.lensProbe.cameraModel.c_str());
            ImGui::Text("Lens: %s %s", a.lensProbe.lensMake.c_str(), a.lensProbe.lensModel.c_str());
            if (a.lensProbe.focalMm > 0.f) ImGui::Text("Focal length: %.0f mm", a.lensProbe.focalMm);
            else ImGui::TextDisabled("Focal length: not reported (manual/adapted lens)");
            if (a.lensProbe.aperture > 0.f) ImGui::Text("Aperture: f/%.1f", a.lensProbe.aperture);
        }
        ImGui::Separator();

        ImGui::Checkbox("Enable lens correction (distortion / vignette / CA)", &a.lensCorrectionEnabled);
        ImGui::BeginDisabled(!a.lensCorrectionEnabled);

        ImGui::Spacing();
        ImGui::TextDisabled("Camera model (editable — type to filter the list below)");
        ImGui::SetNextItemWidth(-1);
        ImGui::InputText("##lenscam", a.lensCameraBuf, sizeof(a.lensCameraBuf));
        if (ImGui::BeginChild("##lenscamlist", ImVec2(360, 90), true)) {
            std::string q(a.lensCameraBuf);
            for (auto& c : q) c = (char)tolower((unsigned char)c);
            for (auto& e : a.lensCameraList) {
                std::string label = e.maker + " " + e.model;
                std::string low = label;
                for (auto& c : low) c = (char)tolower((unsigned char)c);
                if (!q.empty() && low.find(q) == std::string::npos) continue;
                if (ImGui::Selectable(label.c_str())) {
                    std::snprintf(a.lensCameraBuf, sizeof(a.lensCameraBuf), "%s", e.model.c_str());
                }
            }
        }
        ImGui::EndChild();

        ImGui::Spacing();
        ImGui::TextDisabled("Lens model (editable — type to filter the list below)");
        ImGui::SetNextItemWidth(-1);
        ImGui::InputText("##lenslens", a.lensLensBuf, sizeof(a.lensLensBuf));
        if (ImGui::BeginChild("##lenslenslist", ImVec2(360, 90), true)) {
            std::string q(a.lensLensBuf);
            for (auto& c : q) c = (char)tolower((unsigned char)c);
            for (auto& e : a.lensLensList) {
                std::string label = e.maker + " " + e.model;
                std::string low = label;
                for (auto& c : low) c = (char)tolower((unsigned char)c);
                if (!q.empty() && low.find(q) == std::string::npos) continue;
                if (ImGui::Selectable(label.c_str())) {
                    std::snprintf(a.lensLensBuf, sizeof(a.lensLensBuf), "%s", e.model.c_str());
                }
            }
        }
        ImGui::EndChild();

        if (a.lensProbe.ok && a.lensProbe.focalMm <= 0.f) {
            ImGui::Spacing();
            ImGui::TextDisabled("Focal length override (mm) — required for adapted/manual lenses");
            ImGui::SetNextItemWidth(120);
            if (ImGui::InputText("##focalov", a.lensFocalBuf, sizeof(a.lensFocalBuf),
                                 ImGuiInputTextFlags_CharsDecimal)) {
                a.lensFocalOverrideMm = (float)std::atof(a.lensFocalBuf);
            }
        }
        ImGui::EndDisabled();
        ImGui::Separator();

        if (a.lensProbe.matched) {
            ImGui::TextColored(ImVec4(0.3f, 0.9f, 0.3f, 1.f),
                "Matched: %s + %s (crop %.2fx) — will be corrected",
                a.lensProbe.matchedCameraModel.c_str(), a.lensProbe.matchedLensModel.c_str(),
                a.lensProbe.matchedCropFactor);
        } else if (a.lensCorrectionEnabled) {
            ImGui::TextColored(ImVec4(0.9f, 0.75f, 0.2f, 1.f),
                "No confident match yet — edit camera/lens above, or open anyway (uncorrected)");
        }

        ImGui::Spacing();
        if (ImGui::Button("Open", ImVec2(120, 0))) {
            doOpen(a, a.pendingOpenPath);
            // A genuinely new image invalidates any AI adjustment baked into
            // the previous image's params — the identity blob doOpen() leaves
            // untouched still carries the old edits, so clear the AI bit here.
            a.aiColorEnhanceEnabled = false;
            a.aiColorEnhanceApplied.clear();
            a.aiExposeApplied.clear();
            a.aiExposeLastComputed.clear();
            a.dceScore = -1.f;
            a.pendingOpenPath.clear();
            ImGui::CloseCurrentPopup();
        }
        ImGui::SameLine();
        if (ImGui::Button("Cancel", ImVec2(120, 0))) {
            a.pendingOpenPath.clear();
            ImGui::CloseCurrentPopup();
        }
        ImGui::EndPopup();
    }

    ImGui::SameLine();
    if (ImGui::Button("Reset all")) {
        std::copy(raz::kShaderParamsIdentity,
                  raz::kShaderParamsIdentity + raz::kShaderParamsCount,
                  a.params.begin());
        a.haveLut = false; a.lutName = "(none)";
        a.aiColorEnhanceEnabled = false;
        a.aiColorEnhanceApplied.clear();
        a.aiExposeApplied.clear();
        a.aiExposeLastComputed.clear();
        dirty = true;
    }
    ImGui::Separator();

    ImGui::BeginDisabled(!a.imageOpen);

    if (ImGui::BeginTabBar("##adj")) {

        // ════════════════════════════════════════════════════════════════════
        // TONE tab
        // Matches Android: RawLightTab (Smart Bright) + RawTonemapTab +
        //   tonal-zone WB (from LUT ADJ section).
        // ════════════════════════════════════════════════════════════════════
        if (ImGui::BeginTabItem("Tone")) {
            ImGui::TextDisabled("Light");
            dirty |= SlotSlider("Exposure (EV)##ev",   a.params.data(),  0, -4.f,   4.f);
            dirty |= SlotSlider("Contrast##c",          a.params.data(),  1, -1.f,   1.f);
            dirty |= SlotSlider("Highlights##hi",       a.params.data(),  2, -1.f,   1.f);
            dirty |= SlotSlider("Shadows##sh",          a.params.data(),  3, -1.f,   1.f);
            dirty |= SlotSlider("Whites##wh",           a.params.data(),  4, -1.f,   1.f);
            dirty |= SlotSlider("Blacks##bl",           a.params.data(),  5, -1.f,   1.f);
            dirty |= SlotSlider("Dehaze##dh",           a.params.data(), 60, -1.f,   1.f);
            ImGui::Spacing();

            // AI / Recovery features
            ImGui::TextDisabled("Highlight Recovery (re-opens image)");
            bool hlChanged = false;
            hlChanged |= ImGui::RadioButton("Off##hlmode0",  &a.highlightRecoveryMode, 0);
            ImGui::SameLine();
            hlChanged |= ImGui::RadioButton("Blend##hlmode1", &a.highlightRecoveryMode, 1);
            ImGui::SameLine();
            hlChanged |= ImGui::RadioButton("Safe##hlmode2",  &a.highlightRecoveryMode, 2);
            ImGui::SameLine();
            ImGui::TextDisabled("(?)");
            if (ImGui::IsItemHovered()) {
                ImGui::SetTooltip(
                    "Off: hard clip, no recovery, no color cast.\n"
                    "Blend: recovers detail in blown highlights, but can leave\n"
                    "  a magenta/pink cast in smooth clipped areas (clouds, hotspots).\n"
                    "Safe: same recovery as Blend, plus a chroma fade toward\n"
                    "  neutral in those same near-clipped zones — no color cast.");
            }
            if (hlChanged && a.imageOpen && !a.currentImagePath.empty()) {
                doOpen(a, a.currentImagePath);
                dirty = true;
            }

            ImGui::BeginDisabled(!a.imageOpen || !a.aiProbeReady);

            // [-] undo: remove whatever AI Expose currently has applied.
            ImGui::BeginDisabled(a.aiExposeApplied.empty());
            if (ImGui::Button("-##aiexpminus")) {
                for (auto& [slot, value] : a.aiExposeApplied) {
                    if (slot >= 0 && slot < (int)a.params.size()) a.params[slot] -= value;
                }
                a.aiExposeApplied.clear();
                a.status = "AI Expose removed";
                dirty = true;
            }
            ImGui::EndDisabled();
            ImGui::SameLine();

            // Center: (re)probe and apply fresh. Undoes any current
            // application first so repeated clicks never stack.
            if (ImGui::Button("AI Expose##aiexp")) {
                for (auto& [slot, value] : a.aiExposeApplied) {
                    if (slot >= 0 && slot < (int)a.params.size()) a.params[slot] -= value;
                }
                a.aiExposeApplied.clear();

                float score = -1.f, blowFrac = 0.f;
                if (runAiProbe(a, score, blowFrac)) {
                    auto adjustments = razgui::AiMacroGenerators::generateAiExpose(score, blowFrac);
                    applyAiAdjustments(a.params, adjustments);
                    a.aiExposeApplied = adjustments;
                    a.aiExposeLastComputed = adjustments;
                    a.status = "AI Expose applied (DCE=" + std::to_string(score) +
                                ", blown=" + std::to_string(blowFrac) + ")";
                }
                dirty = true;
            }
            ImGui::SameLine();

            // [+] redo: bring back the last computed adjustment without
            // re-probing. Only meaningful right after an undo.
            ImGui::BeginDisabled(!a.aiExposeApplied.empty() || a.aiExposeLastComputed.empty());
            if (ImGui::Button("+##aiexpplus")) {
                applyAiAdjustments(a.params, a.aiExposeLastComputed);
                a.aiExposeApplied = a.aiExposeLastComputed;
                a.status = "AI Expose re-applied";
                dirty = true;
            }
            ImGui::EndDisabled();

            if (a.dceScore >= 0.f) {
                ImGui::SameLine();
                ImGui::TextDisabled("DCE: %.2f", a.dceScore);
            }
            ImGui::EndDisabled();
            if (!a.aiProbeReady) {
                ImGui::TextDisabled("AI model unavailable — see AI_FEATURES_INTEGRATION.md");
            }
            ImGui::Spacing();

            // White Balance (Kelvin delta from as-shot, same storage as Android)
            ImGui::TextDisabled("White Balance");
            dirty |= SlotSlider("Temperature (K delta)##wb", a.params.data(), 8,
                                -3500.f, 6500.f, "%.0f");
            dirty |= SlotSlider("Tint##tint",           a.params.data(),  9, -200.f, 200.f, "%.0f");
            ImGui::Spacing();

            // Tonemap — separate from Light so XMP presets don't clobber AE values
            ImGui::TextDisabled("Tonemap (independent from AE)");
            dirty |= SlotSlider("TM Exposure##tme",     a.params.data(), 141, -3.f,  3.f);
            dirty |= SlotSlider("TM Highlights##tmh",   a.params.data(), 142, -1.f,  1.f);
            dirty |= SlotSlider("TM Shadows##tms",      a.params.data(), 143, -1.f,  1.f);
            ImGui::Spacing();

            // Tonal-zone colour trims (also in LUT ADJ; same slots)
            ImGui::TextDisabled("Tonal Zone Colour");
            dirty |= SlotSlider("Hi Temperature##hitemp", a.params.data(), 201, -1.f, 1.f);
            dirty |= SlotSlider("Hi Tint##hitint",        a.params.data(), 202, -1.f, 1.f);
            dirty |= SlotSlider("Sh Temperature##shtemp", a.params.data(), 203, -1.f, 1.f);
            dirty |= SlotSlider("Sh Tint##shtint",        a.params.data(), 204, -1.f, 1.f);

            ImGui::EndTabItem();
        }

        // ════════════════════════════════════════════════════════════════════
        // COLOR tab
        // Matches Android: RawColorTab — Saturation, Vibrance, Color Density,
        //   HSL 6-anchor panel (Red/Orange/Yellow/Green/Aqua/Blue).
        // Android normalises sat/vib by /100 before putting into params [6][7].
        // HSL: hue -180..180 → stored /180 → slot range -1..1;
        //      sat/lum -100..100 → stored /100 → slot range -1..1.
        // We expose the normalised range directly (matches what apply_macro reads).
        // ════════════════════════════════════════════════════════════════════
        if (ImGui::BeginTabItem("Color")) {
            ImGui::TextDisabled("Global");
            dirty |= SlotSlider("Saturation##sat",     a.params.data(),  6, -1.f, 1.f);
            dirty |= SlotSlider("Vibrance##vib",       a.params.data(),  7, -1.f, 1.f);
            dirty |= SlotSlider("Color Density##cd",   a.params.data(), 343, -1.f, 1.f);
            ImGui::Spacing();

            // AI features
            ImGui::TextDisabled("AI Enhancement");
            ImGui::BeginDisabled(!a.imageOpen || !a.aiProbeReady);
            if (ImGui::Checkbox("AI Color Enhance##aice", &a.aiColorEnhanceEnabled)) {
                if (a.aiColorEnhanceEnabled) {
                    float score = -1.f, blowFrac = 0.f;
                    if (runAiProbe(a, score, blowFrac)) {
                        a.aiColorEnhanceApplied = razgui::AiMacroGenerators::generateAiColorEnhance(score);
                        applyAiAdjustments(a.params, a.aiColorEnhanceApplied);
                        a.status = "AI Color Enhance applied (DCE=" + std::to_string(score) + ")";
                        dirty = true;
                    } else {
                        a.aiColorEnhanceEnabled = false;  // probe failed; don't leave the box checked
                    }
                } else {
                    // Undo whatever the last probe applied.
                    for (auto& [slot, value] : a.aiColorEnhanceApplied) {
                        if (slot >= 0 && slot < (int)a.params.size()) a.params[slot] -= value;
                    }
                    a.aiColorEnhanceApplied.clear();
                    a.status = "AI Color Enhance removed";
                    dirty = true;
                }
            }
            ImGui::EndDisabled();
            ImGui::Spacing();

            // HSL 6-anchor sub-tabs
            // Slot layout: hsl[i] = hsl[10 + anchorIdx*3 + channel]
            //   channel 0 = Hue shift (range -1..1 = -180..180°)
            //   channel 1 = Sat shift (range -1..1 = -100..100)
            //   channel 2 = Lum shift (range -1..1 = -100..100)
            ImGui::TextDisabled("HSL  (per-anchor: Hue / Sat / Lum)");
            const char* hslNames[] = {"Red","Orange","Yellow","Green","Aqua","Blue"};
            if (ImGui::BeginTabBar("##hsl")) {
                for (int i = 0; i < 6; ++i) {
                    if (ImGui::BeginTabItem(hslNames[i])) {
                        int base = 10 + i * 3;
                        char lH[32], lS[32], lL[32];
                        std::snprintf(lH, 32, "Hue##h%d",  i);
                        std::snprintf(lS, 32, "Sat##s%d",  i);
                        std::snprintf(lL, 32, "Lum##l%d",  i);
                        dirty |= SlotSlider(lH, a.params.data(), base,   -1.f, 1.f);
                        dirty |= SlotSlider(lS, a.params.data(), base+1, -1.f, 1.f);
                        dirty |= SlotSlider(lL, a.params.data(), base+2, -1.f, 1.f);
                        ImGui::EndTabItem();
                    }
                }
                ImGui::EndTabBar();
            }

            // Skin Tone (same section shown in Android LUT ADJ / Color)
            ImGui::Spacing();
            ImGui::TextDisabled("Skin Tone");
            dirty |= SlotSlider("Warm##skwm",   a.params.data(), 344, -0.5f, 0.5f);
            dirty |= SlotSlider("Smooth##sksm", a.params.data(), 345,  0.f,  1.f);
            dirty |= SlotSlider("Luma##sklm",   a.params.data(), 346, -0.5f, 0.5f);

            ImGui::EndTabItem();
        }

        // ════════════════════════════════════════════════════════════════════
        // DETAIL tab
        // Matches Android: RawDetailTab — Sharpness, Noise Reduction, Film Grain.
        // ════════════════════════════════════════════════════════════════════
        if (ImGui::BeginTabItem("Detail")) {
            ImGui::TextDisabled("Sharpness");
            dirty |= SlotSlider("Sharpness##sp",         a.params.data(), 149, 0.f, 1.f, "%.2f");
            dirty |= SlotSlider("Smart Sharpness##ssp",  a.params.data(), 150, 0.f, 1.f, "%.2f");
            dirty |= SlotSlider("Texture##tx",           a.params.data(), 152, -1.f, 1.f);
            ImGui::Spacing();

            ImGui::TextDisabled("Noise Reduction");
            dirty |= SlotSlider("Luminance NR##lnr",     a.params.data(), 147, 0.f, 1.f, "%.2f");
            dirty |= SlotSlider("Color NR##cnr",         a.params.data(), 148, 0.f, 1.f, "%.2f");
            dirty |= SlotSlider("Blue NR##bnr",          a.params.data(), 233, 0.f, 1.f, "%.2f");
            dirty |= SlotSlider("Red NR##rnr",           a.params.data(), 234, 0.f, 1.f, "%.2f");
            dirty |= SlotSlider("Smooth Background##sb", a.params.data(), 178, 0.f, 1.f, "%.2f");
            ImGui::Spacing();

            ImGui::TextDisabled("Film Grain");
            dirty |= SlotSlider("Amount##fga",           a.params.data(), 153, 0.f, 1.f, "%.2f");
            dirty |= SlotSlider("Size##fgs",             a.params.data(), 154, 0.f, 1.f, "%.2f");
            dirty |= SlotSlider("Wash Out##fgw",         a.params.data(), 156, 0.f, 1.f, "%.2f");

            ImGui::EndTabItem();
        }

        // ════════════════════════════════════════════════════════════════════
        // FX tab
        // Matches Android: RawEffectsTab — Bloom, Bokeh, Mist, Vintage, Film,
        //   Film Grain (moved from Detail in recent Android build), Ambiance.
        //
        // Slot [209] = ortonStrength = the Bloom/Orton blend amount.
        //   Android: bloomUiToMacro maps UI 0..100 → internal 0..10.
        //   We expose the raw internal range [0..10] directly.
        // Slot [205] = bloomRadius (pixels, default 8)
        // Slot [206] = bloomShape  (anamorphic ratio, default 1)
        // Slot [207] = filmRolloff (analog highlight shoulder 0..1)
        // Slot [208] = ambiance    (edge-aware local contrast)
        // ════════════════════════════════════════════════════════════════════
        if (ImGui::BeginTabItem("FX")) {
            ImGui::TextDisabled("Bloom / Orton");
            dirty |= SlotSlider("Strength##bls",   a.params.data(), 209, 0.f, 10.f, "%.1f");
            dirty |= SlotSlider("Radius (px)##blr", a.params.data(), 205, 0.f, 20.f, "%.1f");
            dirty |= SlotSlider("Shape##blsh",     a.params.data(), 206, 0.f,  4.f, "%.2f");
            ImGui::Spacing();

            ImGui::TextDisabled("Bokeh");
            dirty |= SlotSlider("Background Blur##bok", a.params.data(), 179, 0.f, 50.f, "%.0f");
            ImGui::Spacing();

            ImGui::TextDisabled("Mist");
            dirty |= SlotSlider("Strength##mist",   a.params.data(), 365, 0.f, 1.f, "%.2f");
            dirty |= SlotSlider("Warmth##mistwm",   a.params.data(), 366, -0.5f, 0.5f, "%.2f");
            ImGui::Spacing();

            ImGui::TextDisabled("Vintage");
            dirty |= SlotSlider("Strength##vin",    a.params.data(), 369, 0.f, 1.f, "%.2f");
            dirty |= SlotSlider("Fade##vinfade",    a.params.data(), 370, 0.f, 1.f, "%.2f");
            ImGui::Spacing();

            ImGui::TextDisabled("Film");
            dirty |= SlotSlider("Push / Pull##pp",  a.params.data(), 349, -3.f, 3.f, "%.1f");
            dirty |= SlotSlider("Film Rolloff##fr", a.params.data(), 207, 0.f,  1.f, "%.2f");
            ImGui::Spacing();

            ImGui::TextDisabled("Other");
            dirty |= SlotSlider("Ambiance##amb",    a.params.data(), 208, -1.f, 1.f);

            ImGui::EndTabItem();
        }

        // ════════════════════════════════════════════════════════════════════
        // VIGNETTE tab
        // Matches Android: RawVignetteTab — Amount/Intensity/Feather/CenterX/Y.
        // Segmentation gating (Subject/Background) not available on desktop
        // (no ONNX chain), so vigEffect [66] stays at default 0 (All).
        // ════════════════════════════════════════════════════════════════════
        if (ImGui::BeginTabItem("Vignette")) {
            dirty |= SlotSlider("Amount##vamt",    a.params.data(),  61, -1.f,  1.f);
            dirty |= SlotSlider("Intensity##vint", a.params.data(),  65,  0.f,  1.5f, "%.2f");
            dirty |= SlotSlider("Feather##vfth",   a.params.data(),  64,  0.f,  1.f,  "%.2f");
            dirty |= SlotSlider("Center X##vcx",   a.params.data(),  62,  0.f,  1.f,  "%.2f");
            dirty |= SlotSlider("Center Y##vcy",   a.params.data(),  63,  0.f,  1.f,  "%.2f");
            ImGui::Spacing();
            if (ImGui::Button("Reset vignette")) {
                for (int s : {61, 62, 63, 64, 65}) {
                    a.params[s] = raz::kShaderParamsIdentity[s];
                }
                dirty = true;
            }
            ImGui::EndTabItem();
        }

        // ════════════════════════════════════════════════════════════════════
        // GRADIENT tab
        // Matches Android: RawGradientTab — global angle + 4 sides.
        // Slot layout (ShaderParams.kt):
        //   [68]       gradAngle   degrees [-180..180]
        //   [69..83]   Top    (15 floats)
        //   [84..98]   Bottom (15 floats)
        //   [99..113]  Left   (15 floats)
        //   [114..128] Right  (15 floats)
        // Per-side 15-float layout:
        //   +0 intensity1  +1 length1  +2 feather1
        //   +3..+6 tintRGB+lum (skipped — no colour picker)
        //   +7 enable2  +8 intensity2  +9 length2  +10 feather2
        //   +11..+14 tint2 (skipped)
        // ════════════════════════════════════════════════════════════════════
        if (ImGui::BeginTabItem("Gradient")) {
            dirty |= SlotSlider("Angle##ga", a.params.data(), 68, -180.f, 180.f, "%.0f");
            ImGui::Spacing();

            if (ImGui::BeginTabBar("##gside")) {
                if (ImGui::BeginTabItem("Top##gst")) {
                    dirty |= drawGradientSide("top", a.params.data(), 69);
                    ImGui::EndTabItem();
                }
                if (ImGui::BeginTabItem("Bottom##gsb")) {
                    dirty |= drawGradientSide("bot", a.params.data(), 84);
                    ImGui::EndTabItem();
                }
                if (ImGui::BeginTabItem("Left##gsl")) {
                    dirty |= drawGradientSide("lft", a.params.data(), 99);
                    ImGui::EndTabItem();
                }
                if (ImGui::BeginTabItem("Right##gsr")) {
                    dirty |= drawGradientSide("rgt", a.params.data(), 114);
                    ImGui::EndTabItem();
                }
                ImGui::EndTabBar();
            }
            ImGui::EndTabItem();
        }

        // ════════════════════════════════════════════════════════════════════
        // LUT tab — prebaked library browser + manual file picker
        // Scans engine\luts\ (same category structure as the APK assets).
        // ════════════════════════════════════════════════════════════════════
        if (ImGui::BeginTabItem("LUT")) {
            if (!a.lutLibLoaded) {
                a.lutLib = scanLutLibrary();
                a.lutLibLoaded = true;
            }

            // Active LUT + sliders
            ImGui::Text("LUT: %s", a.lutName.c_str());
            if (a.haveLut) {
                ImGui::SameLine();
                if (ImGui::SmallButton("Clear##lutcl")) {
                    a.haveLut = false; a.lutName = "(none)"; dirty = true;
                }
            }
            ImGui::BeginDisabled(!a.haveLut);
            dirty |= SlotSlider("Strength##luts",            a.params.data(),  31, 0.f,  1.f);
            dirty |= SlotSlider("Highlight Vibrancy##luthv", a.params.data(), 200, -1.f, 1.f);
            ImGui::EndDisabled();
            ImGui::Separator();

            // Manual file picker
            if (ImGui::Button("Load .cube...")) {
                std::string p = openDialog("LUT Files\0*.cube;*.xmp;*.lrtemplate\0Cube LUT\0*.cube\0Lightroom\0*.xmp;*.lrtemplate\0All\0*.*\0");
                if (!p.empty()) {
                    std::string err;
                    if (razgui::loadLutFile(p, a.lut, err)) {
                        a.haveLut = true;
                        size_t s = p.find_last_of("/\\");
                        a.lutName = (s == std::string::npos) ? p : p.substr(s + 1);
                        dirty = true;
                    } else a.status = "LUT error: " + err;
                }
            }
            ImGui::Separator();

            if (a.lutLib.empty()) {
                ImGui::TextDisabled("No LUT library found.");
                ImGui::TextDisabled("Expected: engine\\luts\\<Category>\\*.cube");
            } else {
                // Category dropdown
                ImGui::SetNextItemWidth(-1);
                const char* catLabel = a.lutLib[a.lutLibCat].name.c_str();
                if (ImGui::BeginCombo("##lutcat", catLabel)) {
                    for (int i = 0; i < (int)a.lutLib.size(); ++i) {
                        bool sel = (a.lutLibCat == i);
                        if (ImGui::Selectable(a.lutLib[i].name.c_str(), sel))
                            a.lutLibCat = i;
                        if (sel) ImGui::SetItemDefaultFocus();
                    }
                    ImGui::EndCombo();
                }

                // Search box (full width, with placeholder hint)
                ImGui::SetNextItemWidth(-1);
                ImGui::InputTextWithHint("##lutsearch", "search...",
                                         a.lutSearch, sizeof(a.lutSearch));

                const bool searching = a.lutSearch[0] != '\0';

                // Entry list — fills remaining panel height
                if (ImGui::BeginChild("##lutlist", ImVec2(0, 0), true)) {
                    if (searching) {
                        std::string q(a.lutSearch);
                        for (auto& c : q) c = (char)tolower((unsigned char)c);
                        for (auto& cat : a.lutLib) {
                            for (auto& e : cat.entries) {
                                std::string nl = e.name;
                                for (auto& c : nl) c = (char)tolower((unsigned char)c);
                                if (nl.find(q) == std::string::npos) continue;
                                std::string label = cat.name + " / " + e.name;
                                bool sel = (a.lutName == e.name);
                                if (ImGui::Selectable(label.c_str(), sel)) {
                                    std::string err;
                                    if (razgui::loadCubeLut(e.path, a.lut, err)) {
                                        a.haveLut = true; a.lutName = e.name; dirty = true;
                                    } else a.status = "LUT error: " + err;
                                }
                            }
                        }
                    } else {
                        for (auto& e : a.lutLib[a.lutLibCat].entries) {
                            bool sel = (a.lutName == e.name);
                            if (ImGui::Selectable(e.name.c_str(), sel)) {
                                std::string err;
                                if (razgui::loadCubeLut(e.path, a.lut, err)) {
                                    a.haveLut = true; a.lutName = e.name; dirty = true;
                                } else a.status = "LUT error: " + err;
                            }
                        }
                    }
                }
                ImGui::EndChild();
            }
            ImGui::EndTabItem();
        }

        // ════════════════════════════════════════════════════════════════════
        // LUT ADJ tab
        // Matches Android: RawLutTab (showFinishing=true) — CLAHE, zone WB,
        //   skin tone, Push/Pull.
        // ════════════════════════════════════════════════════════════════════
        if (ImGui::BeginTabItem("LUT ADJ")) {
            ImGui::TextDisabled("CLAHE (adaptive local contrast)");
            dirty |= SlotCheck("Enable CLAHE##clahe", a.params.data(), 144);
            ImGui::BeginDisabled(a.params[144] < 0.5f);
            dirty |= SlotSlider("Shadows Boost##csb",    a.params.data(), 145, -1.f, 1.f);
            dirty |= SlotSlider("Highlights Boost##chb", a.params.data(), 146, -1.f, 1.f);
            ImGui::EndDisabled();
            ImGui::Spacing();

            ImGui::TextDisabled("Tonal Zone Colour");
            dirty |= SlotSlider("Hi Temperature##laht",  a.params.data(), 201, -1.f, 1.f);
            dirty |= SlotSlider("Hi Tint##lahtint",      a.params.data(), 202, -1.f, 1.f);
            dirty |= SlotSlider("Sh Temperature##last",  a.params.data(), 203, -1.f, 1.f);
            dirty |= SlotSlider("Sh Tint##lastint",      a.params.data(), 204, -1.f, 1.f);
            ImGui::Spacing();

            ImGui::TextDisabled("Skin Tone");
            dirty |= SlotSlider("Warm##laskwm",  a.params.data(), 344, -0.5f, 0.5f);
            dirty |= SlotSlider("Smooth##laksm", a.params.data(), 345,  0.f,  1.f);
            dirty |= SlotSlider("Luma##laklm",   a.params.data(), 346, -0.5f, 0.5f);
            ImGui::Spacing();

            ImGui::TextDisabled("Film");
            dirty |= SlotSlider("Push / Pull##lapp", a.params.data(), 349, -3.f, 3.f, "%.1f");

            ImGui::EndTabItem();
        }

        // ════════════════════════════════════════════════════════════════════
        // EXPORT tab
        // ════════════════════════════════════════════════════════════════════
        if (ImGui::BeginTabItem("Export")) {
            ImGui::TextDisabled("Full-resolution export");
            ImGui::Spacing();

            if (ImGui::Button("Save TIFF-16...")) {
                std::string out = saveDialog("TIFF 16-bit\0*.tif\0", "tif");
                if (!out.empty()) {
                    syncLut(a);
                    a.params[28] = 0.f;  // dither off for 16-bit
                    std::string err;
                    bool ok = a.engine.exportTiff16(a.params.data(), (int)a.params.size(),
                                  a.haveLut ? &a.lut : nullptr, out, err);
                    a.params[28] = 1.f;  // restore for preview
                    a.status = ok ? ("Saved: " + out) : ("Export failed: " + err);
                }
            }
            ImGui::Spacing();
            ImGui::Text("JPEG quality:");
            ImGui::SetNextItemWidth(60);
            if (ImGui::InputText("##jq", a.jpegQualityBuf, sizeof(a.jpegQualityBuf),
                                 ImGuiInputTextFlags_CharsDecimal)) {
                int q = std::atoi(a.jpegQualityBuf);
                if (q >= 1 && q <= 100) a.jpegQuality = q;
            }
            ImGui::SameLine();
            if (ImGui::Button("Save JPEG...")) {
                std::string out = saveDialog("JPEG\0*.jpg\0", "jpg");
                if (!out.empty()) {
                    syncLut(a); std::string err;
                    bool ok = a.engine.exportJpegOrPng(a.params.data(), (int)a.params.size(),
                                  a.haveLut ? &a.lut : nullptr, out, false, a.jpegQuality, err);
                    a.status = ok ? ("Saved: " + out) : ("Export failed: " + err);
                }
            }
            if (ImGui::Button("Save PNG...")) {
                std::string out = saveDialog("PNG\0*.png\0", "png");
                if (!out.empty()) {
                    syncLut(a); std::string err;
                    bool ok = a.engine.exportJpegOrPng(a.params.data(), (int)a.params.size(),
                                  a.haveLut ? &a.lut : nullptr, out, true, 0, err);
                    a.status = ok ? ("Saved: " + out) : ("Export failed: " + err);
                }
            }
            ImGui::EndTabItem();
        }

        ImGui::EndTabBar();
    }

    ImGui::EndDisabled();

    ImGui::Separator();
    ImGui::Checkbox("Show Before (A/B)", &a.showBefore);
    ImGui::SameLine();
    if (ImGui::Button("Reset view")) { a.zoom = 0.f; a.pan = ImVec2(0, 0); }

    return dirty;
}

// ── main ─────────────────────────────────────────────────────────────────────
int main(int, char**) {
    // Set RAZ_LOG=1 in the environment before launching to see the engine's
    // diagnostic LOGI lines on stderr (camMul derivation, which decode path
    // was taken, as_shot_wb_applied, etc.) — invaluable for tracing color
    // casts back to their actual cause instead of guessing. Off by default
    // to keep the console quiet for normal use.
    if (const char* v = std::getenv("RAZ_LOG"); v && v[0] == '1') {
        raz_log_enabled = 1;
    }
    if (!glfwInit()) { std::fprintf(stderr, "glfwInit failed\n"); return 1; }
    GLFWwindow* win = glfwCreateWindow(1500, 950, "RAZStudio Desktop", nullptr, nullptr);
    if (!win) { glfwTerminate(); return 1; }
    glfwMakeContextCurrent(win);
    glfwSwapInterval(1);

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGui::GetIO().ConfigFlags |= ImGuiConfigFlags_NavEnableKeyboard;
    ImGui::StyleColorsDark();
    ImGui_ImplGlfw_InitForOpenGL(win, true);
    ImGui_ImplOpenGL2_Init();

    App app;
    app.engine.initAiProbe(exeDir() + "\\models\\zero_dce.onnx");
    app.aiProbeReady = app.engine.aiProbeReady();
    if (!app.aiProbeReady) {
        app.status = "AI features unavailable (ONNX Runtime/model not found) — see AI_FEATURES_INTEGRATION.md";
    }

    while (!glfwWindowShouldClose(win)) {
        glfwPollEvents();
        ImGui_ImplOpenGL2_NewFrame();
        ImGui_ImplGlfw_NewFrame();
        ImGui::NewFrame();

        const ImGuiViewport* vp = ImGui::GetMainViewport();
        const float panelW = 360.f;
        const float statusH = 26.f;

        // Controls panel
        ImGui::SetNextWindowPos(ImVec2(vp->WorkPos.x, vp->WorkPos.y));
        ImGui::SetNextWindowSize(ImVec2(panelW, vp->WorkSize.y - statusH));
        ImGui::Begin("Controls", nullptr,
                     ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoResize |
                     ImGuiWindowFlags_NoCollapse);
        if (drawAdjustmentPanel(app)) rerender(app);
        ImGui::End();

        // Canvas
        ImGui::SetNextWindowPos(ImVec2(vp->WorkPos.x + panelW, vp->WorkPos.y));
        ImGui::SetNextWindowSize(ImVec2(vp->WorkSize.x - panelW, vp->WorkSize.y - statusH));
        ImGui::Begin("Preview", nullptr,
                     ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoResize |
                     ImGuiWindowFlags_NoCollapse | ImGuiWindowFlags_NoScrollbar |
                     ImGuiWindowFlags_NoScrollWithMouse);

        GlTexture& tex = (app.showBefore && app.haveBefore) ? app.texBefore : app.texAfter;
        if (tex.id) {
            ImVec2 avail  = ImGui::GetContentRegionAvail();
            ImVec2 origin = ImGui::GetCursorScreenPos();
            float fit = 1.f;
            if (tex.w > 0 && tex.h > 0)
                fit = std::min(avail.x / (float)tex.w, avail.y / (float)tex.h);
            if (app.zoom <= 0.f) app.zoom = fit;
            ImVec2 imgSize(tex.w * app.zoom, tex.h * app.zoom);
            ImVec2 p0(origin.x + (avail.x - imgSize.x) * 0.5f + app.pan.x,
                      origin.y + (avail.y - imgSize.y) * 0.5f + app.pan.y);
            ImVec2 p1(p0.x + imgSize.x, p0.y + imgSize.y);
            ImGui::GetWindowDrawList()->AddImage((ImTextureID)(intptr_t)tex.id, p0, p1);
            ImGui::InvisibleButton("canvas", avail,
                ImGuiButtonFlags_MouseButtonLeft | ImGuiButtonFlags_MouseButtonMiddle);
            if (ImGui::IsItemHovered()) {
                float wheel = ImGui::GetIO().MouseWheel;
                if (wheel != 0.f) {
                    ImVec2 m = ImGui::GetIO().MousePos;
                    float old = app.zoom;
                    app.zoom *= (wheel > 0 ? 1.1f : 1.f / 1.1f);
                    app.zoom = std::min(std::max(app.zoom, fit * 0.25f), fit * 40.f);
                    float k = app.zoom / old - 1.f;
                    app.pan.x -= (m.x - (p0.x + imgSize.x * 0.5f)) * k;
                    app.pan.y -= (m.y - (p0.y + imgSize.y * 0.5f)) * k;
                }
            }
            if (ImGui::IsItemActive() &&
                (ImGui::IsMouseDragging(ImGuiMouseButton_Left) ||
                 ImGui::IsMouseDragging(ImGuiMouseButton_Middle))) {
                ImVec2 d = ImGui::GetIO().MouseDelta;
                app.pan.x += d.x; app.pan.y += d.y;
            }
            if (app.showBefore && app.haveBefore)
                ImGui::GetWindowDrawList()->AddText(
                    ImVec2(p0.x + 8, p0.y + 8), IM_COL32(255, 220, 0, 255), "BEFORE");
        } else {
            ImGui::TextUnformatted("No image. Click \"Open image...\".");
        }
        ImGui::End();

        // Status bar
        ImGui::SetNextWindowPos(ImVec2(vp->WorkPos.x,
                                       vp->WorkPos.y + vp->WorkSize.y - statusH));
        ImGui::SetNextWindowSize(ImVec2(vp->WorkSize.x, statusH));
        ImGui::Begin("##status", nullptr,
                     ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoResize |
                     ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoScrollbar);
        ImGui::Text("%s", app.status.c_str());
        if (app.imageOpen) {
            ImGui::SameLine(ImGui::GetWindowWidth() - 280);
            ImGui::Text("last render: %lld ms   zoom: %.0f%%",
                        (long long)app.lastMs,
                        (app.zoom > 0 ? app.zoom : 1.f) * 100.f);
        }
        ImGui::End();

        ImGui::Render();
        int dw, dh; glfwGetFramebufferSize(win, &dw, &dh);
        glViewport(0, 0, dw, dh);
        glClearColor(0.10f, 0.10f, 0.11f, 1.f);
        glClear(GL_COLOR_BUFFER_BIT);
        ImGui_ImplOpenGL2_RenderDrawData(ImGui::GetDrawData());
        glfwSwapBuffers(win);
    }

    app.texAfter.destroy();
    app.texBefore.destroy();
    ImGui_ImplOpenGL2_Shutdown();
    ImGui_ImplGlfw_Shutdown();
    ImGui::DestroyContext();
    glfwDestroyWindow(win);
    glfwTerminate();
    return 0;
}
