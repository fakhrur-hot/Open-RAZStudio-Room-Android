// lensfun_android.cpp
// Self-contained Lensfun XML loader + lens correction math for Android.
// Uses Android's system expat (libexpat.so) — no GLib required.
//
// The correction path (lfa_correct_rgba_f16) is a faithful port of lensfun
// master: coordinate normalisation, coefficient rescaling and the model
// formulas match libs/lensfun/{modifier,mod-coord,mod-subpix,mod-color}.cpp.
// The legacy float-RGB path (lfa_apply_corrections) is kept untouched for
// the v2 decoder's lens_corrector.cpp.

#include "lensfun_android.h"
#include <expat.h>
#include <android/log.h>
#include <dirent.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cctype>
#include <algorithm>
#include <memory>
#include <mutex>
#include <thread>

#define LFA_TAG "LensfunAndroid"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LFA_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LFA_TAG, __VA_ARGS__)

// ── Zero-DCE adaptive devignetting ────────────────────────────────────────────
//
// The static pass multiplies dark corners by a steep radial gain, amplifying
// sensor readout noise. The 256×256 Zero-DCE lift map (per-pixel mean |A| of
// its 24 curve channels, computed from the embedded thumbnail BEFORE Stage A)
// marks deep-shadow (low-SNR) regions; the radial gain is attenuated there:
//     M(x,y) = 1 − clamp(aMean/τ, 0, 1)      (τ default 0.7)
//     G_final = 1 + (G_lens − 1) · M

// Bilinear sample of the square Zero-DCE lift map at normalized coords.
static inline float lfaSampleLift(const float* map, int side, float u, float v) {
    if (!map || side < 2) return 0.0f;
    const float px = fminf(fmaxf(u * (side - 1), 0.0f), (float)(side - 1));
    const float py = fminf(fmaxf(v * (side - 1), 0.0f), (float)(side - 1));
    const int x0 = (int)px, y0 = (int)py;
    const int x1 = x0 + 1 < side ? x0 + 1 : x0;
    const int y1 = y0 + 1 < side ? y0 + 1 : y0;
    const float dx = px - x0, dy = py - y0;
    const float t = map[y0 * side + x0] * (1 - dx) + map[y0 * side + x1] * dx;
    const float b = map[y1 * side + x0] * (1 - dx) + map[y1 * side + x1] * dx;
    return t * (1 - dy) + b * dy;
}

// ── String helpers ────────────────────────────────────────────────────────────

static float atof_safe(const char* s) { return s ? (float)atof(s) : 0.0f; }

static std::string lower(const char* s) {
    std::string out(s ? s : "");
    for (auto& c : out) c = (char)tolower((unsigned char)c);
    return out;
}

// Aggressive normalisation for matching: lowercase, alnum only. Makes
// "EF24-105mm f/4L IS USM" and "Canon EF 24-105mm f/4L IS USM" comparable
// by containment.
static std::string normKey(const char* s) {
    std::string out;
    for (const char* p = s ? s : ""; *p; ++p) {
        unsigned char c = (unsigned char)*p;
        if (isalnum(c)) out += (char)tolower(c);
    }
    return out;
}

// All numeric tokens ("24", "105", "4", "2.8") of a string, as normalised
// digit-strings with the dot removed ("2.8" → "28") to survive normKey.
static std::vector<std::string> numericTokens(const char* s) {
    std::vector<std::string> out;
    std::string cur;
    for (const char* p = s ? s : ""; ; ++p) {
        char c = *p;
        if (isdigit((unsigned char)c)) cur += c;
        else if (c == '.' && !cur.empty() && isdigit((unsigned char)p[1])) { /* skip dot */ }
        else { if (cur.size() > 0) out.push_back(cur); cur.clear(); if (!c) break; }
    }
    return out;
}

// ── Lens "feature appendix" tokens ───────────────────────────────────────────
// A lens name's trailing acronyms identify the OPTICAL VARIANT, not decoration.
// "Canon EF 35mm f/2" (1990) and "Canon EF 35mm f/2 IS USM" (2012) are wholly
// different designs with different distortion and TCA curves, yet they share
// focal length AND maximum aperture — so a matcher keyed on numbers alone picks
// whichever the DB happens to list first, i.e. it is wrong about half the time.
//
// Two classes of appendix reliably signal a distinct optical design:
//   • stabiliser      — IS VR OS OSS VC OIS
//   • motor / glass   — USM STM HSM PZD APO ZA SSM SAM
// Everything else is deliberately IGNORED: L, DX, DG, DC, EX, ASPH, IF, ED,
// Pro, II/III and friends are coverage or revision marks that do not determine
// which calibration is correct, and demanding they match would throw away good
// profiles for no gain.
//
// Tokenised on non-alphanumeric boundaries, never by substring — "IS" as a
// substring hits half the dictionary, "AI-S" would false-positive, and so on.
// The two classes are kept SEPARATE because they sit at different priorities:
// stabiliser outranks motor/glass (see lensMatchScore).
static const char* kStabTokens[]  = { "is", "vr", "os", "oss", "vc", "ois" };
static const char* kMotorTokens[] = { "usm", "stm", "hsm", "pzd", "apo",
                                      "za", "ssm", "sam" };

// class: 0 = stabiliser, 1 = motor/glass, 2 = either (union).
static std::vector<std::string> featureTokensOf(const char* s, int cls) {
    std::vector<std::string> out;
    std::string cur;
    for (const char* p = s ? s : ""; ; ++p) {
        const unsigned char c = (unsigned char)*p;
        if (isalnum(c)) { cur += (char)tolower(c); if (*p) continue; }
        if (!cur.empty()) {
            bool hit = false;
            if (cls == 0 || cls == 2)
                for (const char* f : kStabTokens)  if (cur == f) { hit = true; break; }
            if (!hit && (cls == 1 || cls == 2))
                for (const char* f : kMotorTokens) if (cur == f) { hit = true; break; }
            if (hit && std::find(out.begin(), out.end(), cur) == out.end())
                out.push_back(cur);
            cur.clear();
        }
        if (!*p) break;
    }
    std::sort(out.begin(), out.end());
    return out;
}

// normKey with the feature appendix REMOVED, so the variant question is asked
// once, deliberately, by featureAffinity rather than being smuggled into the
// name-containment test. Without this, containment is asymmetric in a way that
// loses the fallback the product rule requires: EXIF "EF35mm f/2 IS USM" is not
// a substring of "canonef35mmf2" (the DB name carries a "Canon" prefix the EXIF
// string lacks) and vice versa, so a body reporting the IS USM variant found
// NOTHING when only the plain lens was calibrated. Stripped to "ef35mmf2" vs
// "canonef35mmf2" it matches, and the appendix ranking then does its job.
static std::string baseKey(const char* s) {
    std::string out, cur;
    for (const char* p = s ? s : ""; ; ++p) {
        const unsigned char c = (unsigned char)*p;
        if (isalnum(c)) { cur += (char)tolower(c); if (*p) continue; }
        if (!cur.empty()) {
            bool isFeat = false;
            for (const char* f : kStabTokens)  if (cur == f) { isFeat = true; break; }
            if (!isFeat)
                for (const char* f : kMotorTokens) if (cur == f) { isFeat = true; break; }
            if (!isFeat) out += cur;
            cur.clear();
        }
        if (!*p) break;
    }
    return out;
}

// ── Structured identity parsed out of a lens NAME ────────────────────────────
// The DB's own minFocal/maxFocal/minAperture fields are declared but never
// populated by the parser, and EXIF gives us only a display string, so both
// sides have to be read out of the name. Deliberately separate from
// numericTokens(): conflating focal with aperture is what let "35mm f/2" match
// "35mm f/2.8".
struct LensSpec {
    float minFocal = 0, maxFocal = 0;   // 0 = not parseable
    float maxAperture = 0;              // the WIDEST aperture (smallest f-number)
};

// "24-105mm f/4L IS USM" → focal 24..105, aperture 4
// "35mm f/2"             → focal 35..35,  aperture 2
// "18-270mm F/3.5-6.3"   → focal 18..270, aperture 3.5 (wide end)
static LensSpec parseLensSpec(const char* s) {
    LensSpec sp;
    const std::string t = lower(s ? s : "");
    // Focal: the number (or N-M range) immediately preceding "mm".
    const size_t mm = t.find("mm");
    if (mm != std::string::npos) {
        size_t e = mm;
        while (e > 0 && (isdigit((unsigned char)t[e-1]) || t[e-1] == '.' ||
                         t[e-1] == '-' || t[e-1] == ' ')) --e;
        std::string seg = t.substr(e, mm - e);
        // Trim trailing/leading spaces, then split on '-'.
        while (!seg.empty() && seg.front() == ' ') seg.erase(seg.begin());
        while (!seg.empty() && seg.back()  == ' ') seg.pop_back();
        const size_t dash = seg.find('-');
        if (dash != std::string::npos) {
            sp.minFocal = (float)atof_safe(seg.substr(0, dash).c_str());
            sp.maxFocal = (float)atof_safe(seg.substr(dash + 1).c_str());
        } else if (!seg.empty()) {
            sp.minFocal = sp.maxFocal = (float)atof_safe(seg.c_str());
        }
        if (sp.minFocal <= 0.f || sp.maxFocal <= 0.f) { sp.minFocal = sp.maxFocal = 0.f; }
    }
    // Aperture: first number after an "f/", "f", or "1:" marker. Take the wide
    // end of a variable-aperture range.
    for (size_t i = 0; i + 1 < t.size(); ++i) {
        bool marker = false;
        // The 'f' must start a WORD. Without that check the 'f' of "EF35mm"
        // reads as an aperture marker and "EF35mm f/2" parses as f/35 — which
        // then disagrees with the DB's f/2 and demotes every correct Canon
        // match. It went unnoticed for focal lengths above the 45 sanity cap
        // ("EF50mm" → 50 → rejected → the real f/1.8 found), so it only
        // corrupted wide and normal primes: 16, 17, 24, 35, 40.
        const bool wordStart = (i == 0) || !isalpha((unsigned char)t[i-1]);
        if (t[i] == 'f' && wordStart &&
            (t[i+1] == '/' || isdigit((unsigned char)t[i+1]))) marker = true;
        // Soviet/older notation: "58mm 1:2".
        if (t[i] == ':' && i > 0 && t[i-1] == '1') marker = true;
        if (!marker) continue;
        size_t j = i + 1;
        while (j < t.size() && (t[j] == '/' || t[j] == ' ')) ++j;
        if (j >= t.size() || !isdigit((unsigned char)t[j])) continue;
        size_t k = j;
        while (k < t.size() && (isdigit((unsigned char)t[k]) || t[k] == '.')) ++k;
        const float v = (float)atof_safe(t.substr(j, k - j).c_str());
        // Sanity band: real max apertures live in [0.7, 45]. Rejects "1:2"
        // macro-ratio false hits and stray version digits.
        if (v >= 0.7f && v <= 45.f) { sp.maxAperture = v; break; }
    }
    return sp;
}

// 2 = both sides parsed and equal, 1 = one side unknown (neutral, cannot
// disagree), 0 = parsed and different.
static int cmpTri(float a, float b, float tol) {
    if (a <= 0.f || b <= 0.f) return 1;
    return (fabsf(a - b) <= tol * std::max(a, b)) ? 2 : 0;
}

// Rank one class of appendix (stabiliser OR motor/glass) against EXIF.
//   2 — exact set match: this is the same optical variant.
//   1 — candidate is PLAINER (a subset). Preferred over a superset: a profile
//       that claims stabilisation or motor optics the physical lens never had
//       is the more wrong of the two.
//   0 — candidate carries EXTRA or conflicting features. Still usable as the
//       last-resort alternative the product rule allows.
static int featureAffinity(const std::vector<std::string>& want,
                           const std::vector<std::string>& cand) {
    if (want == cand) return 2;
    const auto covers = [](const std::vector<std::string>& a,
                           const std::vector<std::string>& b) {
        for (const auto& t : b)
            if (std::find(a.begin(), a.end(), t) == a.end()) return false;
        return true;   // a ⊇ b
    };
    if (covers(want, cand)) return 1;
    return 0;
}

// Simple fuzzy score: count matching words.
static int fuzzyScore(const std::string& haystack, const std::string& needle) {
    if (needle.empty()) return 0;
    std::string h = lower(haystack.c_str());
    std::string n = lower(needle.c_str());
    if (h == n) return 1000;
    if (h.find(n) != std::string::npos) return 500;
    int score = 0;
    size_t pos = 0;
    while ((pos = n.find(' ', pos)) != std::string::npos) {
        std::string word = n.substr(0, pos);
        if (!word.empty() && h.find(word) != std::string::npos) score += 10;
        pos++;
    }
    std::string last = n.substr(n.rfind(' ') == std::string::npos ? 0 : n.rfind(' ') + 1);
    if (!last.empty() && h.find(last) != std::string::npos) score += 10;
    return score;
}

// "3:2" or "1.5" → float ratio.
static float parseAspect(const char* s) {
    if (!s || !*s) return 1.5f;
    const char* colon = strchr(s, ':');
    if (colon) {
        float a = (float)atof(s), b = (float)atof(colon + 1);
        return (b > 0.f) ? a / b : 1.5f;
    }
    float v = (float)atof(s);
    return v > 0.f ? v : 1.5f;
}

// ── Expat XML parser state ────────────────────────────────────────────────────

enum class Tag { None, Camera, Lens, Calibration };

struct ParseState {
    LfDatabase*      db;
    Tag              tag     = Tag::None;
    std::string      charBuf;
    LfCameraProfile  cam;
    LfLensProfile    lens;
    bool             inCalib = false;
};

static const char* attr(const char** atts, const char* name) {
    for (int i = 0; atts[i]; i += 2)
        if (strcmp(atts[i], name) == 0) return atts[i + 1];
    return nullptr;
}

static void XMLCALL startElement(void* userData, const char* name, const char** atts) {
    auto* ps = static_cast<ParseState*>(userData);
    ps->charBuf.clear();

    if (strcmp(name, "camera") == 0) {
        ps->tag = Tag::Camera;
        ps->cam = {};
    } else if (strcmp(name, "lens") == 0) {
        ps->tag = Tag::Lens;
        ps->lens = {};
    } else if (strcmp(name, "calibration") == 0) {
        ps->inCalib = true;
    } else if (ps->inCalib && ps->tag == Tag::Lens) {
        if (strcmp(name, "distortion") == 0) {
            const char* model = attr(atts, "model");
            const float focal = atof_safe(attr(atts, "focal"));
            const char* rf    = attr(atts, "real-focal");
            if (model && strcmp(model, "ptlens") == 0) {
                LfDistEntry e{};
                e.focal     = focal;
                e.realFocal = rf ? atof_safe(rf) : focal;   // absent → nominal
                e.poly3     = false;
                e.a         = atof_safe(attr(atts, "a"));
                e.b         = atof_safe(attr(atts, "b"));
                e.c         = atof_safe(attr(atts, "c"));
                ps->lens.dist.push_back(e);
            } else if (model && strcmp(model, "poly3") == 0) {
                LfDistEntry e{};
                e.focal     = focal;
                e.realFocal = rf ? atof_safe(rf) : focal;
                e.poly3     = true;
                e.a         = atof_safe(attr(atts, "k1"));  // k1 stored in .a
                ps->lens.dist.push_back(e);
            }
        } else if (strcmp(name, "vignetting") == 0) {
            const char* model = attr(atts, "model");
            if (model && strcmp(model, "pa") == 0) {
                LfVigEntry e;
                e.focal    = atof_safe(attr(atts, "focal"));
                e.aperture = atof_safe(attr(atts, "aperture"));
                e.distance = atof_safe(attr(atts, "distance"));
                e.k1 = atof_safe(attr(atts, "k1"));
                e.k2 = atof_safe(attr(atts, "k2"));
                e.k3 = atof_safe(attr(atts, "k3"));
                ps->lens.vig.push_back(e);
            }
        } else if (strcmp(name, "tca") == 0) {
            const char* model = attr(atts, "model");
            if (model && strcmp(model, "poly3") == 0) {
                LfTcaEntry e{};
                e.focal = atof_safe(attr(atts, "focal"));
                // Absent attributes default to the identity: v=1, b=c=0.
                const char* vr = attr(atts, "vr"); e.vr = vr ? atof_safe(vr) : 1.0f;
                const char* vb = attr(atts, "vb"); e.vb = vb ? atof_safe(vb) : 1.0f;
                e.cr = atof_safe(attr(atts, "cr"));
                e.cb = atof_safe(attr(atts, "cb"));
                e.br = atof_safe(attr(atts, "br"));
                e.bb = atof_safe(attr(atts, "bb"));
                ps->lens.tca.push_back(e);
            }
        }
    }
}

static void XMLCALL endElement(void* userData, const char* name) {
    auto* ps = static_cast<ParseState*>(userData);
    std::string text = ps->charBuf;
    ps->charBuf.clear();

    if (strcmp(name, "calibration") == 0) {
        ps->inCalib = false;
        return;
    }

    if (ps->tag == Tag::Camera) {
        if      (strcmp(name, "maker")      == 0) ps->cam.maker      = text;
        else if (strcmp(name, "model")      == 0) {
            // First <model> = the technical id (e.g. "ILCE-7M2"); a following
            // <model lang="…"> is the marketing name (e.g. "Alpha 7 II"). Keep
            // both so the picker can be searched by either.
            if (ps->cam.model.empty()) ps->cam.model = text;
            else if (ps->cam.alias.empty() && text != ps->cam.model) ps->cam.alias = text;
        }
        else if (strcmp(name, "mount")      == 0) ps->cam.mount      = text;
        else if (strcmp(name, "cropfactor") == 0) ps->cam.cropFactor = atof_safe(text.c_str());
        else if (strcmp(name, "camera")     == 0) {
            if (!ps->cam.maker.empty() && !ps->cam.model.empty())
                ps->db->cameras.push_back(ps->cam);
            ps->cam = {};
            ps->tag = Tag::None;
        }
    } else if (ps->tag == Tag::Lens) {
        if      (strcmp(name, "maker") == 0) ps->lens.maker = text;
        else if (strcmp(name, "model") == 0 && ps->lens.model.empty()) ps->lens.model = text;
        else if (strcmp(name, "mount") == 0 && ps->lens.mount.empty()) ps->lens.mount = text;
        else if (strcmp(name, "cropfactor")   == 0) ps->lens.cropFactor = atof_safe(text.c_str());
        else if (strcmp(name, "aspect-ratio") == 0) ps->lens.aspect     = parseAspect(text.c_str());
        else if (strcmp(name, "lens") == 0) {
            if (!ps->lens.model.empty())
                ps->db->lenses.push_back(ps->lens);
            ps->lens = {};
            ps->tag  = Tag::None;
            ps->inCalib = false;
        }
    }
}

static void XMLCALL charData(void* userData, const char* s, int len) {
    auto* ps = static_cast<ParseState*>(userData);
    ps->charBuf.append(s, (size_t)len);
}

static bool parseFile(LfDatabase& db, const char* path) {
    FILE* f = fopen(path, "r");
    if (!f) return false;

    XML_Parser p = XML_ParserCreate(nullptr);
    ParseState ps;
    ps.db = &db;
    XML_SetUserData(p, &ps);
    XML_SetElementHandler(p, startElement, endElement);
    XML_SetCharacterDataHandler(p, charData);

    char buf[4096];
    bool ok = true;
    int done;
    do {
        size_t n = fread(buf, 1, sizeof(buf), f);
        done = (n < sizeof(buf));
        if (XML_Parse(p, buf, (int)n, done) == XML_STATUS_ERROR) {
            LOGE("XML parse error in %s: %s", path,
                 XML_ErrorString(XML_GetErrorCode(p)));
            ok = false;
            break;
        }
    } while (!done);

    XML_ParserFree(p);
    fclose(f);
    return ok;
}

// ── Public API: database + matching ──────────────────────────────────────────

int lfa_load_database(LfDatabase& db, const char* dbDir) {
    DIR* dir = opendir(dbDir);
    if (!dir) { LOGE("Cannot open db dir: %s", dbDir); return 0; }

    int count = 0;
    struct dirent* ent;
    while ((ent = readdir(dir))) {
        const char* n = ent->d_name;
        size_t len = strlen(n);
        if (len > 4 && strcmp(n + len - 4, ".xml") == 0) {
            std::string path = std::string(dbDir) + "/" + n;
            if (parseFile(db, path.c_str())) count++;
        }
    }
    closedir(dir);
    LOGI("Loaded %d XML files; %zu cameras, %zu lenses",
         count, db.cameras.size(), db.lenses.size());
    return count;
}

const LfDatabase* lfa_cached_database(const char* dbDir) {
    static std::mutex mu;
    static LfDatabase db;
    static std::string loadedDir;
    static bool loadOk = false;
    if (!dbDir || !*dbDir) return nullptr;
    std::lock_guard<std::mutex> lk(mu);
    if (loadedDir != dbDir) {
        db = LfDatabase{};
        loadOk = lfa_load_database(db, dbDir) > 0;
        loadedDir = dbDir;
    }
    return loadOk ? &db : nullptr;
}

const LfCameraProfile* lfa_find_camera(const LfDatabase& db,
                                        const char* maker, const char* model) {
    int best = 0;
    const LfCameraProfile* found = nullptr;
    for (const auto& c : db.cameras) {
        int s = fuzzyScore(c.model, model ? model : "") +
                fuzzyScore(c.maker, maker ? maker : "");
        if (s > best) { best = s; found = &c; }
    }
    return found;
}

const LfLensProfile* lfa_find_lens(const LfDatabase& db,
                                    const LfCameraProfile* cam,
                                    const char* lensMaker, const char* lensModel,
                                    float /*focalLength*/) {
    int best = 0;
    const LfLensProfile* found = nullptr;
    for (const auto& l : db.lenses) {
        // Mount compatibility: prefer lenses whose mount matches camera mount
        int mountBonus = (cam && !l.mount.empty() && l.mount == cam->mount) ? 100 : 0;
        int s = fuzzyScore(l.model, lensModel ? lensModel : "") +
                fuzzyScore(l.maker, lensMaker ? lensMaker : "") +
                mountBonus;
        if (s > best) { best = s; found = &l; }
    }
    return (best > 0) ? found : nullptr;
}

// ── User-specific adapted/chipped lens remaps ────────────────────────────────
// Mirrors the EXIF watermark's correctLensModel(): certain lens IDs reported
// by the body are known to be wrong for THIS user's kit, so the profile lookup
// substitutes the physically-correct (or optically-equivalent) DB model.
// Keyed on the normalised EXIF lens string; returns the exact DB model to use
// instead, or nullptr for no remap.
static const char* remapSpecialLens(const std::string& normLens) {
    // Custom-chipped Sigma reporting itself as "EF28mm f/2.8" (shoots down to
    // f/1.8 — impossible for the real EF 28/2.8). The physical lens is the
    // Sigma 28mm f/1.8 High-Speed Wide Aspherical II — now a dedicated DB
    // profile (zz-pending-upstream.xml, duplicated from the EX DG calibration).
    // Resolve the chipped EXIF identity to that profile, and NEVER apply the
    // Canon EF 28mm f/2.8 profile to these frames (nobody uses that lens here).
    if (normLens == "ef28mmf28" || normLens == "canonef28mmf28")
        return "Sigma 28mm f/1.8 High-Speed Wide Aspherical II";
    // EF 100mm f/2 USM has no Lensfun calibration, and the numeric-token
    // fallback would false-match it to the EF 100mm f/2.8 Macro. Its optical
    // construction is the EF 85mm f/1.8 USM family — use that profile.
    // (Exclude f/2.8 strings so the real Macro keeps its own profile.)
    if (normLens.find("ef100mmf2") != std::string::npos &&
        normLens.find("ef100mmf28") == std::string::npos)
        return "Canon EF 85mm f/1.8 USM";
    // Tamron SP AF 20-40mm f/2.7-3.5 Aspherical (IF) — Canon EOS 6D reports
    // this lens as "21-39mm" via PROP_LENS_NAME (lens_focal_min=21,
    // lens_focal_max=39 rounded by the Canon EF protocol). The physically
    // correct lens uses the Tamron E 20-40mm F2.8 A062 calibration profile
    // registered under the SP AF 20-40mm name in our DB.
    if (normLens == "2139mm")
        return "Tamron SP AF 20-40mm f/2.7-3.5 Aspherical (IF)";
    return nullptr;
}

// ── Lens matching: ONE scorer, three callers ─────────────────────────────────
//
// Candidates are ranked by a STRICT LEXICOGRAPHIC ordering of the identity
// criteria, most significant first:
//
//     1. sensor size format (crop factor)   ← most important
//     2. lens brand
//     3. lens focal range
//     4. maximum aperture
//     5. stabiliser appendix    (IS VR OS OSS VC OIS)
//     6. motor / glass appendix (USM STM HSM PZD APO ZA SSM SAM)
//
// The weights are decade-separated so a criterion can NEVER be outvoted by the
// sum of all lower ones. That is what makes the documented fallback — "if
// unavailable, same method but ignore aperture and both appendices" — automatic
// rather than a literal second pass: a candidate agreeing on format + brand +
// range but differing in aperture or appendix always outranks one that nails
// aperture and appendix on the wrong sensor format.
//
// An ADDITIVE scheme with mismatch penalties cannot express that. Give crop
// +300/-200 and let maker(200) + containment(150) + focal(120) + aperture(80) +
// mount(50) + appendices(40n) accumulate, and up to ~640 points of
// lower-priority signal outvotes the 500-point crop delta — which is exactly
// the mis-selection this ordering exists to prevent.
//
// Above all six sits the NAME tier, which answers the prior question of whether
// this is even the same lens family; the criteria order the survivors.
//
// Why format leads: 95 DB models are listed more than once, differing ONLY by
// <cropfactor> — "Canon EF 24-105mm f/4L IS USM" exists at 1.0 and 1.611,
// "Canon EF 35mm f/2 IS USM" at 1.005 and 1.613. Correction coefficients are
// rescaled by that crop (see lfa_correct_rgba_f16), so picking the APS-C
// calibration for a full-frame body warps geometry.
//
// One scorer, not three: lfa_match_strict (Stage A import + desktop razbatch),
// lfa_rank_lenses (the UI shortlist) and lfa_score_one (the "why this?"
// diagnostics) all call scoreLensAgainst(). A second implementation anywhere —
// in Kotlin for the UI, say — would drift from the one Stage A actually applies,
// and the user would be shown a different answer than the pixels got.
namespace {

// Everything derived from the EXIF side, computed ONCE per query rather than
// per candidate.
struct LensQuery {
    // The RAW EXIF string is kept alongside the normalised keys because
    // fuzzyScore counts WORD overlap — it splits the needle on spaces, which
    // normKey has already stripped. Passing the normalised form silently scores
    // 0 for everything and collapses the numeric name tier from 2 to 1, which
    // in turn fails the confidence gate. (Caught by the "Helios 44-2 58mm f/2"
    // case in the harness after exactly that slip.)
    std::string rawModel;
    std::string wantLens, wantBase, wantLensMak, wantCamMak;
    std::vector<std::string> nums, wantStab, wantMotor;
    LensSpec spec;
    float       camCrop  = 1.0f;
    std::string camMount;
    bool        adapted  = false;
};

LensQuery buildLensQuery(const LfCameraProfile* cam, const char* camMakerHint,
                         const char* lensMaker, const char* lensModel,
                         bool adaptedMode) {
    LensQuery q;
    q.rawModel    = lensModel ? lensModel : "";
    q.wantLens    = normKey(lensModel);
    q.wantBase    = baseKey(lensModel);
    q.nums        = numericTokens(lensModel);
    q.wantStab    = featureTokensOf(lensModel, 0);
    q.wantMotor   = featureTokensOf(lensModel, 1);
    q.spec        = parseLensSpec(lensModel);
    q.wantLensMak = normKey(lensMaker);
    q.wantCamMak  = normKey(camMakerHint);
    q.adapted     = adaptedMode;
    if (cam) {
        q.camCrop  = (cam->cropFactor > 0.f) ? cam->cropFactor : 1.0f;
        q.camMount = cam->mount;
    }
    return q;
}

LfaLensScore scoreLensAgainst(const LensQuery& q, const LfLensProfile& l) {
    LfaLensScore sc;
    const std::string k  = normKey(l.model.c_str());
    const std::string bk = baseKey(l.model.c_str());

    // ── Name tier: is this the same lens family at all? ─────────────────────
    if (k == q.wantLens) sc.nameTier = 5;
    // Containment on the feature-stripped keys, DIRECTIONAL. A DB name that
    // fully contains the EXIF name is the more specific match and outranks a
    // generic fragment that merely sits inside it — otherwise the bare
    // "35mm f/2" entry (a Voigtlander, Nikon Z mount) ties with
    // "Canon EF 35mm f/2".
    else if (!q.wantBase.empty() && bk.find(q.wantBase) != std::string::npos) sc.nameTier = 4;
    else if (!bk.empty() && q.wantBase.find(bk) != std::string::npos)         sc.nameTier = 3;
    else if (!q.nums.empty()) {
        bool allNums = true;
        for (const auto& n : q.nums)
            if (k.find(n) == std::string::npos) { allNums = false; break; }
        // Weakest tier: every numeric token of the EXIF string appears somewhere
        // in the candidate. Only trusted with corroboration (see the gate in
        // classifyConfidence).
        if (allNums) sc.nameTier = fuzzyScore(l.model, q.rawModel) > 0 ? 2 : 1;
    }
    if (sc.nameTier == 0) return sc;   // total stays 0 → not a candidate

    // ── 1. Sensor size format ──────────────────────────────────────────────
    // lensfun semantics: a lens's cropfactor is the SMALLEST format its
    // calibration covers. Equal format is ideal; a calibration for a LARGER
    // format still corrects a smaller sensor (the coefficients rescale and we
    // only use the inner field); the reverse — an APS-C calibration on a bigger
    // sensor — extrapolates beyond what was measured and bends the geometry.
    // NOTE: numerically-closest is therefore NOT the rule. A 1.611 calibration
    // is nearer to a 1.525 body than 1.0 is, yet 1.0 is the correct choice.
    if (l.cropFactor <= 0.f)                                       sc.fmt = 1;  // unknown
    else if (fabsf(l.cropFactor - q.camCrop) <= 0.05f * q.camCrop)  sc.fmt = 2;  // same format
    else if (l.cropFactor < q.camCrop)                             sc.fmt = 1;  // covers more
    else                                                           sc.fmt = 0;  // too small

    // Mount is a hard physical compatibility fact, so it rides just under
    // format rather than below brand. In adapted mode it is dropped entirely:
    // a manual lens on a dumb adapter cannot report a matching mount, and
    // penalising that would hide every profile the user actually wants.
    sc.mount = q.adapted ? 1
             : ((!l.mount.empty() && l.mount == q.camMount) ? 1 : 0);

    // ── 2. Lens brand ──────────────────────────────────────────────────────
    // Strongest evidence is the maker's name inside the EXIF lens string
    // ("Sigma 18-125mm ..."). Next, an explicit EXIF lens-maker tag. Failing
    // both, the BODY maker is a weak nudge toward first-party glass — worth 1,
    // never 2, so it cannot outrank real evidence and cannot bury a
    // third-party lens that EXIF simply did not name.
    const std::string dbMak = normKey(l.maker.c_str());
    if (!dbMak.empty() && q.wantLens.find(dbMak) != std::string::npos)      sc.brand = 2;
    else if (!q.wantLensMak.empty() && !dbMak.empty() &&
             (dbMak.find(q.wantLensMak) != std::string::npos ||
              q.wantLensMak.find(dbMak) != std::string::npos))              sc.brand = 2;
    else if (q.wantLensMak.empty() && !dbMak.empty() && !q.wantCamMak.empty() &&
             (dbMak.find(q.wantCamMak) != std::string::npos ||
              q.wantCamMak.find(dbMak) != std::string::npos))               sc.brand = 1;

    // ── 3./4. Focal range and maximum aperture ─────────────────────────────
    // Parsed as STRUCTURED values, not numeric tokens: conflating focal with
    // aperture is what let "35mm f/2" match "35mm f/2.8". 3% tolerance absorbs
    // marketing rounding (a "24-105" calibrating as 24.0-104.6) without letting
    // 35 pass for 40. cmpTri returns 1 (neutral) when either side is unknown,
    // so a missing value never penalises.
    const LensSpec cs = parseLensSpec(l.model.c_str());
    sc.range    = std::min(cmpTri(q.spec.minFocal, cs.minFocal, 0.03f),
                           cmpTri(q.spec.maxFocal, cs.maxFocal, 0.03f));
    sc.aperture = cmpTri(q.spec.maxAperture, cs.maxAperture, 0.03f);

    // ── 5./6. Appendices, as separate classes (stabiliser outranks motor) ───
    sc.stab  = featureAffinity(q.wantStab,  featureTokensOf(l.model.c_str(), 0));
    sc.motor = featureAffinity(q.wantMotor, featureTokensOf(l.model.c_str(), 1));

    // Crop-factor closeness, at the LOWEST significance of all: it only
    // separates candidates every other criterion left tied. Needed for the
    // degenerate case where NO candidate has a usable format (an APS-C-only
    // lens on a full-frame body) — each option extrapolates, so take the
    // least-bad. Also orders the "covers more" class nearest-first.
    sc.prox = 9;
    if (l.cropFactor > 0.f && q.camCrop > 0.f) {
        const int steps = (int)(fabsf(l.cropFactor - q.camCrop) / q.camCrop * 10.f + 0.5f);
        sc.prox = 9 - std::min(9, steps);
    }

    sc.total = (long long)sc.nameTier * 100000000LL
             + (long long)sc.fmt      *  10000000LL
             + (long long)sc.mount    *   1000000LL
             + (long long)sc.brand    *    100000LL
             + (long long)sc.range    *     10000LL
             + (long long)sc.aperture *      1000LL
             + (long long)sc.stab     *       100LL
             + (long long)sc.motor    *        10LL
             + (long long)sc.prox     *         1LL;
    return sc;
}

// The gate and the confidence bands are the same decision, so they live
// together: NONE is exactly "fails the strict gate", and the product rule is
// that only HIGH may be applied without asking.
LfaConfidence classifyConfidence(const LfaLensScore& sc) {
    if (sc.nameTier == 0) return LFA_CONF_NONE;
    // Numeric-only tiers are never trusted alone — every "35mm f/2" in the DB
    // shares those tokens. Require a usable sensor format AND corroboration
    // from brand/range/aperture before admitting them at all.
    if (sc.nameTier < 3) {
        const int corroboration = sc.brand + sc.range + sc.aperture;
        if (!(sc.nameTier == 2 && sc.fmt >= 1 && corroboration >= 3))
            return LFA_CONF_NONE;
        return LFA_CONF_LOW;
    }
    // Name is specific, sensor format is exact, and the optical numbers agree:
    // safe to apply silently.
    // `brand >= 1` is what stops a SHORT, brandless EXIF string from claiming
    // HIGH just because it happens to be a substring of some DB name: a bare
    // "58mm f/2" sits inside "Carl Zeiss Jena Biotar 58mm f/2" and reaches
    // nameTier 4, but there are dozens of 58mm f/2 lenses and nothing here ties
    // this frame to Zeiss. A body-maker nudge (1) or a named maker (2) is
    // enough; both hold for ordinary first- and third-party glass.
    if (sc.nameTier >= 4 && sc.fmt == 2 && sc.brand >= 1 && sc.range == 2 &&
        sc.aperture >= 1 && sc.stab >= 1 && sc.motor >= 1)
        return LFA_CONF_HIGH;
    // Recognisable and usable, but something is approximate — a generic-fragment
    // name, a format we can only extrapolate, a differing appendix. Worth
    // offering, not worth applying unasked.
    if (sc.fmt >= 1 && sc.range >= 1) return LFA_CONF_MEDIUM;
    return LFA_CONF_LOW;
}

}  // namespace

int lfa_rank_lenses(const LfDatabase& db,
                    const LfCameraProfile* cam,
                    const char* lensMaker, const char* lensModel,
                    bool adaptedMode,
                    LfaLensCandidate* out, int maxOut) {
    if (!out || maxOut <= 0 || !lensModel || !*lensModel) return 0;

    // Honour the curated remap before ranking, so the shortlist the user sees
    // is the same identity Stage A would correct with.
    std::string effLens = lensModel;
    if (const char* remap = remapSpecialLens(normKey(lensModel))) {
        effLens = remap;
        lensMaker = "";
    }
    const LensQuery q = buildLensQuery(cam, cam ? cam->maker.c_str() : "",
                                       lensMaker, effLens.c_str(), adaptedMode);

    std::vector<LfaLensCandidate> all;
    all.reserve(64);
    for (const auto& l : db.lenses) {
        LfaLensScore sc = scoreLensAgainst(q, l);
        if (sc.nameTier == 0) continue;
        const LfaConfidence conf = classifyConfidence(sc);
        if (conf == LFA_CONF_NONE) continue;
        all.push_back({ &l, sc, conf });
    }
    // Stable ordering: score first, then the SHORTER (less decorated) name, then
    // the model string — so the same DB always produces the same shortlist.
    std::sort(all.begin(), all.end(),
              [](const LfaLensCandidate& a, const LfaLensCandidate& b) {
                  if (a.score.total != b.score.total) return a.score.total > b.score.total;
                  const size_t la = a.lens->model.size(), lb = b.lens->model.size();
                  if (la != lb) return la < lb;
                  return a.lens->model < b.lens->model;
              });
    // One row per MODEL NAME. The DB lists a separate <lens> per mount and per
    // calibration format, so without this the shortlist repeats
    // "Canon EF 35mm f/2 IS USM" two or three times and the user is asked to
    // choose between entries that look identical. The sort above already put the
    // right-format row first, so keeping the first occurrence keeps the best
    // calibration; the alternatives it hides are ones we have already judged
    // worse for this body.
    int n = 0;
    for (const auto& c : all) {
        bool dup = false;
        for (int i = 0; i < n; ++i)
            if (out[i].lens->model == c.lens->model) { dup = true; break; }
        if (dup) continue;
        out[n++] = c;
        if (n >= maxOut) break;
    }
    return n;
}

LfaLensCandidate lfa_score_one(const LfDatabase& /*db*/,
                               const LfCameraProfile* cam,
                               const char* lensMaker, const char* lensModel,
                               const LfLensProfile* candidate,
                               bool adaptedMode) {
    LfaLensCandidate c;
    if (!candidate || !lensModel) return c;
    const LensQuery q = buildLensQuery(cam, cam ? cam->maker.c_str() : "",
                                       lensMaker, lensModel, adaptedMode);
    c.lens       = candidate;
    c.score      = scoreLensAgainst(q, *candidate);
    c.confidence = classifyConfidence(c.score);
    return c;
}

LfaMatch lfa_match_strict(const LfDatabase& db,
                          const char* camMaker, const char* camModel,
                          const char* lensMaker, const char* lensModel) {
    LfaMatch m;
    // Only the CAMERA identity is mandatory. An empty lens string must NOT
    // abort the whole match: adapted/manual lenses on a dumb adapter report no
    // lens in EXIF, and the workspace UI still needs the resolved body to
    // populate its "Camera body" field (the user then picks the lens by hand).
    // The lens phase below is simply skipped, leaving m.lens null — and since
    // ok() requires BOTH, the correction path is unchanged (never guesses).
    if (!camModel || !*camModel) return m;
    const bool haveLens = (lensModel != nullptr && *lensModel != '\0');

    // Apply the special-case remap BEFORE any matching so the UI probe, the
    // import decode, and the log all agree on the substituted lens.
    std::string effLens = haveLens ? lensModel : "";
    if (haveLens) {
        if (const char* remap = remapSpecialLens(normKey(lensModel))) {
            LOGI("lfa_match_strict: special-case remap '%s' -> '%s'", lensModel, remap);
            effLens = remap;
            lensMaker = "";   // maker hint no longer applies to the substitute
        }
        lensModel = effLens.c_str();
    }

    // ── Camera: containment of normalised model strings (either way). The DB
    //   models usually embed the maker ("Canon EOS 6D"), EXIF models often do
    //   too — normKey containment covers both spellings.
    const std::string wantCam = normKey(camModel);
    const std::string wantMak = normKey(camMaker);
    int bestCamScore = 0;
    for (const auto& c : db.cameras) {
        const std::string k = normKey(c.model.c_str());
        int s = 0;
        if (k == wantCam) s = 1000;
        else if (!wantCam.empty() &&
                 (k.find(wantCam) != std::string::npos ||
                  wantCam.find(k) != std::string::npos)) s = 500;
        if (s > 0 && !wantMak.empty() &&
            normKey(c.maker.c_str()).find(wantMak) == std::string::npos &&
            wantMak.find(normKey(c.maker.c_str())) == std::string::npos)
            s -= 200;   // maker mismatch demotes but doesn't kill (rebadges)
        // Prefer the longer (more specific) DB model on equal score.
        if (s > bestCamScore ||
            (s == bestCamScore && m.cam && k.size() > normKey(m.cam->model.c_str()).size())) {
            bestCamScore = s;
            m.cam = &c;
        }
    }
    if (bestCamScore < 500) { m.cam = nullptr; return m; }

    // No lens string to work with — return with the camera resolved and the
    // lens left null (see the haveLens note above).
    if (!haveLens) return m;

    // Rank through the shared scorer and take the best. Note this is NOT
    // adapted mode: silent import correction stays mount-strict, because the
    // adapted-lens relaxation is a deliberate user choice, not a default.
    LfaLensCandidate best[1];
    const int n = lfa_rank_lenses(db, m.cam, lensMaker, lensModel,
                                 /*adaptedMode=*/false, best, 1);
    if (n > 0) {
        m.lens       = best[0].lens;
        m.confidence = best[0].confidence;
        m.score      = best[0].score.total;
        const LfaLensScore& sc = best[0].score;
        LOGI("lfa_match_strict: lens '%s' -> '%s' conf=%d "
             "[name %d fmt %d mnt %d brand %d range %d apert %d stab %d motor %d] "
             "crop %.3f vs body %.3f",
             lensModel, m.lens->model.c_str(), (int)m.confidence,
             sc.nameTier, sc.fmt, sc.mount, sc.brand, sc.range, sc.aperture,
             sc.stab, sc.motor, m.lens->cropFactor,
             m.cam->cropFactor > 0.f ? m.cam->cropFactor : 1.0f);
    } else {
        LOGI("lfa_match_strict: lens '%s' -> NO CONFIDENT MATCH", lensModel);
    }
    return m;
}

// ── Faithful lensfun correction on Stage A's sRGB FP16 RGBA buffer ───────────

namespace {

// sRGB transfer pair (matches stage_a's linearToSrgb).
inline float srgbToLin(float v) {
    return v <= 0.04045f ? v / 12.92f : powf((v + 0.055f) / 1.055f, 2.4f);
}
inline float linToSrgb(float v) {
    return v <= 0.0031308f ? v * 12.92f : 1.055f * powf(v, 1.0f / 2.4f) - 0.055f;
}

// Linear interpolation of distortion entries by focal.
// Returns interpolated raw (unscaled) coefficients + realFocal + poly3 flag.
bool interpDistRaw(const std::vector<LfDistEntry>& es, float focal,
                   float& a, float& b, float& c, float& realFocal, bool& poly3) {
    if (es.empty()) return false;
    std::vector<const LfDistEntry*> sorted;
    for (const auto& e : es) sorted.push_back(&e);
    std::sort(sorted.begin(), sorted.end(),
              [](const LfDistEntry* x, const LfDistEntry* y){ return x->focal < y->focal; });
    const LfDistEntry* lo = sorted.front();
    const LfDistEntry* hi = sorted.back();
    for (const auto* e : sorted) {
        if (e->focal <= focal) lo = e;
        if (e->focal >= focal) { hi = e; break; }
    }
    poly3 = lo->poly3;   // mixed-model lenses are rare; use lower entry's model
    if (lo == hi || hi->focal <= lo->focal || lo->poly3 != hi->poly3) {
        a = lo->a; b = lo->b; c = lo->c;
        realFocal = lo->realFocal > 0.f ? lo->realFocal : focal;
        return true;
    }
    const float t = (focal - lo->focal) / (hi->focal - lo->focal);
    a = lo->a + t * (hi->a - lo->a);
    b = lo->b + t * (hi->b - lo->b);
    c = lo->c + t * (hi->c - lo->c);
    const float rf0 = lo->realFocal > 0.f ? lo->realFocal : lo->focal;
    const float rf1 = hi->realFocal > 0.f ? hi->realFocal : hi->focal;
    realFocal = rf0 + t * (rf1 - rf0);
    return true;
}

bool interpTcaRaw(const std::vector<LfTcaEntry>& es, float focal,
                  float& vr, float& vb, float& cr, float& cb, float& br, float& bb) {
    if (es.empty()) return false;
    std::vector<const LfTcaEntry*> sorted;
    for (const auto& e : es) sorted.push_back(&e);
    std::sort(sorted.begin(), sorted.end(),
              [](const LfTcaEntry* x, const LfTcaEntry* y){ return x->focal < y->focal; });
    const LfTcaEntry* lo = sorted.front();
    const LfTcaEntry* hi = sorted.back();
    for (const auto* e : sorted) {
        if (e->focal <= focal) lo = e;
        if (e->focal >= focal) { hi = e; break; }
    }
    if (lo == hi || hi->focal <= lo->focal) {
        vr = lo->vr; vb = lo->vb; cr = lo->cr; cb = lo->cb; br = lo->br; bb = lo->bb;
        return true;
    }
    const float t = (focal - lo->focal) / (hi->focal - lo->focal);
    vr = lo->vr + t * (hi->vr - lo->vr);  vb = lo->vb + t * (hi->vb - lo->vb);
    cr = lo->cr + t * (hi->cr - lo->cr);  cb = lo->cb + t * (hi->cb - lo->cb);
    br = lo->br + t * (hi->br - lo->br);  bb = lo->bb + t * (hi->bb - lo->bb);
    return true;
}

// Inverse-distance-squared weighting over all vignetting entries.
bool interpVigRaw(const std::vector<LfVigEntry>& es, float focal, float aperture,
                  float& k1, float& k2, float& k3) {
    if (es.empty()) return false;
    float wSum = 0.f, k1s = 0.f, k2s = 0.f, k3s = 0.f;
    for (const auto& e : es) {
        const float df = (e.focal > 0.f && focal > 0.f)
                       ? fabsf(e.focal - focal) / focal : 0.f;
        const float da = (aperture > 0.f && e.aperture > 0.f)
                       ? fabsf(e.aperture - aperture) / aperture : 0.f;
        const float d2 = df * df * 4.f + da * da + 1e-6f;
        const float w  = 1.f / d2;
        wSum += w; k1s += w * e.k1; k2s += w * e.k2; k3s += w * e.k3;
    }
    k1 = k1s / wSum; k2 = k2s / wSum; k3 = k3s / wSum;
    return true;
}

struct GeomChain {
    // distortion (rescaled): Rd = Ru*(a_*Ru³ + b_*Ru² + c_*Ru + 1); identity when !hasDist
    bool  hasDist = false;
    float a_ = 0, b_ = 0, c_ = 0;
    // TCA (rescaled): Rd_ch = Ru*(b*Ru² + c*Ru + v); identity when !hasTca
    bool  hasTca = false;
    float tvr = 1, tvb = 1, tcr = 0, tcb = 0, tbr = 0, tbb = 0;

    // target normalised coord → per-channel source normalised coords
    inline void map(float nx, float ny,
                    float& rx, float& ry, float& gx, float& gy,
                    float& bx, float& by) const {
        float x = nx, y = ny;
        if (hasDist) {
            const float ru2 = x * x + y * y;
            const float r   = sqrtf(ru2);
            const float poly = a_ * ru2 * r + b_ * ru2 + c_ * r + 1.f;
            x *= poly; y *= poly;
        }
        gx = x; gy = y;
        if (hasTca) {
            const float rd2 = x * x + y * y;
            const float rd  = sqrtf(rd2);
            const float pr  = tbr * rd2 + tcr * rd + tvr;
            const float pb  = tbb * rd2 + tcb * rd + tvb;
            rx = x * pr; ry = y * pr;
            bx = x * pb; by = y * pb;
        } else {
            rx = x; ry = y; bx = x; by = y;
        }
    }
};

} // namespace

static thread_local LfaReport g_lfaReport;
const LfaReport& lfa_last_report() { return g_lfaReport; }
int lfa_report_string(char* out, int cap) {
    const LfaReport& r = g_lfaReport;
    const char* vig = r.vig == 2 ? "generic-cos4" : (r.vig == 1 ? "profile" : "none");
    if (!r.ran)
        return snprintf(out, (size_t)cap, "lens=NONE (%s)", r.reason && *r.reason ? r.reason : "not run");
    return snprintf(out, (size_t)cap,
        "lens='%s' body='%s' conf=%d fmt=%s(cal %.2f/body %.2f) f=%.1fmm f/%.1f "
        "dist=%s tca=%s vig=%s zoom=%.4f%s%s",
        r.lens, r.cam, r.confidence,
        (fabsf(r.calCrop - r.camCrop) < 0.05f) ? "match" : "MISMATCH", r.calCrop, r.camCrop,
        r.focalMm, r.aperture,
        r.dist ? "profile" : "none", r.tca ? "profile" : "none", vig, r.zoom,
        r.applied ? "" : " NOT-APPLIED:", r.applied ? "" : (r.reason ? r.reason : ""));
}

bool lfa_correct_rgba_f16(uint16_t* rgbaF16u, int W, int H,
                          const LfaMatch& match,
                          float focalMm, float apertureF,
                          const float* liftMap, int liftSide,
                          float liftTau) {
    g_lfaReport = LfaReport{};
    if (!match.ok() || W < 8 || H < 8) { g_lfaReport.reason = "no match / tiny image"; return false; }
    const LfLensProfile&   lens = *match.lens;
    const LfCameraProfile& cam  = *match.cam;
    g_lfaReport.ran = true;
    g_lfaReport.focalMm = focalMm; g_lfaReport.aperture = apertureF;
    g_lfaReport.confidence = (int)match.confidence;
    snprintf(g_lfaReport.cam,  sizeof g_lfaReport.cam,  "%s", cam.model.c_str());
    snprintf(g_lfaReport.lens, sizeof g_lfaReport.lens, "%s", lens.model.c_str());
    if (focalMm <= 0.f) { g_lfaReport.reason = "focal length unknown (set it in Lens Correction)"; return false; }

    const float camCrop  = cam.cropFactor > 0.f ? cam.cropFactor : 1.0f;
    const float calCrop  = lens.cropFactor > 0.f ? lens.cropFactor : camCrop;
    const float calAsp   = lens.aspect > 0.f ? lens.aspect : 1.5f;
    const float fullDiag = hypotf(36.f, 24.f);   // 43.2666 mm
    g_lfaReport.camCrop = camCrop; g_lfaReport.calCrop = calCrop;

    // ── Interpolate raw calibrations at this focal / aperture ────────────────
    float da = 0, dbc = 0, dc = 0, realFocal = focalMm; bool poly3 = false;
    const bool hasDistCal = interpDistRaw(lens.dist, focalMm, da, dbc, dc, realFocal, poly3);
    if (!hasDistCal) realFocal = focalMm;   // lensfun: RealFocal = Focal fallback

    float vr, vb, cr, cb, br, bb;
    const bool hasTcaCal = interpTcaRaw(lens.tca, focalMm, vr, vb, cr, cb, br, bb);

    float k1, k2, k3;
    const bool hasVigCal = interpVigRaw(lens.vig, focalMm, apertureF, k1, k2, k3);

    if (!hasDistCal && !hasTcaCal && !hasVigCal) return false;

    // ── Rescale coefficients (lensfun rescale_polynomial_coefficients) ───────
    GeomChain chain;
    {
        const float huginMmDist = fullDiag / calCrop / hypotf(calAsp, 1.f) / 2.f;
        const float sD = realFocal / huginMmDist;
        if (hasDistCal) {
            if (poly3) {
                const float d = 1.f - da;   // da holds k1
                if (fabsf(d) > 1e-6f) {
                    chain.b_ = da * (sD * sD) / (d * d * d);
                    chain.hasDist = chain.b_ != 0.f;
                }
            } else {
                const float d = 1.f - da - dbc - dc;
                if (fabsf(d) > 1e-6f) {
                    chain.a_ = da  * (sD * sD * sD) / (d * d * d * d);
                    chain.b_ = dbc * (sD * sD)      / (d * d * d);
                    chain.c_ = dc  * sD             / (d * d);
                    chain.hasDist = (chain.a_ != 0.f || chain.b_ != 0.f || chain.c_ != 0.f);
                }
            }
        }
        if (hasTcaCal) {
            chain.tvr = vr; chain.tvb = vb;
            chain.tcr = cr * sD;       chain.tcb = cb * sD;
            chain.tbr = br * sD * sD;  chain.tbb = bb * sD * sD;
            chain.hasTca = (vr != 1.f || vb != 1.f || cr != 0.f || cb != 0.f ||
                            br != 0.f || bb != 0.f);
        }
    }
    float vk1 = 0, vk2 = 0, vk3 = 0;
    bool doVig = false;
    // Generic fallback: many profiles (e.g. Canon EF 28-105mm f/3.5-4.5 II USM,
    // Sigma 28mm f/1.8 EX DG) carry distortion only — no <vignetting> at all —
    // so corners stayed dark with "correction applied" (owner report
    // 2026-09-04, adapted Sigma 28/1.8 on an A7 II using the Canon zoom's
    // profile). When the LENS HAS NO vignetting calibration whatsoever we
    // compensate natural (cos⁴) falloff instead: in this coordinate system
    // r = tan θ (mm-from-centre / focal), so cos⁴θ = 1/(1+r²)². Optical
    // vignetting shrinks as the lens stops down, so the gain is scaled by
    // s = clamp(2.8 / f-number, 0.5, 1) and capped at ×2.5 (~1.3 EV) — an
    // approximation, deliberately labelled vig=2 in the log. NOT used when the
    // profile has vignetting data that merely failed to interpolate at this
    // focal/aperture — that is the calibration's call, not ours.
    bool genericVig = false;
    float genericVigStrength = 0.f;
    if (hasVigCal) {
        const float huginMmVig = fullDiag / calCrop / 2.f;   // no aspect for vignetting
        const float sV = realFocal / huginMmVig;
        vk1 = k1 * sV * sV;
        vk2 = k2 * sV * sV * sV * sV;
        vk3 = k3 * sV * sV * sV * sV * sV * sV;
        doVig = (vk1 != 0.f || vk2 != 0.f || vk3 != 0.f);
    } else if (lens.vig.empty()) {
        const float fN = (apertureF > 0.5f) ? apertureF : 2.8f;   // unknown aperture → assume mid
        genericVigStrength = fminf(fmaxf(2.8f / fN, 0.5f), 1.0f);
        genericVig = true;
        doVig = true;
    }
    const bool doGeom = chain.hasDist || chain.hasTca;
    g_lfaReport.dist = chain.hasDist; g_lfaReport.tca = chain.hasTca;
    g_lfaReport.vig  = doVig ? (genericVig ? 2 : 1) : 0;
    if (!doVig && !doGeom) { g_lfaReport.reason = "profile has no usable calibration at this focal/aperture"; return false; }

    // ── Coordinate system (lensfun modifier.cpp) ─────────────────────────────
    // Width/Height are (pixels-1); NormScale uses the full pixel span.
    const float normScale = fullDiag / camCrop / hypotf((float)W, (float)H) / realFocal;
    const float cx = (float)(W - 1) * 0.5f;
    const float cy = (float)(H - 1) * 0.5f;

    __fp16* rgba = reinterpret_cast<__fp16*>(rgbaF16u);
    const int nThreads = (int)std::max(1u, std::min(8u, std::thread::hardware_concurrency()));

    // ── Pass 1: devignetting, in linear light, in place ──────────────────────
    if (doVig) {
        double liftSum = 0.0, cornerLiftSum = 0.0;
        long long liftCnt = 0, cornerLiftCnt = 0;
        std::mutex liftMu;
        // Corner-region accumulator: r² beyond 0.8 of the corner radius (0.64
        // in squared units) — the darkest, most-corrected area. Telemetry for
        // how aggressively the corners are being protected per frame.
        const float nxC = (float)(W - 1) * 0.5f * normScale;
        const float nyC = (float)(H - 1) * 0.5f * normScale;
        const float r2Corner = nxC * nxC + nyC * nyC;

        auto vigRows = [&](int y0, int y1) {
            double localSum = 0.0, localCornerSum = 0.0;
            long long localCnt = 0, localCornerCnt = 0;
            for (int y = y0; y < y1; ++y) {
                const float ny = ((float)y - cy) * normScale;
                __fp16* row = rgba + (size_t)y * W * 4;
                for (int x = 0; x < W; ++x) {
                    const float nx = ((float)x - cx) * normScale;
                    const float r2 = nx * nx + ny * ny;
                    const float r4 = r2 * r2;
                    float c;
                    if (genericVig) {
                        // Natural falloff cos⁴θ with r = tanθ → Cd = 1/(1+r²)²,
                        // blended toward 1 by strength, gain capped at ×2.5.
                        const float onePlus = 1.f + r2;
                        const float cd = 1.f / (onePlus * onePlus);
                        float gain = 1.f + genericVigStrength * (1.f / cd - 1.f);
                        if (gain > 2.5f) gain = 2.5f;
                        c = 1.f / gain;
                    } else {
                        c = 1.f + vk1 * r2 + vk2 * r4 + vk3 * r4 * r2;
                    }
                    if (c > 1e-3f && c != 1.f) {
                        const float g = 1.f / c;   // lensfun DeVignetting: ×(1/Cd)
                        // Zero-DCE guided attenuation: deep-shadow regions get
                        // a reduced optical gain so corner noise isn't boosted.
                        float gUse = g;
                        if (liftMap) {
                            const float aMean = lfaSampleLift(liftMap, liftSide,
                                                              (float)x / (float)(W - 1),
                                                              (float)y / (float)(H - 1));
                            const float m = 1.f - fminf(fmaxf(aMean / liftTau, 0.f), 1.f);
                            gUse = 1.f + (g - 1.f) * m;
                            localSum += aMean;
                            ++localCnt;
                            if (r2 > 0.64f * r2Corner) {
                                localCornerSum += aMean;
                                ++localCornerCnt;
                            }
                        }
                        __fp16* px = row + (size_t)x * 4;
                        for (int ch = 0; ch < 3; ++ch) {
                            float v = (float)px[ch];
                            v = linToSrgb(srgbToLin(v) * gUse);   // linear-light gain
                            px[ch] = (__fp16)fminf(fmaxf(v, 0.f), 1.f);
                        }
                    }
                }
            }
            std::lock_guard<std::mutex> lk(liftMu);
            liftSum += localSum;
            liftCnt += localCnt;
            cornerLiftSum += localCornerSum;
            cornerLiftCnt += localCornerCnt;
        };
        std::vector<std::thread> ts;
        const int chunk = (H + nThreads - 1) / nThreads;
        for (int t = 0; t < nThreads; ++t) {
            const int y0 = t * chunk, y1 = std::min(H, y0 + chunk);
            if (y0 < y1) ts.emplace_back(vigRows, y0, y1);
        }
        for (auto& t : ts) t.join();

        // Report adaptive state only when pixels were actually attenuated —
        // a non-null map whose pass touched nothing must not claim otherwise.
        if (liftMap && liftCnt > 0) {
            g_lfaReport.adaptive = true;
            g_lfaReport.liftMean = (float)(liftSum / (double)liftCnt);
            if (cornerLiftCnt > 0)
                g_lfaReport.cornerLiftMean = (float)(cornerLiftSum / (double)cornerLiftCnt);
        }
    }

    // ── Auto-scale: zoom in just enough that no target pixel samples outside
    //   the source (lensfun GetAutoScale equivalent, via binary search) ───────
    float zoom = 1.0f;
    if (doGeom) {
        auto allInside = [&](float z) -> bool {
            const int SAMPLES = 32;
            auto check = [&](float px, float py) -> bool {
                const float nx = (px - cx) * normScale / z;
                const float ny = (py - cy) * normScale / z;
                float rx, ry, gx, gy, bx, by;
                chain.map(nx, ny, rx, ry, gx, gy, bx, by);
                const float coords[6] = { rx, ry, gx, gy, bx, by };
                for (int i = 0; i < 6; i += 2) {
                    const float sx = coords[i]   / normScale + cx;
                    const float sy = coords[i+1] / normScale + cy;
                    if (sx < 0.f || sx > (float)(W - 1) || sy < 0.f || sy > (float)(H - 1))
                        return false;
                }
                return true;
            };
            for (int i = 0; i <= SAMPLES; ++i) {
                const float fx = (float)i / SAMPLES * (float)(W - 1);
                const float fy = (float)i / SAMPLES * (float)(H - 1);
                if (!check(fx, 0.f) || !check(fx, (float)(H - 1)) ||
                    !check(0.f, fy) || !check((float)(W - 1), fy)) return false;
            }
            return true;
        };
        if (!allInside(1.0f)) {
            float lo = 1.0f, hi = 1.8f;
            if (allInside(hi)) {
                for (int it = 0; it < 14; ++it) {
                    const float mid = (lo + hi) * 0.5f;
                    if (allInside(mid)) hi = mid; else lo = mid;
                }
                zoom = hi;
            } else {
                zoom = hi;   // pathological calibration; crop hard rather than border
            }
        }
    }

    // ── Pass 2: geometry (distortion + TCA), warp into a new buffer ──────────
    if (doGeom) {
        const size_t count = (size_t)W * H * 4;
        std::vector<uint16_t> outBuf(count);
        __fp16* out = reinterpret_cast<__fp16*>(outBuf.data());

        auto sampleCh = [&](float sx, float sy, int ch) -> float {
            sx = fminf(fmaxf(sx, 0.f), (float)(W - 1));
            sy = fminf(fmaxf(sy, 0.f), (float)(H - 1));
            const int x0 = (int)sx, y0 = (int)sy;
            const int x1 = std::min(x0 + 1, W - 1), y1 = std::min(y0 + 1, H - 1);
            const float fx = sx - x0, fy = sy - y0;
            const __fp16* p00 = rgba + ((size_t)y0 * W + x0) * 4;
            const __fp16* p10 = rgba + ((size_t)y0 * W + x1) * 4;
            const __fp16* p01 = rgba + ((size_t)y1 * W + x0) * 4;
            const __fp16* p11 = rgba + ((size_t)y1 * W + x1) * 4;
            return (1 - fx) * (1 - fy) * (float)p00[ch] + fx * (1 - fy) * (float)p10[ch]
                 + (1 - fx) * fy       * (float)p01[ch] + fx * fy       * (float)p11[ch];
        };

        auto warpRows = [&](int y0r, int y1r) {
            for (int y = y0r; y < y1r; ++y) {
                const float nyBase = ((float)y - cy) * normScale / zoom;
                __fp16* dstRow = out + (size_t)y * W * 4;
                for (int x = 0; x < W; ++x) {
                    const float nx = ((float)x - cx) * normScale / zoom;
                    float rx, ry, gx, gy, bx, by;
                    chain.map(nx, nyBase, rx, ry, gx, gy, bx, by);
                    const float srx = rx / normScale + cx, sry = ry / normScale + cy;
                    const float sgx = gx / normScale + cx, sgy = gy / normScale + cy;
                    const float sbx = bx / normScale + cx, sby = by / normScale + cy;
                    __fp16* dst = dstRow + (size_t)x * 4;
                    dst[0] = (__fp16)sampleCh(srx, sry, 0);
                    dst[1] = (__fp16)sampleCh(sgx, sgy, 1);
                    dst[2] = (__fp16)sampleCh(sbx, sby, 2);
                    dst[3] = (__fp16)sampleCh(sgx, sgy, 3);   // alpha follows G geometry
                }
            }
        };
        std::vector<std::thread> ts;
        const int chunk = (H + nThreads - 1) / nThreads;
        for (int t = 0; t < nThreads; ++t) {
            const int y0 = t * chunk, y1 = std::min(H, y0 + chunk);
            if (y0 < y1) ts.emplace_back(warpRows, y0, y1);
        }
        for (auto& t : ts) t.join();
        memcpy(rgbaF16u, outBuf.data(), count * sizeof(uint16_t));
    }

    LOGI("lfa_correct_rgba_f16: %s + %s | f=%.1fmm f/%.1f realF=%.1f "
         "dist=%d tca=%d vig=%d zoom=%.4f (%dx%d, %d threads) "
         "adaptive=%d lift=%.3f corner=%.3f",
         cam.model.c_str(), lens.model.c_str(), focalMm, apertureF, realFocal,
         chain.hasDist ? 1 : 0, chain.hasTca ? 1 : 0, doVig ? (genericVig ? 2 : 1) : 0, zoom, W, H, nThreads,
         g_lfaReport.adaptive ? 1 : 0, g_lfaReport.liftMean, g_lfaReport.cornerLiftMean);
    g_lfaReport.zoom = zoom; g_lfaReport.applied = true;
    return true;
}

// ── Legacy correction math (v2 decoder path — unchanged behaviour) ───────────

// Interpolate between two calibration entries by focal length.
static void interpDistLegacy(const std::vector<LfDistEntry>& entries, float focal,
                        float& a, float& b, float& c) {
    a = b = c = 0.0f;
    if (entries.empty()) return;
    if (entries.size() == 1) { a = entries[0].a; b = entries[0].b; c = entries[0].c; return; }

    size_t lo = 0, hi = entries.size() - 1;
    for (size_t i = 0; i < entries.size(); i++) {
        if (entries[i].focal <= focal) lo = i;
        if (entries[i].focal >= focal && entries[hi].focal > entries[lo].focal) { hi = i; break; }
    }
    if (lo == hi) { a = entries[lo].a; b = entries[lo].b; c = entries[lo].c; return; }

    float t = (focal - entries[lo].focal) / (entries[hi].focal - entries[lo].focal);
    a = entries[lo].a + t * (entries[hi].a - entries[lo].a);
    b = entries[lo].b + t * (entries[hi].b - entries[lo].b);
    c = entries[lo].c + t * (entries[hi].c - entries[lo].c);
}

static void interpVigLegacy(const std::vector<LfVigEntry>& entries, float focal, float aperture,
                       float& k1, float& k2, float& k3) {
    k1 = k2 = k3 = 0.0f;
    if (entries.empty()) return;

    float bestDist = 1e9f;
    const LfVigEntry* best = nullptr;
    for (const auto& e : entries) {
        float d = fabsf(e.focal - focal) * 0.5f + fabsf(e.aperture - aperture);
        if (d < bestDist) { bestDist = d; best = &e; }
    }
    if (best) { k1 = best->k1; k2 = best->k2; k3 = best->k3; }
}

static void interpTcaLegacy(const std::vector<LfTcaEntry>& entries, float focal,
                       float& br, float& vr, float& bb, float& vb) {
    br = bb = 0.0f; vr = vb = 1.0f;
    if (entries.empty()) return;
    if (entries.size() == 1) { br = entries[0].br; vr = entries[0].vr; bb = entries[0].bb; vb = entries[0].vb; return; }

    size_t lo = 0, hi = entries.size() - 1;
    for (size_t i = 0; i < entries.size(); i++) {
        if (entries[i].focal <= focal) lo = i;
        if (entries[i].focal >= focal) { hi = i; break; }
    }
    if (lo == hi) { br = entries[lo].br; vr = entries[lo].vr; bb = entries[lo].bb; vb = entries[lo].vb; return; }

    float t = (focal - entries[lo].focal) / (entries[hi].focal - entries[lo].focal);
    br = entries[lo].br + t * (entries[hi].br - entries[lo].br);
    vr = entries[lo].vr + t * (entries[hi].vr - entries[lo].vr);
    bb = entries[lo].bb + t * (entries[hi].bb - entries[lo].bb);
    vb = entries[lo].vb + t * (entries[hi].vb - entries[lo].vb);
}

// Bilinear sample of float RGB buffer (clamp-to-edge).
static void sampleRGB(const float* src, int w, int h, float u, float v,
                       float& R, float& G, float& B) {
    u = fmaxf(0.0f, fminf((float)(w - 1), u));
    v = fmaxf(0.0f, fminf((float)(h - 1), v));
    int x0 = (int)u, y0 = (int)v;
    int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
    float fx = u - x0, fy = v - y0;

    auto px = [&](int x, int y, int ch) { return src[(y * w + x) * 3 + ch]; };
    R = (1-fx)*(1-fy)*px(x0,y0,0) + fx*(1-fy)*px(x1,y0,0)
      + (1-fx)*fy    *px(x0,y1,0) + fx*fy    *px(x1,y1,0);
    G = (1-fx)*(1-fy)*px(x0,y0,1) + fx*(1-fy)*px(x1,y0,1)
      + (1-fx)*fy    *px(x0,y1,1) + fx*fy    *px(x1,y1,1);
    B = (1-fx)*(1-fy)*px(x0,y0,2) + fx*(1-fy)*px(x1,y0,2)
      + (1-fx)*fy    *px(x0,y1,2) + fx*fy    *px(x1,y1,2);
}

void lfa_apply_corrections(
    float* pixels, int width, int height,
    const LfLensProfile* lens,
    const LfCameraProfile* /*cam*/,
    float focalLength, float aperture,
    float distStrength, float vigStrength, float caStrength)
{
    if (!lens || (distStrength == 0.0f && vigStrength == 0.0f && caStrength == 0.0f)) return;

    const int N = width * height * 3;

    float da, db, dc;
    interpDistLegacy(lens->dist, focalLength, da, db, dc);
    float vk1, vk2, vk3;
    interpVigLegacy(lens->vig, focalLength, aperture, vk1, vk2, vk3);
    float tbr, tvr, tbb, tvb;
    interpTcaLegacy(lens->tca, focalLength, tbr, tvr, tbb, tvb);

    std::vector<float> scratch(N);
    memcpy(scratch.data(), pixels, N * sizeof(float));

    const float cx = (float)(width  - 1) * 0.5f;
    const float cy = (float)(height - 1) * 0.5f;
    const float normR = fminf(cx, cy);

    for (int py = 0; py < height; py++) {
        for (int px = 0; px < width; px++) {
            float nx = ((float)px - cx) / normR;
            float ny = ((float)py - cy) / normR;
            float r  = sqrtf(nx*nx + ny*ny);
            float r2 = r * r;
            float r4 = r2 * r2;
            float r6 = r4 * r2;

            float dScale = 1.0f;
            if (distStrength > 0.0f && r > 1e-6f) {
                float fullScale = (1.0f + da*r2 + db*r4 + dc*r6);
                dScale = 1.0f + distStrength * (fullScale - 1.0f);
            }

            float tcaScaleR = 1.0f + caStrength * ((tvr + tbr*r2) - 1.0f);
            float tcaScaleB = 1.0f + caStrength * ((tvb + tbb*r2) - 1.0f);

            float srcXr = cx + (nx * dScale * tcaScaleR) * normR;
            float srcYr = cy + (ny * dScale * tcaScaleR) * normR;
            float srcXg = cx + (nx * dScale) * normR;
            float srcYg = cy + (ny * dScale) * normR;
            float srcXb = cx + (nx * dScale * tcaScaleB) * normR;
            float srcYb = cy + (ny * dScale * tcaScaleB) * normR;

            float rR, gR, bR, rG, gG, bG, rB, gB, bB;
            sampleRGB(scratch.data(), width, height, srcXr, srcYr, rR, gR, bR);
            sampleRGB(scratch.data(), width, height, srcXg, srcYg, rG, gG, bG);
            sampleRGB(scratch.data(), width, height, srcXb, srcYb, rB, gB, bB);

            float* dst = pixels + (py * width + px) * 3;
            dst[0] = rR;
            dst[1] = gG;
            dst[2] = bB;

            if (vigStrength > 0.0f) {
                float vigFull = 1.0f + vk1*r2 + vk2*r4 + vk3*r6;
                float vigScale = 1.0f + vigStrength * (vigFull - 1.0f);
                dst[0] = fmaxf(0.0f, dst[0] * vigScale);
                dst[1] = fmaxf(0.0f, dst[1] * vigScale);
                dst[2] = fmaxf(0.0f, dst[2] * vigScale);
            }
        }
    }
}
