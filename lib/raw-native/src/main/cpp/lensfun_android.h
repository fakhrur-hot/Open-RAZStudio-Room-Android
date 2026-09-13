#pragma once
#include <string>
#include <vector>
#include <cstdint>

// ── Lensfun Android — self-contained lens correction ─────────────────────────
// Parses a directory of Lensfun XML database files using expat (bundled in
// Android's libexpat) and applies ptlens/poly3 distortion, PA vignetting and
// poly3 TCA corrections.  No GLib dependency.
//
// The correction math is a faithful port of lensfun master
// (libs/lensfun/{modifier,mod-coord,mod-subpix,mod-color}.cpp):
//   • Coordinates normalised by NormScale = 43.2666mm / Crop / hypot(W,H) / RealFocal
//     (i.e. radii measured in units of the real focal length on the 35mm frame).
//   • Calibration coefficients rescaled by hugin_scaling = RealFocal / hugin_mm
//     with hugin_mm = 43.2666 / CalibCrop / hypot(CalibAspect, 1) / 2, plus the
//     PT "d" re-normalisation (a/d⁴, b/d³, c/d²) so transforms preserve focal.
//   • Distortion (correcting): Rd = Ru·(a_·Ru³ + b_·Ru² + c_·Ru + 1).
//   • TCA poly3 (correcting):  Rd_ch = Ru·(b_ch·Ru² + c_ch·Ru + v_ch).
//   • Devignetting: pixel *= 1 / (1 + k1·r² + k2·r⁴ + k3·r⁶), in LINEAR light.

struct LfDistEntry {
    float focal;
    float realFocal;        // 0 = not calibrated (fall back to nominal focal)
    bool  poly3;            // false = ptlens(a,b,c); true = poly3(k1 in .a)
    float a, b, c;
};

struct LfVigEntry {
    float focal, aperture, distance;
    float k1, k2, k3;       // PA model: Cd = 1 + k1*r² + k2*r⁴ + k3*r⁶
};

struct LfTcaEntry {
    float focal;
    float vr, vb;           // poly3: Rd_ch = Ru*(b*Ru² + c*Ru + v)
    float cr, cb;
    float br, bb;
};

struct LfLensProfile {
    std::string maker;
    std::string model;
    std::string mount;
    float cropFactor = 0.f;     // calibration sensor crop (0 = unknown)
    float aspect     = 1.5f;    // calibration sensor aspect ratio (3:2 default)
    float minFocal = 0, maxFocal = 0;
    float minAperture = 0;
    std::vector<LfDistEntry> dist;
    std::vector<LfVigEntry>  vig;
    std::vector<LfTcaEntry>  tca;
};

struct LfCameraProfile {
    std::string maker;
    std::string model;
    std::string alias;   // marketing name from <model lang="…"> (e.g. "Alpha 7 II"); may be empty
    std::string mount;
    float cropFactor = 1.0f;
};

struct LfDatabase {
    std::vector<LfCameraProfile> cameras;
    std::vector<LfLensProfile>   lenses;
};

// Resolved camera+lens pair for one image. Valid only when both matched.
/**
 * How much to trust an automatic lens match. The product rule is that only a
 * HIGH match may be applied silently; anything less has to be confirmed by the
 * user, and NONE must never be guessed at.
 */
enum LfaConfidence {
    LFA_CONF_NONE   = 0,   // below the strict gate — do not offer
    LFA_CONF_LOW    = 1,   // plausible; show a brand/range fallback list
    LFA_CONF_MEDIUM = 2,   // likely; show the ranked shortlist and confirm
    LFA_CONF_HIGH   = 3,   // safe to auto-apply
};

struct LfaMatch {
    const LfCameraProfile* cam  = nullptr;
    const LfLensProfile*   lens = nullptr;
    LfaConfidence          confidence = LFA_CONF_NONE;
    long long              score      = 0;
    bool ok() const { return cam != nullptr && lens != nullptr; }
};

// ── Public API ────────────────────────────────────────────────────────────────

/** Load all *.xml files from dbDir into db. Returns number of files loaded. */
int lfa_load_database(LfDatabase& db, const char* dbDir);

/**
 * Process-wide cached database keyed by directory: parses once, then returns
 * the same instance (thread-safe). Returns nullptr when the directory can't
 * be read. Used by both the Stage A import hook and the UI probe JNI so the
 * ~5 MB XML set is only parsed once per process.
 */
const LfDatabase* lfa_cached_database(const char* dbDir);

/** Find best-matching camera (case-insensitive fuzzy match). Returns nullptr if not found. */
const LfCameraProfile* lfa_find_camera(const LfDatabase& db,
                                        const char* maker, const char* model);

/** Find best-matching lens profile. Returns nullptr if not found. */
const LfLensProfile* lfa_find_lens(const LfDatabase& db,
                                    const LfCameraProfile* cam,
                                    const char* lensMaker, const char* lensModel,
                                    float focalLength);

/**
 * Strict camera+lens resolution for automatic import correction. Unlike
 * lfa_find_lens (best-effort), this REQUIRES a confident match on both the
 * camera body and the lens (all numeric tokens of the EXIF lens string must
 * appear in the DB model) — per product rule "only when all fields auto
 * retrieved successfully does the correction run".
 */
LfaMatch lfa_match_strict(const LfDatabase& db,
                          const char* camMaker, const char* camModel,
                          const char* lensMaker, const char* lensModel);

/**
 * Per-criterion breakdown of one candidate's score, in the SAME priority order
 * the matcher uses. Exposed so the UI can explain WHY a candidate ranked where
 * it did (the alternative is re-deriving it on the Kotlin side, which is how
 * matchers drift out of sync).
 */
struct LfaLensScore {
    long long total = 0;
    int nameTier = 0;   // 5 exact · 4 db⊇exif · 3 exif⊇db · 2-1 numeric only
    int fmt      = 0;   // 2 same sensor format · 1 covers more/unknown · 0 too small
    int mount    = 0;   // 1 mount matches the body
    int brand    = 0;   // 2 named/tagged maker · 1 body-maker nudge · 0 none
    int range    = 0;   // 2 focal range agrees · 1 unknown · 0 differs
    int aperture = 0;   // 2 agrees · 1 unknown · 0 differs
    int stab     = 0;   // 2 same stabiliser set · 1 plainer · 0 extra/conflicting
    int motor    = 0;   // 2 same motor/glass set · 1 plainer · 0 extra/conflicting
    int prox     = 0;   // 0-9 crop-factor closeness, least significant tie-break
};

/** One ranked candidate. `lens` is owned by the database, never freed here. */
struct LfaLensCandidate {
    const LfLensProfile* lens = nullptr;
    LfaLensScore         score;
    LfaConfidence        confidence = LFA_CONF_NONE;
};

/**
 * Rank up to `maxOut` lens candidates for an already-resolved camera body,
 * best first, so the UI can present a shortlist instead of a single opaque
 * answer. Shares ONE scorer with lfa_match_strict — the ranked list and the
 * auto-applied match can never disagree.
 *
 * `adaptedMode` drops the mount criterion (an adapted lens on a dumb adapter
 * physically cannot report a matching mount) while keeping the sensor-format
 * preference intact. Returns the number written to `out`.
 */
int lfa_rank_lenses(const LfDatabase& db,
                    const LfCameraProfile* cam,
                    const char* lensMaker, const char* lensModel,
                    bool adaptedMode,
                    LfaLensCandidate* out, int maxOut);

/** Score+confidence for one specific DB lens, for the "why this?" diagnostics. */
LfaLensCandidate lfa_score_one(const LfDatabase& db,
                               const LfCameraProfile* cam,
                               const char* lensMaker, const char* lensModel,
                               const LfLensProfile* candidate,
                               bool adaptedMode);

/**
 * Apply devignetting + distortion + TCA correction in place on an
 * sRGB-encoded RGBA __fp16 buffer (as produced by Stage A). Vignetting is
 * corrected in linear light (decode→gain→re-encode); geometry is resampled
 * bilinearly with lensfun-style auto-scale so no unfilled borders appear.
 * Multi-threaded. Returns true if any correction was applied.
 */
bool lfa_correct_rgba_f16(uint16_t* rgbaF16, int width, int height,
                          const LfaMatch& match,
                          float focalMm, float apertureF);

/**
 * Diagnostic record of the LAST lfa_correct_rgba_f16 call on this thread.
 * `vig`: 0 = none, 1 = profile calibration, 2 = generic cos⁴ fallback (the
 * lens has no <vignetting> data at all). Read it right after the call — the
 * Stage A / JPEG import paths fold it into their one-line LENS-REPORT log.
 */
struct LfaReport {
    bool  ran      = false;   // lfa_correct_rgba_f16 got past its guards
    bool  applied  = false;   // any pass ran
    bool  dist     = false;
    bool  tca      = false;
    int   vig      = 0;
    float zoom     = 1.f;
    float focalMm  = 0.f;
    float aperture = 0.f;
    float camCrop  = 1.f;
    float calCrop  = 1.f;
    int   confidence = 0;
    char  cam[96]  = {0};
    char  lens[128] = {0};
    const char* reason = "";  // why nothing applied, when !applied
};
const LfaReport& lfa_last_report();
/** Compact single-line rendering of lfa_last_report() for logs. */
int lfa_report_string(char* out, int cap);

/**
 * Legacy path (v2 decoder): apply corrections on a float32 interleaved RGB
 * buffer. Kept for lens_corrector.cpp compatibility.
 */
void lfa_apply_corrections(
    float* pixels, int width, int height,
    const LfLensProfile* lens,
    const LfCameraProfile* cam,
    float focalLength, float aperture,
    float distStrength, float vigStrength, float caStrength);
