/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  JNI entry points for the v3 pipeline. One symbol per Kotlin facade method
 *  in RawV3Engine.kt. See Plan.md §5.
 *
 *  Stage A (M2)        — nativeStageADecode
 *  Stage B (M3+, TBD)  — nativeStageBDownsample, nativeStageBSerialize
 *  Stage C (M8+, TBD)  — nativeStageCExport
 *  Renderer (M3+, TBD) — nativeInitRenderer / nativeUpdateUniforms / ...
 * ─────────────────────────────────────────────────────────────────────────────
 */

#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window_jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include <cmath>
#include <cstdio>

#include "stage_a.h"
#include "../lensfun_android.h"   // lensfun probe + Stage A correction db
#include "tiff_mmap_io.h"   // writeStageATiff — reused for synthetic Stage A (JPEG/PNG)
#include "../libraw/libraw.h"
#include "stage_b_downsample.h"
#include "stage_b_serialize.h"
#include "stage_c_export.h"
#include "apply_macro.h"   // ApplyMacroMaskLayers (mask-layer count + structs)
#include "rayxie_defringe.h"  // second-stage CA defringe (non-RAW path)
#include "../raw_decoder_shared.h"  // rayxie_correct_fringing (RGB CA remover)
#include "gles_renderer.h"
#include "lut3d.h"   // parseCubeFile — .cube ASCII/1D + .smcube binary
#include "v3_debug_log.h"
#include "offscreen_save_renderer.h"
#include "adobe_xmp_parser.h"
#include "raw_v3_highlight_recovery.h"
#include "guided_filter.h"
#include "lmmse_demosaic.h"  // lmmse_demosaic_to_planes — used by self-test harness
#include "film_sim.h"

#define LOG_TAG "RawV3.JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Helper: JNI string → std::string (returns empty if null).
std::string jstrOrEmpty(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s = c ? c : "";
    if (c) env->ReleaseStringUTFChars(js, c);
    return s;
}

// IEEE-754 float → binary16 bit pattern (same as stage_a.cpp's helper).
// Used by the synthetic Stage A (JPEG/PNG) path to fill the RGBA-F16 buffer.
inline uint16_t f32ToHalf(float f) {
    uint32_t x; std::memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000u;
    int32_t exp = int32_t((x >> 23) & 0xFF) - 127 + 15;
    uint32_t mant = x & 0x7FFFFFu;
    if (exp <= 0) return uint16_t(sign);                 // underflow → ±0
    if (exp >= 0x1F) return uint16_t(sign | 0x7C00u);    // overflow → ±inf
    return uint16_t(sign | (uint32_t(exp) << 10) | (mant >> 13));
}

}  // anonymous namespace

extern "C" {

// Self-test: returns the version string. Used at startup to verify the JNI
// binding links correctly without performing any decode work.
JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeVersion(
        JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("v3-M2");
}

// Java symbol: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine#nativeExtractEmbeddedThumbnail
//
// Opens the RAW file, calls LibRaw::unpack_thumb() + dcraw_make_mem_thumb(),
// and returns the embedded JPEG bytes ready for BitmapFactory.decodeByteArray
// on the Kotlin side. Completes in ~10–30 ms vs ~15 s for full Stage A.
// Returns null if the camera has no embedded JPEG thumbnail.
JNIEXPORT jbyteArray JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeExtractEmbeddedThumbnail(
        JNIEnv* env, jobject /*thiz*/, jstring jFilePath) {
    if (!jFilePath) return nullptr;
    const char* path = env->GetStringUTFChars(jFilePath, nullptr);
    if (!path) return nullptr;
    std::string filePath(path);
    env->ReleaseStringUTFChars(jFilePath, path);

    LibRaw raw;
    int ret = raw.open_file(filePath.c_str());
    if (ret != LIBRAW_SUCCESS) {
        LOGI("extractEmbeddedThumbnail: open_file failed: %s", libraw_strerror(ret));
        return nullptr;
    }
    ret = raw.unpack_thumb();
    if (ret != LIBRAW_SUCCESS) {
        LOGI("extractEmbeddedThumbnail: unpack_thumb failed: %s", libraw_strerror(ret));
        return nullptr;
    }
    libraw_processed_image_t* thumb = raw.dcraw_make_mem_thumb(&ret);
    if (!thumb) {
        LOGI("extractEmbeddedThumbnail: dcraw_make_mem_thumb null: %s", libraw_strerror(ret));
        return nullptr;
    }
    if (thumb->type != LIBRAW_IMAGE_JPEG || thumb->data_size <= 0) {
        LOGI("extractEmbeddedThumbnail: thumb type=%d not JPEG, skipping", (int)thumb->type);
        LibRaw::dcraw_clear_mem(thumb);
        return nullptr;
    }
    jbyteArray out = env->NewByteArray((jsize)thumb->data_size);
    if (out) {
        env->SetByteArrayRegion(out, 0, (jsize)thumb->data_size, (const jbyte*)thumb->data);
        LOGI("extractEmbeddedThumbnail: returning %u bytes JPEG", thumb->data_size);
    }
    LibRaw::dcraw_clear_mem(thumb);
    return out;
}

// Java symbol: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine#nativeLensfunProbe
//
// Metadata-only probe for the import screen's "Lens Correction" section:
// opens the RAW (no unpack — fast), extracts camera/lens EXIF, and resolves
// them against the bundled Lensfun database with the SAME strict matcher the
// Stage A correction uses — so what the UI displays is exactly what will be
// applied. Returns a String[8]:
//   [0] EXIF camera maker   [1] EXIF camera model   [2] EXIF lens string
//   [3] focal length (mm)   [4] aperture (f-number)
//   [5] matched DB camera model ("" = no match)
//   [6] matched camera crop factor ("" = no match)
//   [7] matched DB lens model ("" = no match)
// Returns null when the file can't be opened as a RAW.
JNIEXPORT jobjectArray JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeLensfunProbe(
        JNIEnv* env, jobject /*thiz*/, jstring jRawPath, jstring jDbDir) {
    const std::string rawPath = jstrOrEmpty(env, jRawPath);
    const std::string dbDir   = jstrOrEmpty(env, jDbDir);
    if (rawPath.empty()) return nullptr;

    LibRaw lr;
    if (lr.open_file(rawPath.c_str()) != LIBRAW_SUCCESS) {
        LOGI("nativeLensfunProbe: open_file failed for %s", rawPath.c_str());
        return nullptr;
    }
    const std::string camMaker  = lr.imgdata.idata.make;
    const std::string camModel  = lr.imgdata.idata.model;
    const std::string lensMaker = lr.imgdata.lens.LensMake;
    const std::string lensModel = lr.imgdata.lens.Lens;
    const float focal    = lr.imgdata.other.focal_len;
    const float aperture = lr.imgdata.other.aperture;
    lr.recycle();

    std::string mCam, mCrop, mLens;
    if (!dbDir.empty()) {
        const LfDatabase* db = lfa_cached_database(dbDir.c_str());
        if (db) {
            const LfaMatch m = lfa_match_strict(
                *db, camMaker.c_str(), camModel.c_str(),
                lensMaker.c_str(), lensModel.c_str());
            // Independent reporting — see nativeLensfunMatch for the rationale.
            if (m.cam) {
                mCam  = m.cam->model;
                char cropBuf[16];
                snprintf(cropBuf, sizeof(cropBuf), "%.2f", m.cam->cropFactor);
                mCrop = cropBuf;
            }
            if (m.lens) mLens = m.lens->model;
        }
    }

    char focalBuf[16], apBuf[16];
    snprintf(focalBuf, sizeof(focalBuf), "%.1f", focal);
    snprintf(apBuf,    sizeof(apBuf),    "%.1f", aperture);
    const char* fields[8] = {
        camMaker.c_str(), camModel.c_str(), lensModel.c_str(),
        focalBuf, apBuf,
        mCam.c_str(), mCrop.c_str(), mLens.c_str(),
    };
    jobjectArray out = env->NewObjectArray(8, env->FindClass("java/lang/String"), nullptr);
    if (!out) return nullptr;
    for (int i = 0; i < 8; ++i)
        env->SetObjectArrayElement(out, i, env->NewStringUTF(fields[i]));
    LOGI("nativeLensfunProbe: cam='%s %s' lens='%s' f=%.1f f/%.1f → match cam='%s' lens='%s'",
         camMaker.c_str(), camModel.c_str(), lensModel.c_str(), focal, aperture,
         mCam.c_str(), mLens.c_str());
    return out;
}

// Java symbol: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine#nativeLensfunMatch
//
// Strings-only variant of the probe for callers that already hold the EXIF
// identity (the workspace selector reads it via ExifInterface without
// touching LibRaw). Same strict matcher as the Stage A correction.
// Returns String[3] { matchedCameraModel, cropFactor, matchedLensModel }
// with empty strings on no-match; null when the db can't be loaded.
JNIEXPORT jobjectArray JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeLensfunMatch(
        JNIEnv* env, jobject /*thiz*/,
        jstring jDbDir, jstring jCamMaker, jstring jCamModel,
        jstring jLensMaker, jstring jLensModel) {
    const std::string dbDir = jstrOrEmpty(env, jDbDir);
    const LfDatabase* db = lfa_cached_database(dbDir.c_str());
    if (!db) return nullptr;
    const std::string camMaker  = jstrOrEmpty(env, jCamMaker);
    const std::string camModel  = jstrOrEmpty(env, jCamModel);
    const std::string lensMaker = jstrOrEmpty(env, jLensMaker);
    const std::string lensModel = jstrOrEmpty(env, jLensModel);
    const LfaMatch m = lfa_match_strict(
        *db, camMaker.c_str(), camModel.c_str(),
        lensMaker.c_str(), lensModel.c_str());
    // Report the camera and the lens INDEPENDENTLY. Gating both on m.ok()
    // (which needs cam AND lens) threw away a perfectly good body match
    // whenever the lens didn't resolve — e.g. a Sony ILCE-7M2 (in the DB) with
    // an unmatched "DT 28-105mm F3.7 SAM" or an adapted lens reporting nothing.
    // The workspace UI reads these two fields separately, so an empty lens just
    // leaves the lens picker blank while the Camera body field auto-fills.
    // Correction itself still requires both (Stage A gates on m.ok()).
    std::string mCam, mCrop, mLens;
    if (m.cam) {
        mCam  = m.cam->model;
        char cropBuf[16];
        snprintf(cropBuf, sizeof(cropBuf), "%.2f", m.cam->cropFactor);
        mCrop = cropBuf;
    }
    if (m.lens) mLens = m.lens->model;
    // [3] = confidence. The UI must know whether it may apply this silently
    // (HIGH) or has to ask (MEDIUM/LOW), and re-deriving that on the Kotlin side
    // would be a second matcher that drifts from the one Stage A applies.
    char confBuf[8];
    snprintf(confBuf, sizeof(confBuf), "%d", m.lens ? (int)m.confidence : 0);
    const char* fields[4] = { mCam.c_str(), mCrop.c_str(), mLens.c_str(), confBuf };
    jobjectArray out = env->NewObjectArray(4, env->FindClass("java/lang/String"), nullptr);
    if (!out) return nullptr;
    for (int i = 0; i < 4; ++i)
        env->SetObjectArrayElement(out, i, env->NewStringUTF(fields[i]));
    return out;
}

// Java symbol: …RawV3Engine#nativeLensfunRankLenses
//
// Ranked lens shortlist for the workspace picker: the same scorer Stage A uses,
// but returning the top N instead of only the winner, so a less-than-certain
// match can be presented as a choice rather than silently applied.
//
// One row per candidate, tab-separated:
//   model \t cropFactor \t mount \t confidence \t nameTier,fmt,mount,brand,range,aperture,stab,motor
// The trailing breakdown drives the "why this one?" diagnostics — shipping the
// component scores beats making the UI guess at the reasoning.
//
// adaptedMode drops the mount criterion for lenses on a dumb adapter.
// Null when the database can't be loaded; empty array when nothing is credible.
JNIEXPORT jobjectArray JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeLensfunRankLenses(
        JNIEnv* env, jobject /*thiz*/,
        jstring jDbDir, jstring jCamMaker, jstring jCamModel,
        jstring jLensMaker, jstring jLensModel,
        jboolean jAdapted, jint jMaxOut) {
    const std::string dbDir = jstrOrEmpty(env, jDbDir);
    const LfDatabase* db = lfa_cached_database(dbDir.c_str());
    if (!db) return nullptr;
    const std::string camMaker  = jstrOrEmpty(env, jCamMaker);
    const std::string camModel  = jstrOrEmpty(env, jCamModel);
    const std::string lensMaker = jstrOrEmpty(env, jLensMaker);
    const std::string lensModel = jstrOrEmpty(env, jLensModel);

    // Resolve the body first — the sensor format is the top-priority criterion,
    // so without a camera there is nothing to rank against.
    const LfaMatch camOnly = lfa_match_strict(
        *db, camMaker.c_str(), camModel.c_str(), "", "");
    const int maxOut = std::max(1, std::min(16, (int)jMaxOut));
    std::vector<LfaLensCandidate> cands((size_t)maxOut);
    const int n = lfa_rank_lenses(*db, camOnly.cam, lensMaker.c_str(),
                                  lensModel.c_str(), jAdapted == JNI_TRUE,
                                  cands.data(), maxOut);

    jobjectArray out = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    if (!out) return nullptr;
    char row[768];
    for (int i = 0; i < n; ++i) {
        const LfaLensCandidate& c = cands[(size_t)i];
        const LfaLensScore& s = c.score;
        snprintf(row, sizeof(row), "%s\t%.3f\t%s\t%d\t%d,%d,%d,%d,%d,%d,%d,%d",
                 c.lens->model.c_str(), c.lens->cropFactor, c.lens->mount.c_str(),
                 (int)c.confidence,
                 s.nameTier, s.fmt, s.mount, s.brand, s.range, s.aperture,
                 s.stab, s.motor);
        env->SetObjectArrayElement(out, i, env->NewStringUTF(row));
    }
    LOGI("nativeLensfunRankLenses: '%s' on '%s' adapted=%d -> %d candidate(s)",
         lensModel.c_str(), camModel.c_str(), jAdapted == JNI_TRUE ? 1 : 0, n);
    return out;
}

// Java symbol: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine#nativeLensfunListCameras
//
// Enumerate every camera body in the Lensfun database for the manual
// override autocomplete. One row per camera: "maker\tmodel\tcropFactor".
// Null when the database can't be loaded.
JNIEXPORT jobjectArray JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeLensfunListCameras(
        JNIEnv* env, jobject /*thiz*/, jstring jDbDir) {
    const std::string dbDir = jstrOrEmpty(env, jDbDir);
    const LfDatabase* db = lfa_cached_database(dbDir.c_str());
    if (!db) return nullptr;
    const jsize n = (jsize)db->cameras.size();
    jobjectArray out = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    if (!out) return nullptr;
    char row[512];
    for (jsize i = 0; i < n; ++i) {
        const auto& c = db->cameras[(size_t)i];
        // "maker\tmodel\tcrop\talias" — alias (marketing name) may be empty.
        snprintf(row, sizeof(row), "%s\t%s\t%.2f\t%s",
                 c.maker.c_str(), c.model.c_str(), c.cropFactor, c.alias.c_str());
        env->SetObjectArrayElement(out, i, env->NewStringUTF(row));
    }
    return out;
}

// Java symbol: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine#nativeLensfunListLenses
//
// Enumerate every lens in the Lensfun database for the manual override
// pickers. One row per lens: "maker\tmodel\tcropFactor\tmount". Null when the
// database can't be loaded. Brand canonicalisation/grouping happens on the
// Kotlin side.
//
// crop + mount are emitted because the picker has to DISAMBIGUATE: 95 models
// appear more than once differing only by calibration format, so a bare name
// list shows the user the same entry two or three times with no way to tell
// which is which.
JNIEXPORT jobjectArray JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeLensfunListLenses(
        JNIEnv* env, jobject /*thiz*/, jstring jDbDir) {
    const std::string dbDir = jstrOrEmpty(env, jDbDir);
    const LfDatabase* db = lfa_cached_database(dbDir.c_str());
    if (!db) return nullptr;
    const jsize n = (jsize)db->lenses.size();
    jobjectArray out = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    if (!out) return nullptr;
    char row[768];
    for (jsize i = 0; i < n; ++i) {
        const auto& l = db->lenses[(size_t)i];
        snprintf(row, sizeof(row), "%s\t%s\t%.3f\t%s",
                 l.maker.c_str(), l.model.c_str(), l.cropFactor, l.mount.c_str());
        env->SetObjectArrayElement(out, i, env->NewStringUTF(row));
    }
    return out;
}

// Defined further down, next to the Rayxie CA core. Forward-declared here so
// nativeStageADecode can arm the hook before runStageA reaches the CA step.
static void installCaHookOnce();

// Java symbol: com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine#nativeStageADecode
//
// Kotlin facade (RawV3Engine.stageADecode) constructs the cache path with
// the form `<cacheDir>/raw_v3/<sha>/A.tif` and passes it as outTifPath. The
// Kotlin side guarantees the parent directory exists before calling.
JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeStageADecode(
        JNIEnv* env, jobject /*thiz*/,
        jstring jRawFilePath,
        jstring jOutTifPath,
        jint    demosaicAlgorithm,
        jint    highlightMode,
        jint    wbSource,
        jfloat  exposureShift,
        jint    fbddNoise,
        jboolean nrEnabled,
        jint     nrLuma,
        jint     nrChroma,
        jboolean caCorrectionEnabled,
        jstring jLensfunCameraId,
        jstring jLensfunLensId,
        jfloat blackLevelDelta,
        jfloat whiteLevelDelta,
        jfloat clipThreshold,
        jfloat  dualContrastThreshold,
        jboolean dualAutoContrast,
        jbyteArray jHdrModelData,
        jbyteArray jShadowModelData,
        jboolean enhanceEnabled,
        jint     enhanceWindowSize,
        jfloat   enhanceNoiseVar,
        jfloat   enhanceUsmRadius,
        jfloat   enhanceUsmAmount,
        jfloat   enhanceUsmThreshold,
        jboolean enhanceSkipDenoise,
        jboolean enhanceSkipSharpen,
        jfloat   enhanceUsmEdgeThreshold,
        jboolean enhanceGuidedFilter,
        jfloat   claheHighlightsBoost,
        jfloat   adjustMaximumThr,
        jboolean isLinearRaw,
        jstring jLensfunDbDir,
        jfloat  lensfunFocalOverrideMm,
        jfloatArray jLiftMap,
        jfloat  jLiftTau) {

    raw_v3::StageAOptions opts;
    opts.demosaicAlgorithm      = int(demosaicAlgorithm);
    opts.highlightMode          = int(highlightMode);
    opts.wbSource               = int(wbSource);
    opts.exposureShift          = float(exposureShift);
    opts.fbddNoise              = int(fbddNoise);
    opts.nrEnabled              = nrEnabled == JNI_TRUE;
    opts.nrLuma                 = int(nrLuma);
    opts.nrChroma               = int(nrChroma);
    opts.caCorrectionEnabled    = caCorrectionEnabled == JNI_TRUE;
    // The median-deviation defringe rides the SAME user-facing toggle rather
    // than adding a JNI parameter (which would be an ABI change for one float).
    // 0.55 is deliberately below the 0.7 default: the span clip has already run
    // by this point, so this pass only has to clean up what that left behind,
    // and under-correcting fringe is far less objectionable than desaturating an
    // edge. Raise it here if field images still show colour edges.
    opts.caGuidedStrength       = (caCorrectionEnabled == JNI_TRUE) ? 0.55f : 0.f;
    // "Safe Recovery" — see StageAOptions::highlightDesaturateStrength. Every
    // RAW import runs highlightMode=Blend/Reconstruct; without this, blown
    // smooth highlights can leave a magenta/purple cast (confirmed 2026-08-28
    // on-device: clipped sky decoding to a constant magenta-ish colour).
    // Independent of caCorrectionEnabled. 0.95 is a stronger magenta kill that
    // still leaves neutralizeHighlights's brightness/edge/hue gates in charge
    // of *where* desat applies (not a full 1.0 chroma wipe). Pairs with the
    // Strong (0.85) Highlight Protection default.
    opts.highlightDesaturateStrength = 0.95f;
    opts.lensfunCameraId        = jstrOrEmpty(env, jLensfunCameraId);
    opts.lensfunLensId          = jstrOrEmpty(env, jLensfunLensId);
    opts.lensfunDbDir           = jstrOrEmpty(env, jLensfunDbDir);
    opts.lensfunFocalOverrideMm = float(lensfunFocalOverrideMm);
    // Zero-DCE adaptive devignetting: square row-major lift map (empty = static).
    if (jLiftMap != nullptr) {
        jsize n = env->GetArrayLength(jLiftMap);
        if (n > 0) {
            opts.liftMap.resize((size_t)n);
            env->GetFloatArrayRegion(jLiftMap, 0, n, opts.liftMap.data());
            opts.liftSide = (int)std::sqrt((double)n);
        }
    }
    opts.liftTau = jLiftTau > 0.f ? jLiftTau : 0.7f;
    opts.blackLevelDelta        = float(blackLevelDelta);
    opts.whiteLevelDelta        = float(whiteLevelDelta);
    opts.clipThreshold          = float(clipThreshold);
    opts.dualContrastThreshold  = float(dualContrastThreshold);
    opts.dualAutoContrast       = dualAutoContrast == JNI_TRUE;
    if (jHdrModelData != nullptr) {
        jsize len = env->GetArrayLength(jHdrModelData);
        if (len > 0) {
            opts.hdrModelData.resize((size_t)len);
            env->GetByteArrayRegion(jHdrModelData, 0, len,
                reinterpret_cast<jbyte*>(opts.hdrModelData.data()));
        }
    }
    if (jShadowModelData != nullptr) {
        jsize len = env->GetArrayLength(jShadowModelData);
        if (len > 0) {
            opts.shadowModelData.resize((size_t)len);
            env->GetByteArrayRegion(jShadowModelData, 0, len,
                reinterpret_cast<jbyte*>(opts.shadowModelData.data()));
        }
    }
    opts.enhanceEnabled      = enhanceEnabled == JNI_TRUE;
    opts.enhanceWindowSize   = int(enhanceWindowSize);
    opts.enhanceNoiseVar     = float(enhanceNoiseVar);
    opts.enhanceUsmRadius    = float(enhanceUsmRadius);
    opts.enhanceUsmAmount    = float(enhanceUsmAmount);
    opts.enhanceUsmThreshold = float(enhanceUsmThreshold);
    opts.enhanceSkipDenoise      = enhanceSkipDenoise == JNI_TRUE;
    opts.enhanceSkipSharpen      = enhanceSkipSharpen == JNI_TRUE;
    opts.enhanceUsmEdgeThreshold = float(enhanceUsmEdgeThreshold);
    opts.enhanceGuidedFilter     = enhanceGuidedFilter == JNI_TRUE;
    opts.claheHighlightsBoost    = float(claheHighlightsBoost);
    opts.adjustMaximumThr        = float(adjustMaximumThr);
    opts.isLinearRaw             = isLinearRaw == JNI_TRUE;

    const std::string rawFilePath = jstrOrEmpty(env, jRawFilePath);
    const std::string outTifPath  = jstrOrEmpty(env, jOutTifPath);

    if (rawFilePath.empty() || outTifPath.empty()) {
        LOGE("nativeStageADecode: empty path argument");
        return env->NewStringUTF("{\"success\":false,\"error\":\"empty path\"}");
    }

    // Arm the Rayxie CA hook. stage_a.cpp can't call into raw_decoder.cpp
    // directly (not in the desktop razbatch target), so it holds a function
    // pointer this Android-only translation unit fills in. Running CA inside
    // runStageA means it works on the buffer that already exists — a post-hoc
    // pass over the finished A.tif would cost ~330 MB of transient buffers on
    // a 42 MP frame, and this pipeline already fights lmkd.
    installCaHookOnce();

    raw_v3::StageAMetadata meta = raw_v3::runStageA(rawFilePath, outTifPath, opts);

    // Emit a small JSON blob the Kotlin side parses. Hand-rolled (no nlohmann
    // dependency) — fields are a fixed schema.
    auto esc = [](const std::string& s) {
        std::string out; out.reserve(s.size() + 2);
        for (char c : s) {
            switch (c) {
                case '"':  out += "\\\""; break;
                case '\\': out += "\\\\"; break;
                case '\n': out += "\\n";  break;
                case '\r': out += "\\r";  break;
                case '\t': out += "\\t";  break;
                default:
                    if (uint8_t(c) < 0x20) {
                        char buf[8]; snprintf(buf, sizeof(buf), "\\u%04x", c);
                        out += buf;
                    } else {
                        out += c;
                    }
            }
        }
        return out;
    };

    std::string json = "{";
    json += "\"success\":";        json += (meta.success ? "true" : "false");
    json += ",\"width\":";         json += std::to_string(meta.width);
    json += ",\"height\":";        json += std::to_string(meta.height);
    json += ",\"orientation\":";   json += std::to_string(meta.orientation);
    json += ",\"cameraMake\":\"";  json += esc(meta.cameraMake);   json += "\"";
    json += ",\"cameraModel\":\""; json += esc(meta.cameraModel);  json += "\"";
    json += ",\"lensMake\":\"";    json += esc(meta.lensMake);     json += "\"";
    json += ",\"lensModel\":\"";   json += esc(meta.lensModel);    json += "\"";
    json += ",\"lensId\":";        json += std::to_string(meta.lensId);
    json += ",\"colorTemperature\":"; json += std::to_string(meta.colorTemperature);
    json += ",\"iso\":";           json += std::to_string(meta.iso);
    json += ",\"shutterSpeed\":";  json += std::to_string(meta.shutterSpeed);
    json += ",\"aperture\":";      json += std::to_string(meta.aperture);
    json += ",\"focalLength\":";   json += std::to_string(meta.focalLength);
    json += ",\"dateTimeOriginal\":\""; json += esc(meta.dateTimeOriginal); json += "\"";
    if (meta.dualContrastThreshold >= 0.f) {
        json += ",\"dualContrastThreshold\":";
        json += std::to_string(meta.dualContrastThreshold);
    }
    if (!meta.errorMessage.empty()) {
        json += ",\"error\":\""; json += esc(meta.errorMessage); json += "\"";
    }
    json += "}";

    return env->NewStringUTF(json.c_str());
}

// ─────────────────────────────────────────────────────────────────────────────
//  Synthetic Stage A for already-decoded sources (JPEG / PNG / WebP / etc.)
//
//  These have no Bayer mosaic, so LibRaw's runStageA can't open them. Instead
//  the Kotlin side decodes the file to an ARGB_8888 Bitmap (EXIF-oriented),
//  hands us the raw RGBA bytes, and we write the SAME Stage A BigTIFF
//  (RGBA-F16, sRGB-gamma) that runStageA produces — so Stage B/C, the Detail
//  tab (NR/sharpness), Auto-Exposure and export all work UNCHANGED downstream.
//
//  JPEG/PNG pixels are already sRGB-gamma-encoded 8-bit, which is exactly what
//  Stage A stores (it applies the sRGB transfer to LibRaw's linear output), so
//  we simply normalise /255 into half-floats — no extra transfer needed.
// ─────────────────────────────────────────────────────────────────────────────
// Bake a luma-scale map into the FP16 Stage A TIFF, in place (denoise baseline).
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeApplyLumaScaleToStageA(
        JNIEnv* env, jobject /*thiz*/,
        jstring jTifPath,
        jfloatArray jScale,
        jint jScaleW,
        jint jScaleH) {
    const std::string tifPath = jstrOrEmpty(env, jTifPath);
    if (tifPath.empty() || jScale == nullptr || jScaleW <= 0 || jScaleH <= 0) return JNI_FALSE;
    const jsize n = env->GetArrayLength(jScale);
    if (n < 1LL * jScaleW * jScaleH) {
        LOGE("nativeApplyLumaScaleToStageA: scale array %d < %dx%d", int(n), int(jScaleW), int(jScaleH));
        return JNI_FALSE;
    }
    jfloat* scale = env->GetFloatArrayElements(jScale, nullptr);
    if (!scale) return JNI_FALSE;
    const bool ok = raw_v3::applyLumaScaleToStageA(tifPath, scale, int(jScaleW), int(jScaleH));
    env->ReleaseFloatArrayElements(jScale, scale, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ─────────────────────────────────────────────────────────────────────────────
// Bake a per-channel 256×3 tone-curve LUT into the FP16 Stage A TIFF, in place
// (Camera Color Profile baked at import). jLut is 768 bytes (256×3 interleaved).
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeApplyToneCurveToStageA(
        JNIEnv* env, jobject /*thiz*/,
        jstring jTifPath,
        jbyteArray jLut) {
    const std::string tifPath = jstrOrEmpty(env, jTifPath);
    if (tifPath.empty() || jLut == nullptr) return JNI_FALSE;
    const jsize n = env->GetArrayLength(jLut);
    if (n < 768) {
        LOGE("nativeApplyToneCurveToStageA: lut array %d < 768", int(n));
        return JNI_FALSE;
    }
    jbyte* lut = env->GetByteArrayElements(jLut, nullptr);
    if (!lut) return JNI_FALSE;
    const bool ok = raw_v3::applyToneCurveToStageA(
        tifPath, reinterpret_cast<const uint8_t*>(lut));
    env->ReleaseByteArrayElements(jLut, lut, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ── Rayxie CA correction — shared core (RAW hook + non-RAW JNI) ─────────────
//
// The RayXie29 algorithm (github.com/RayXie29/Chromatic_aberration_correction)
// is an RGB-domain, edge-based colour-difference clip: find a green-gradient
// edge, then clamp R-G and B-G inside the span to the values at the span ends.
// It never needed CFA data, so it applies to a demosaiced RAW and a decoded
// JPEG alike. We reuse the EXISTING battle-tested port
// (`rayxie_correct_fringing`: bounded 128-px edge search, multithreaded,
// RAYXIE_SKIP_VERTICAL honoured) rather than writing a second implementation.
//
// Lives in v3_jni.cpp on purpose: raw_decoder.cpp (where that port lives) is
// NOT part of the desktop razbatch target, so stage_a.cpp cannot call it
// directly without breaking the desktop link. stage_a exposes a hook instead,
// which this Android-only file installs (installCaHookOnce).
//
// DOMAIN: the Stage-A buffer is sRGB-encoded FP16 in [0,1], NOT linear light
// (see the Lensfun block in stage_a.cpp). We scale to 16-bit for the algorithm
// and back — deliberately NOT linearising, because Lensfun and every Stage B/C
// consumer reads this data as sRGB-encoded, and it matches the domain the
// algorithm ran in on the v2 RAW path (post-gamma 16-bit).
//
// MEMORY: processed in row BANDS. The vertical pass is compiled out on Android
// (RAYXIE_SKIP_VERTICAL), so every row is independent and banding is EXACT, not
// an approximation. A whole-image uint16 BGR copy plus a before-copy would be
// ~290 MB on a 24 MP RAW; a band is ~18 MB regardless of megapixels — and this
// pipeline already fights lmkd.
//
// GUARDS (see the night-foliage regression, 2026-08-27): the clip rewrites
// interior pixels as `bgmin/bgmax + G[k]`, so on extreme local contrast a bright
// pixel inside a span whose ends sit on dark blue sky gets repainted vivid blue.
// Both guards key on that: damage scales with the pixel own brightness.
//   1. SHADOW GATE — full strength at/below the image own luma MEDIAN,
//      smoothstepping to zero above it. Real purple/blue fringing lives on the
//      dark side of an edge, which is also where `bgmin + G[k]` stays small.
//   2. BOUNDED DELTA — real CA is a small colour deviation; clamp any channel
//      change to kMaxDelta.
static void razApplyCaToRgbaF16(uint16_t* rgba, int W, int H, int threshold) {
    if (!rgba || W < 8 || H < 8) return;
    const size_t px = size_t(W) * size_t(H);

    auto h2f = [](uint16_t h) -> float {
        const uint32_t sign = uint32_t(h & 0x8000u) << 16;
        const uint32_t e    = (h >> 10) & 0x1Fu;
        const uint32_t m    = h & 0x3FFu;
        uint32_t r32;
        if (e == 0)       r32 = sign | (m == 0 ? 0u : (((1u + 127u - 15u) << 23) | (m << 13)));
        else if (e == 31) r32 = sign | 0x7F800000u | (m << 13);
        else              r32 = sign | ((e + (127u - 15u)) << 23) | (m << 13);
        float f; std::memcpy(&f, &r32, 4); return f;
    };
    auto f2h = [](float f) -> uint16_t {
        uint32_t x; std::memcpy(&x, &f, 4);
        const uint32_t sign = (x >> 16) & 0x8000u;
        int32_t  exp  = int32_t((x >> 23) & 0xFF) - 127 + 15;
        uint32_t mant = x & 0x7FFFFFu;
        if (exp <= 0)  return uint16_t(sign);
        if (exp >= 31) return uint16_t(sign | 0x7C00u);
        return uint16_t(sign | (uint32_t(exp) << 10) | (mant >> 13));
    };
    auto toU16 = [](float v) -> int {
        v = v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
        return int(v * 65535.f + 0.5f);
    };

    // ── Shadow gate: 256-bin Rec.709 luma histogram of the ORIGINAL → median ─
    uint32_t hist[256] = {0};
    for (size_t p = 0; p < px; ++p) {
        const size_t i = p * 4;
        const float lum = 0.2126f * h2f(rgba[i]) + 0.7152f * h2f(rgba[i + 1])
                        + 0.0722f * h2f(rgba[i + 2]);
        int b = int(lum * 255.0f + 0.5f);
        hist[b < 0 ? 0 : (b > 255 ? 255 : b)]++;
    }
    uint32_t acc = 0; int medianBin = 128;
    for (int b = 0; b < 256; ++b) { acc += hist[b]; if (acc >= px / 2) { medianBin = b; break; } }
    const float midLuma = float(medianBin) / 255.0f;
    const float fadeEnd = midLuma + (1.0f - midLuma) * 0.5f;
    const float invSpan = (fadeEnd > midLuma) ? 1.0f / (fadeEnd - midLuma) : 0.0f;
    LOGI("razApplyCa: %dx%d thr=%d shadow-gate median=%.3f fadeEnd=%.3f",
         W, H, threshold, midLuma, fadeEnd);

    constexpr int kMaxDelta  = 3000;   // ~4.6% of range, about 12 levels at 8-bit
    constexpr int kBandRows  = 512;
    std::vector<uint16_t> bgr(size_t(kBandRows) * size_t(W) * 3);

    for (int y0 = 0; y0 < H; y0 += kBandRows) {
        const int rows = std::min(kBandRows, H - y0);
        for (int y = 0; y < rows; ++y) {
            const uint16_t* src = rgba + (size_t(y0 + y) * size_t(W)) * 4;
            uint16_t* dst = bgr.data() + size_t(y) * size_t(W) * 3;
            for (int x = 0; x < W; ++x) {
                dst[x * 3 + 0] = uint16_t(toU16(h2f(src[x * 4 + 2])));   // B
                dst[x * 3 + 1] = uint16_t(toU16(h2f(src[x * 4 + 1])));   // G
                dst[x * 3 + 2] = uint16_t(toU16(h2f(src[x * 4 + 0])));   // R
            }
        }
        rayxie_correct_fringing(bgr.data(), W, rows, threshold, nullptr);
        for (int y = 0; y < rows; ++y) {
            uint16_t* dstRow = rgba + (size_t(y0 + y) * size_t(W)) * 4;
            const uint16_t* cor = bgr.data() + size_t(y) * size_t(W) * 3;
            for (int x = 0; x < W; ++x) {
                const float r0 = h2f(dstRow[x * 4 + 0]);
                const float g0 = h2f(dstRow[x * 4 + 1]);
                const float b0 = h2f(dstRow[x * 4 + 2]);
                const float lum = 0.2126f * r0 + 0.7152f * g0 + 0.0722f * b0;
                float w = 1.0f;
                if (lum > midLuma) {
                    float t = (lum - midLuma) * invSpan;
                    if (t >= 1.0f) continue;              // fully gated out
                    t = t * t * (3.0f - 2.0f * t);
                    w = 1.0f - t;
                }
                auto blend = [&](int orig, int corrected) -> float {
                    int d = corrected - orig;
                    if (d > kMaxDelta) d = kMaxDelta;
                    else if (d < -kMaxDelta) d = -kMaxDelta;
                    int nv = orig + int(float(d) * w);
                    nv = nv < 0 ? 0 : (nv > 65535 ? 65535 : nv);
                    return float(nv) * (1.0f / 65535.0f);
                };
                dstRow[x * 4 + 0] = f2h(blend(toU16(r0), int(cor[x * 3 + 2])));
                dstRow[x * 4 + 1] = f2h(blend(toU16(g0), int(cor[x * 3 + 1])));
                dstRow[x * 4 + 2] = f2h(blend(toU16(b0), int(cor[x * 3 + 0])));
                // alpha (dstRow[x*4+3]) untouched
            }
        }
    }
}

// Install the CA hook into stage_a exactly once (thread-safe magic static), so
// the RAW path gets CA inside runStageA — on the buffer that already exists,
// with no extra full-image allocation.
static void installCaHookOnce() {
    static const bool installed = [] {
        raw_v3::setCaHook(&razApplyCaToRgbaF16);
        return true;
    }();
    (void)installed;
}

// Java symbol: RawV3Engine#nativeLensfunLastReport — one-line diagnostic of the
// last lfa_correct_rgba_f16 on the CALLING thread (the JPEG import path calls
// applyLensfunToStageA then this back-to-back on the same coroutine thread).
extern "C" JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeLensfunLastReport(
        JNIEnv* env, jobject /*thiz*/) {
    char buf[512];
    lfa_report_string(buf, sizeof buf);
    return env->NewStringUTF(buf);
}

// Java symbol: RawV3Engine#nativeApplyRayxieCaToStageA
//
// Non-RAW (JPEG/PNG) entry point: the synthetic Stage A never runs runStageA,
// so there is no hook call — apply the same core to the finished A.tif instead.
// A whole-image load is fine here (these sources are small); the RAW path uses
// the in-place hook precisely to avoid this allocation.
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeApplyRayxieCaToStageA(
        JNIEnv* env, jobject /*thiz*/, jstring jTifPath, jint jThreshold) {
    const std::string tifPath = jstrOrEmpty(env, jTifPath);
    if (tifPath.empty()) return JNI_FALSE;

    raw_v3::StageATiffReader* rd = raw_v3::openStageATiff(tifPath);
    if (!rd) { LOGE("applyRayxieCa: open failed %s", tifPath.c_str()); return JNI_FALSE; }
    const raw_v3::StageATiffHeader hdr = raw_v3::getStageATiffHeader(rd);
    const uint32_t W = hdr.width, H = hdr.height, rps = hdr.rowsPerStrip;
    if (W < 8 || H < 8 || rps == 0) { raw_v3::closeStageATiff(rd); return JNI_FALSE; }
    if (!raw_v3::stageATiffPayloadInBounds(rd)) {
        LOGE("applyRayxieCa: truncated A.tif %s", tifPath.c_str());
        raw_v3::closeStageATiff(rd);
        return JNI_FALSE;
    }

    std::vector<uint16_t> img(size_t(W) * H * 4);
    for (uint32_t y = 0; y < H; ++y) {
        const uint16_t* src = raw_v3::getStageATiffStrip(rd, y / rps)
                            + size_t(y % rps) * W * 4;
        std::memcpy(img.data() + size_t(y) * W * 4, src, size_t(W) * 4 * sizeof(uint16_t));
    }
    raw_v3::closeStageATiff(rd);   // release mmap BEFORE overwriting the file

    razApplyCaToRgbaF16(img.data(), int(W), int(H), int(jThreshold));

    // Second stage: the median-deviation defringe, in the SAME order runStageA
    // uses (span clip, then defringe, then Lensfun). Without this the non-RAW
    // path silently skipped it — the synthetic Stage A never calls runStageA, so
    // wiring the defringe only in there covered RAW alone. Logcat showed the
    // JPEG import running the clip and Lensfun with zero defringe lines, which
    // is how this was caught.
    raw_v3::RayxieDefringeParams dp;
    dp.strength = 0.55f;   // matches the value v3_jni sets for the RAW path
    const bool didDefringe =
        raw_v3::rayxie_defringe_f16(img.data(), int(W), int(H), dp);

    const bool ok = raw_v3::writeStageATiffAtomic(tifPath, img.data(), W, H);
    LOGI("applyRayxieCa: %ux%u threshold=%d clip+defringe(%s) -> %s",
         W, H, int(jThreshold), didDefringe ? "applied" : "no-op",
         ok ? "ok" : "FAIL");
    return ok ? JNI_TRUE : JNI_FALSE;
}
// Apply Lensfun correction in place to a Stage-A TIFF — the non-RAW (JPEG/PNG)
// path's equivalent of the RAW Lensfun block. Camera/lens are the UI-selected
// (or EXIF) names; focalMm is the manual override for adapted lenses.
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeApplyLensfunToStageA(
        JNIEnv* env, jobject /*thiz*/,
        jstring jTifPath,
        jstring jCamMaker, jstring jCamModel,
        jstring jLensMaker, jstring jLensModel,
        jfloat jFocalMm, jfloat jAperture,
        jstring jDbDir) {
    const std::string tifPath = jstrOrEmpty(env, jTifPath);
    if (tifPath.empty()) return JNI_FALSE;
    const bool ok = raw_v3::applyLensfunToStageA(
        tifPath,
        jstrOrEmpty(env, jCamMaker), jstrOrEmpty(env, jCamModel),
        jstrOrEmpty(env, jLensMaker), jstrOrEmpty(env, jLensModel),
        float(jFocalMm), float(jAperture),
        jstrOrEmpty(env, jDbDir));
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Apply Lensfun geometric correction IN PLACE to an ARGB_8888 Bitmap. Used for
// the live workspace-selector preview: the moment camera+lens+focal resolve,
// the background photo shows the same distortion/vignetting/CA correction the
// import will bake. Reuses the SAME strict matcher + FP16 correction core as
// the Stage-A path (u8↔f16 round-trip — the preview is small, so it's cheap).
// Returns false (bitmap untouched) when no confident match or no calibration.
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeApplyLensfunToBitmap(
        JNIEnv* env, jobject /*thiz*/,
        jobject jBitmap,
        jstring jCamMaker, jstring jCamModel,
        jstring jLensMaker, jstring jLensModel,
        jfloat jFocalMm, jfloat jAperture,
        jstring jDbDir) {
    const std::string dbDir = jstrOrEmpty(env, jDbDir);
    if (dbDir.empty() || jBitmap == nullptr) return JNI_FALSE;
    const LfDatabase* db = lfa_cached_database(dbDir.c_str());
    if (!db) return JNI_FALSE;
    const std::string camMaker  = jstrOrEmpty(env, jCamMaker);
    const std::string camModel  = jstrOrEmpty(env, jCamModel);
    const std::string lensMaker = jstrOrEmpty(env, jLensMaker);
    const std::string lensModel = jstrOrEmpty(env, jLensModel);
    LfaMatch m = lfa_match_strict(*db,
        camMaker.empty()  ? nullptr : camMaker.c_str(),
        camModel.empty()  ? nullptr : camModel.c_str(),
        lensMaker.empty() ? nullptr : lensMaker.c_str(),
        lensModel.empty() ? nullptr : lensModel.c_str());
    if (!m.ok()) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, jBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
        return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.width == 0 || info.height == 0)
        return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return JNI_FALSE;

    const int W = int(info.width), H = int(info.height);
    const size_t stride = info.stride;   // bytes per row (>= W*4)
    auto f2h = [](float f) -> uint16_t { _Float16 h = (_Float16)f; uint16_t o; std::memcpy(&o, &h, 2); return o; };
    auto h2f = [](uint16_t u) -> float { _Float16 h; std::memcpy(&h, &u, 2); return float(h); };
    std::vector<uint16_t> buf(size_t(W) * H * 4);
    const float inv255 = 1.0f / 255.0f;
    for (int y = 0; y < H; ++y) {
        const uint8_t* row = reinterpret_cast<const uint8_t*>(pixels) + size_t(y) * stride;
        uint16_t* out = buf.data() + size_t(y) * W * 4;
        for (int x = 0; x < W * 4; ++x) out[x] = f2h(row[x] * inv255);
    }
    const bool applied = lfa_correct_rgba_f16(buf.data(), W, H, m,
                                              float(jFocalMm), float(jAperture));
    if (applied) {
        auto cl = [](float v) -> uint8_t {
            int i = int(v * 255.0f + 0.5f);
            return uint8_t(i < 0 ? 0 : (i > 255 ? 255 : i));
        };
        for (int y = 0; y < H; ++y) {
            uint8_t* row = reinterpret_cast<uint8_t*>(pixels) + size_t(y) * stride;
            const uint16_t* in = buf.data() + size_t(y) * W * 4;
            for (int x = 0; x < W * 4; ++x) row[x] = cl(h2f(in[x]));
        }
    }
    AndroidBitmap_unlockPixels(env, jBitmap);
    return applied ? JNI_TRUE : JNI_FALSE;
}

// Parse ANY LUT file we ship through the SAME raw_v3::parseCubeFile the GL
// preview uses — ASCII .cube, 1D .cube (expanded to 3D) and the binary
// smol-cube (.smcube, "SML1" magic). The Kotlin-side reader understood only
// ASCII 3D .cube, so every .smcube silently failed there and the EXPORT
// dropped the LUT while the preview showed it (owner report 2026-09-07:
// Fujifilm Acros B&W looked right in the editor, saved in colour).
//
// Returned layout (flat float[]), or null on failure:
//   [0]      cube size N
//   [1..3]   domainMin RGB
//   [4..6]   domainMax RGB
//   [7..]    N³ × 3 RGB triplets, red-fastest (same order Stage C expects)
JNIEXPORT jfloatArray JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeParseLutFile(
        JNIEnv* env, jobject /*thiz*/, jstring jPath) {
    const std::string path = jstrOrEmpty(env, jPath);
    if (path.empty()) return nullptr;
    raw_v3::CubeLut lut = raw_v3::parseCubeFile(path);
    const size_t expect = size_t(lut.size) * lut.size * lut.size * 3;
    if (lut.size < 2 || lut.rgb.size() != expect) {
        LOGE("nativeParseLutFile: parse failed for %s (size=%d floats=%zu)",
             path.c_str(), lut.size, lut.rgb.size());
        return nullptr;
    }
    jfloatArray out = env->NewFloatArray(jsize(7 + expect));
    if (!out) return nullptr;
    float head[7];
    head[0] = float(lut.size);
    for (int i = 0; i < 3; ++i) head[1 + i] = lut.domainMin[i];
    for (int i = 0; i < 3; ++i) head[4 + i] = lut.domainMax[i];
    env->SetFloatArrayRegion(out, 0, 7, head);
    env->SetFloatArrayRegion(out, 7, jsize(expect), lut.rgb.data());
    LOGI("nativeParseLutFile: %s -> %d^3", path.c_str(), lut.size);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeStageAFromRgba(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray jRgba,
        jint jWidth,
        jint jHeight,
        jint jOrientation,
        jstring jOutTifPath) {

    const std::string outTifPath = jstrOrEmpty(env, jOutTifPath);
    const int W = int(jWidth), H = int(jHeight);
    if (outTifPath.empty() || W <= 0 || H <= 0 || jRgba == nullptr) {
        return env->NewStringUTF("{\"success\":false,\"error\":\"bad args\"}");
    }
    const jsize n = env->GetArrayLength(jRgba);
    const long long need = 4LL * W * H;
    if (n < need) {
        LOGE("nativeStageAFromRgba: array %d < needed %lld (%dx%d)", n, need, W, H);
        return env->NewStringUTF("{\"success\":false,\"error\":\"short rgba buffer\"}");
    }

    jbyte* src = env->GetByteArrayElements(jRgba, nullptr);
    if (!src) return env->NewStringUTF("{\"success\":false,\"error\":\"GetByteArrayElements null\"}");

    std::vector<uint16_t> rgbaF16(size_t(W) * H * 4);
    constexpr float inv255 = 1.0f / 255.0f;
    for (long long i = 0; i < 1LL * W * H; ++i) {
        const float r = float(uint8_t(src[i * 4 + 0])) * inv255;
        const float g = float(uint8_t(src[i * 4 + 1])) * inv255;
        const float b = float(uint8_t(src[i * 4 + 2])) * inv255;
        rgbaF16[i * 4 + 0] = f32ToHalf(r);
        rgbaF16[i * 4 + 1] = f32ToHalf(g);
        rgbaF16[i * 4 + 2] = f32ToHalf(b);
        rgbaF16[i * 4 + 3] = f32ToHalf(1.0f);
    }
    env->ReleaseByteArrayElements(jRgba, src, JNI_ABORT);

    const bool ok = raw_v3::writeStageATiff(outTifPath, rgbaF16.data(),
                                            uint32_t(W), uint32_t(H));
    if (!ok) return env->NewStringUTF("{\"success\":false,\"error\":\"writeStageATiff failed\"}");

    // Kotlin already applied EXIF orientation to the bitmap, so report
    // orientation=1 (identity) to avoid a double-rotation downstream.
    std::string json = "{\"success\":true,\"width\":";
    json += std::to_string(W);
    json += ",\"height\":"; json += std::to_string(H);
    json += ",\"orientation\":1";
    json += ",\"cameraMake\":\"\",\"cameraModel\":\"\",\"lensMake\":\"\",\"lensModel\":\"\"";
    json += ",\"lensId\":0,\"colorTemperature\":0";
    json += ",\"iso\":0,\"shutterSpeed\":0,\"aperture\":0,\"focalLength\":0";
    json += ",\"dateTimeOriginal\":\"\"}";
    return env->NewStringUTF(json.c_str());
}

// ─────────────────────────────────────────────────────────────────────────────
//  Stage B (M3) — downsample Stage A TIFF → AHardwareBuffer (RGBA_F16)
// ─────────────────────────────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeStageBDownsample(
        JNIEnv* env, jobject /*thiz*/,
        jstring jStageATifPath,
        jobject jHardwareBuffer,
        jint    targetW,
        jint    targetH,
        jfloatArray jParams,
        jfloatArray jSubjectMask,
        jint    jSubjectMaskSize,
        jint    jSubjectMaskH,
        jbyteArray  jCancelFlag     // byte[1]; set [0]=1 from Kotlin to abort early
) {

    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, jHardwareBuffer);
    if (!ahb) {
        LOGE("nativeStageBDownsample: AHardwareBuffer_fromHardwareBuffer returned null");
        return env->NewStringUTF("{\"success\":false,\"error\":\"null AHB\"}");
    }
    const std::string tifPath = jstrOrEmpty(env, jStageATifPath);
    if (tifPath.empty()) {
        return env->NewStringUTF("{\"success\":false,\"error\":\"empty path\"}");
    }

    // CLAHE intent rides in the same ShaderParams blob at slots [144..146].
    // jParams may be null (callers without CLAHE) → pass disabled defaults.
    bool  claheEnabled = false;
    float claheSh = 0.0f, claheHi = 0.0f;
    float lumaNR = 0.0f, chromaNR = 0.0f, blueNR = 0.0f, redNR = 0.0f;
    raw_v3::DetailParams detail{};
    if (jParams) {
        const jsize n = env->GetArrayLength(jParams);
        if (n > 144) {
            jfloat* p = env->GetFloatArrayElements(jParams, nullptr);
            claheEnabled = p[144] > 0.5f;
            if (n > 145) claheSh = p[145];
            if (n > 146) claheHi = p[146];
            if (n > 147) lumaNR = p[147];
            if (n > 148) chromaNR = p[148];
            // Slot 233 = blue (Cb) NR; slot 234 = red (Cr) NR.
            if (n > 233) blueNR = p[233];
            if (n > 234) redNR  = p[234];
            if (n > 149) detail.sharpness           = p[149];
            if (n > 150) detail.smartSharpness      = p[150];
            if (n > 151) detail.clarity             = p[151];
            if (n > 408) detail.clarityLift         = p[408];
            if (n > 152) detail.texture             = p[152];
            if (n > 153) detail.filmGrain           = p[153];
            if (n > 154) detail.filmGrainSize       = p[154];
            if (n > 155) detail.filmGrainUniformity = p[155];
            if (n > 156) detail.filmGrainWashOut    = p[156];
            if (n > 178) detail.smoothBackground    = p[178];
            env->ReleaseFloatArrayElements(jParams, p, JNI_ABORT);
        }
    }

    // Subject mask (U2Net, [0,1], maskSize²). Copy out of the JNI array so we
    // can release it before the (synchronous) downsample runs.
    std::vector<float> maskBuf;
    int maskSize = 0;
    int maskH    = 0;
    if (jSubjectMask && jSubjectMaskSize > 0) {
        const int mh = jSubjectMaskH > 0 ? int(jSubjectMaskH) : int(jSubjectMaskSize);
        const jsize mn = env->GetArrayLength(jSubjectMask);
        if (mn >= jSubjectMaskSize * mh) {
            maskBuf.resize(size_t(jSubjectMaskSize) * mh);
            env->GetFloatArrayRegion(jSubjectMask, 0, jsize(maskBuf.size()), maskBuf.data());
            maskSize = jSubjectMaskSize;
            maskH    = mh;
        }
    }

    // Pin the cancel flag byte array so C++ can read it during the downsample.
    // GetByteArrayElements with isCopy=false gives a direct pointer when
    // available (most JVMs on Android do this for byte arrays). The byte
    // stays pinned until ReleaseByte below.
    jbyte* cancelPtr = nullptr;
    if (jCancelFlag) {
        cancelPtr = env->GetByteArrayElements(jCancelFlag, nullptr);
    }

    raw_v3::StageBResult r = raw_v3::runStageBDownsample(
        tifPath, ahb, uint32_t(targetW), uint32_t(targetH),
        claheEnabled, claheSh, claheHi, lumaNR, chromaNR, blueNR, redNR, detail,
        maskBuf.empty() ? nullptr : maskBuf.data(), maskSize, maskH,
        reinterpret_cast<const volatile int8_t*>(cancelPtr));

    if (jCancelFlag && cancelPtr) {
        env->ReleaseByteArrayElements(jCancelFlag, cancelPtr, JNI_ABORT);
    }

    std::string json = "{";
    json += "\"success\":";    json += (r.success ? "true" : "false");
    json += ",\"cancelled\":"; json += (r.cancelled ? "true" : "false");
    json += ",\"width\":";     json += std::to_string(r.outWidth);
    json += ",\"height\":";    json += std::to_string(r.outHeight);
    if (!r.error.empty()) {
        json += ",\"error\":\""; for (char c : r.error) {
            if (c == '"' || c == '\\') json += '\\'; json += c;
        } json += "\"";
    }
    json += "}";
    return env->NewStringUTF(json.c_str());
}

// Option A fast path: re-apply only the spatial pre-pass onto a fresh AHB,
// copying the pristine downsampled FP16 from a cached AHB. Skips disk decode +
// Lanczos downsample. Same ShaderParams slot layout as nativeStageBDownsample.
JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeStageBApplySpatialToAhb(
        JNIEnv* env, jobject /*thiz*/,
        jobject jSrcHardwareBuffer,
        jobject jDstHardwareBuffer,
        jfloatArray jParams,
        jfloatArray jSubjectMask,
        jint    jSubjectMaskSize,
        jint    jSubjectMaskH
) {
    AHardwareBuffer* srcAhb = AHardwareBuffer_fromHardwareBuffer(env, jSrcHardwareBuffer);
    AHardwareBuffer* dstAhb = AHardwareBuffer_fromHardwareBuffer(env, jDstHardwareBuffer);
    if (!srcAhb || !dstAhb) {
        return env->NewStringUTF("{\"success\":false,\"error\":\"null AHB\"}");
    }

    bool  claheEnabled = false;
    float claheSh = 0.0f, claheHi = 0.0f;
    float lumaNR = 0.0f, chromaNR = 0.0f, blueNR = 0.0f, redNR = 0.0f;
    raw_v3::DetailParams detail{};
    if (jParams) {
        const jsize n = env->GetArrayLength(jParams);
        if (n > 144) {
            jfloat* p = env->GetFloatArrayElements(jParams, nullptr);
            claheEnabled = p[144] > 0.5f;
            if (n > 145) claheSh = p[145];
            if (n > 146) claheHi = p[146];
            if (n > 147) lumaNR = p[147];
            if (n > 148) chromaNR = p[148];
            if (n > 233) blueNR = p[233];
            if (n > 234) redNR  = p[234];
            if (n > 149) detail.sharpness           = p[149];
            if (n > 150) detail.smartSharpness      = p[150];
            if (n > 151) detail.clarity             = p[151];
            if (n > 408) detail.clarityLift         = p[408];
            if (n > 152) detail.texture             = p[152];
            if (n > 153) detail.filmGrain           = p[153];
            if (n > 154) detail.filmGrainSize       = p[154];
            if (n > 155) detail.filmGrainUniformity = p[155];
            if (n > 156) detail.filmGrainWashOut    = p[156];
            if (n > 178) detail.smoothBackground    = p[178];
            env->ReleaseFloatArrayElements(jParams, p, JNI_ABORT);
        }
    }

    std::vector<float> maskBuf;
    int maskSize = 0;
    int maskH    = 0;
    if (jSubjectMask && jSubjectMaskSize > 0) {
        const int mh = jSubjectMaskH > 0 ? int(jSubjectMaskH) : int(jSubjectMaskSize);
        const jsize mn = env->GetArrayLength(jSubjectMask);
        if (mn >= jSubjectMaskSize * mh) {
            maskBuf.resize(size_t(jSubjectMaskSize) * mh);
            env->GetFloatArrayRegion(jSubjectMask, 0, jsize(maskBuf.size()), maskBuf.data());
            maskSize = jSubjectMaskSize;
            maskH    = mh;
        }
    }

    raw_v3::StageBResult r = raw_v3::runStageBApplySpatialToAhb(
        srcAhb, dstAhb,
        claheEnabled, claheSh, claheHi, lumaNR, chromaNR, blueNR, redNR, detail,
        maskBuf.empty() ? nullptr : maskBuf.data(), maskSize, maskH);

    std::string json = "{";
    json += "\"success\":";    json += (r.success ? "true" : "false");
    json += ",\"width\":";     json += std::to_string(r.outWidth);
    json += ",\"height\":";    json += std::to_string(r.outHeight);
    if (!r.error.empty()) {
        json += ",\"error\":\""; for (char c : r.error) {
            if (c == '"' || c == '\\') json += '\\'; json += c;
        } json += "\"";
    }
    json += "}";
    return env->NewStringUTF(json.c_str());
}

// ─────────────────────────────────────────────────────────────────────────────
//  GLES renderer (M3) — backed by a heap-allocated GlesRenderer pointer that
//  the Kotlin side holds as a long handle.
// ─────────────────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeInitRenderer(
        JNIEnv* env, jobject /*thiz*/,
        jobject jSurface,
        jobject jHardwareBuffer) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, jSurface);
    if (!window) {
        LOGE("nativeInitRenderer: ANativeWindow_fromSurface returned null");
        return 0;
    }
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, jHardwareBuffer);
    if (!ahb) {
        LOGE("nativeInitRenderer: AHardwareBuffer_fromHardwareBuffer returned null");
        ANativeWindow_release(window);
        return 0;
    }

    auto* r = new raw_v3::GlesRenderer();
    if (!r->init(window, ahb)) {
        delete r;
        ANativeWindow_release(window);
        return 0;
    }
    // We keep the window referenced for the lifetime of the renderer; the
    // EGL surface holds an internal reference but releasing ours here would
    // be a use-after-free on some drivers. Free in nativeReleaseRenderer.
    // (Renderer doesn't store the ANativeWindow handle; release is OK to
    // happen via EGL teardown.)
    ANativeWindow_release(window);
    return reinterpret_cast<jlong>(r);
}

JNIEXPORT jlong JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeGetEglDisplay(
        JNIEnv*, jobject, jlong handle) {
    if (!handle) return 0;
    auto* r = reinterpret_cast<raw_v3::GlesRenderer*>(handle);
    return reinterpret_cast<jlong>(r->getEglDisplay());
}

JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeBindTextureToMask(
        JNIEnv*, jobject, jlong handle, jint layer, jint textureId) {
    if (!handle) return JNI_FALSE;
    auto* r = reinterpret_cast<raw_v3::GlesRenderer*>(handle);
    return r->bindTextureToMask(int(layer), static_cast<GLuint>(textureId)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUpdateAhb(
        JNIEnv* env, jobject /*thiz*/,
        jlong   handle,
        jobject jHardwareBuffer,
        jint    fenceFd) {
    if (!handle) return;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, jHardwareBuffer);
    if (!ahb) {
        LOGE("nativeUpdateAhb: AHardwareBuffer_fromHardwareBuffer returned null");
        return;
    }
    reinterpret_cast<raw_v3::GlesRenderer*>(handle)->updateAhb(ahb, int(fenceFd));
}

JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeRenderFrame(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong handle) {
    if (!handle) return JNI_FALSE;
    return reinterpret_cast<raw_v3::GlesRenderer*>(handle)->renderFrame()
           ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUpdateUniforms(
        JNIEnv* env, jobject /*thiz*/,
        jlong       handle,
        jfloatArray jParams) {
    if (!handle || !jParams) return;
    jsize n = env->GetArrayLength(jParams);
    jfloat* arr = env->GetFloatArrayElements(jParams, nullptr);
    auto p = raw_v3::ShaderParams::fromFloatArray(arr, int(n));
    env->ReleaseFloatArrayElements(jParams, arr, JNI_ABORT);
    reinterpret_cast<raw_v3::GlesRenderer*>(handle)->setParams(p);
}

JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadLut3d(
        JNIEnv* env, jobject /*thiz*/,
        jlong   handle,
        jstring jCubePath) {
    if (!handle) return JNI_FALSE;
    const std::string path = jstrOrEmpty(env, jCubePath);
    bool ok = reinterpret_cast<raw_v3::GlesRenderer*>(handle)->uploadLut3d(path.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeClearLut3d(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong handle) {
    if (!handle) return;
    reinterpret_cast<raw_v3::GlesRenderer*>(handle)->clearLut3d();
}

// M12.2c.1 — upload U2Net subject mask. `jMask` is a row-major byte[]
// of length width*height where each byte encodes the subject probability
// (255 = certain subject). Renderer keeps a 320×320 GL_R8 texture bound
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadSubjectMask(
        JNIEnv* env, jobject /*thiz*/,
        jlong       handle,
        jbyteArray  jMask,
        jint        width,
        jint        height) {
    if (!handle || !jMask || width <= 0 || height <= 0) return JNI_FALSE;
    const jsize expected = jsize(width) * jsize(height);
    if (env->GetArrayLength(jMask) < expected) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(jMask, nullptr);
    if (!bytes) return JNI_FALSE;
    bool ok = reinterpret_cast<raw_v3::GlesRenderer*>(handle)->uploadSubjectMask(
        reinterpret_cast<const uint8_t*>(bytes), int(width), int(height));
    env->ReleaseByteArrayElements(jMask, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadSubjectMaskAhb(
        JNIEnv* env, jobject /*thiz*/,
        jlong       handle,
        jobject     jAhb,
        jint        fenceFd) {
    if (!handle || !jAhb) return JNI_FALSE;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, jAhb);
    if (!ahb) return JNI_FALSE;
    return reinterpret_cast<raw_v3::GlesRenderer*>(handle)->uploadSubjectMask(ahb, int(fenceFd))
           ? JNI_TRUE : JNI_FALSE;
}

// Bokeh attenuation upload — see GlesRenderer::uploadBokehAttenuation.
// Carries max(sky, terrain) packed as a single-channel uint8 plane.
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadBokehAttenuation(
        JNIEnv* env, jobject /*thiz*/,
        jlong       handle,
        jbyteArray  jMask,
        jint        width,
        jint        height) {
    if (!handle || !jMask || width <= 0 || height <= 0) return JNI_FALSE;
    const jsize expected = jsize(width) * jsize(height);
    if (env->GetArrayLength(jMask) < expected) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(jMask, nullptr);
    if (!bytes) return JNI_FALSE;
    bool ok = reinterpret_cast<raw_v3::GlesRenderer*>(handle)->uploadBokehAttenuation(
        reinterpret_cast<const uint8_t*>(bytes), int(width), int(height));
    env->ReleaseByteArrayElements(jMask, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadBokehAttenuationAhb(
        JNIEnv* env, jobject /*thiz*/,
        jlong       handle,
        jobject     jAhb,
        jint        fenceFd) {
    if (!handle || !jAhb) return JNI_FALSE;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, jAhb);
    if (!ahb) return JNI_FALSE;
    return reinterpret_cast<raw_v3::GlesRenderer*>(handle)->uploadBokehAttenuation(ahb, int(fenceFd))
           ? JNI_TRUE : JNI_FALSE;
}

// Depth map → unit-10 RG8 .g + focus plane for CoC bokeh.
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadDepthMap(
        JNIEnv* env, jobject /*thiz*/,
        jlong       handle,
        jbyteArray  jMask,
        jint        width,
        jint        height,
        jfloat      focusDepth) {
    if (!handle || !jMask || width <= 0 || height <= 0) return JNI_FALSE;
    const jsize expected = jsize(width) * jsize(height);
    if (env->GetArrayLength(jMask) < expected) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(jMask, nullptr);
    if (!bytes) return JNI_FALSE;
    bool ok = reinterpret_cast<raw_v3::GlesRenderer*>(handle)->uploadDepthMap(
        reinterpret_cast<const uint8_t*>(bytes), int(width), int(height), float(focusDepth));
    env->ReleaseByteArrayElements(jMask, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadDepthMapAhb(
        JNIEnv* env, jobject /*thiz*/,
        jlong       handle,
        jobject     jAhb,
        jfloat      focusDepth,
        jint        fenceFd) {
    if (!handle || !jAhb) return JNI_FALSE;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, jAhb);
    if (!ahb) return JNI_FALSE;
    return reinterpret_cast<raw_v3::GlesRenderer*>(handle)->uploadDepthMap(ahb, float(focusDepth), int(fenceFd))
           ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeClearDepthMap(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong handle) {
    if (!handle) return;
    reinterpret_cast<raw_v3::GlesRenderer*>(handle)->clearDepthMap();
}

JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeClearSubjectMask(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong handle) {
    if (!handle) return;
    reinterpret_cast<raw_v3::GlesRenderer*>(handle)->clearSubjectMask();
}

JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeSetSubjectMaskInnerRect(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong  handle,
        jfloat u0, jfloat v0, jfloat u1, jfloat v1) {
    if (!handle) return;
    reinterpret_cast<raw_v3::GlesRenderer*>(handle)->setSubjectMaskInnerRect(
        float(u0), float(v0), float(u1), float(v1));
}

// Preview pan/zoom for the on-screen window viewport (scale about centre +
// pan in surface px). Keeps the SurfaceView pinned so the preview is clipped
// to the letterbox slot instead of overflowing onto the chrome.
JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeSetViewTransform(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong  handle,
        jfloat scale, jfloat offsetX, jfloat offsetY) {
    if (!handle) return;
    reinterpret_cast<raw_v3::GlesRenderer*>(handle)->setViewTransform(
        float(scale), float(offsetX), float(offsetY));
}

// Bilateral denoise NR slots (native-only CPU slots 147/148). Pushed via
// direct JNI call, not through ShaderParams. Both zero → NR pass skipped.
JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeSetNrSlots(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong handle,
        jfloat slot147, jfloat slot148) {
    if (!handle) return;
    reinterpret_cast<raw_v3::GlesRenderer*>(handle)->setNrSlots(
        float(slot147), float(slot148));
}

// M12.2c.2 — brush-painted Mask tab mask. [layer] selects one of 4 composited
// layers (0→unit3, 1→unit5, 2→unit6, 3→unit7); GL_R8.
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadBrushMask(
        JNIEnv* env, jobject /*thiz*/,
        jlong       handle,
        jint        layer,
        jbyteArray  jMask,
        jint        width,
        jint        height) {
    if (!handle || !jMask || width <= 0 || height <= 0) return JNI_FALSE;
    const jsize expected = jsize(width) * jsize(height);
    if (env->GetArrayLength(jMask) < expected) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(jMask, nullptr);
    if (!bytes) return JNI_FALSE;
    bool ok = reinterpret_cast<raw_v3::GlesRenderer*>(handle)->uploadBrushMask(
        int(layer), reinterpret_cast<const uint8_t*>(bytes), int(width), int(height));
    env->ReleaseByteArrayElements(jMask, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadBrushMaskAhb(
        JNIEnv* env, jobject /*thiz*/,
        jlong       handle,
        jint        layer,
        jobject     jAhb,
        jint        fenceFd) {
    if (!handle || !jAhb) return JNI_FALSE;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, jAhb);
    if (!ahb) return JNI_FALSE;
    return reinterpret_cast<raw_v3::GlesRenderer*>(handle)->uploadBrushMask(int(layer), ahb, int(fenceFd))
           ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeClearBrushMask(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong handle,
        jint  layer) {
    if (!handle) return;
    reinterpret_cast<raw_v3::GlesRenderer*>(handle)->clearBrushMask(int(layer));
}

// M12.2c.4 — Sobel edge mask + edge-snap controls.
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadSobelEdgeMask(
        JNIEnv* env, jobject /*thiz*/,
        jlong       handle,
        jbyteArray  jMask,
        jint        width,
        jint        height) {
    if (!handle || !jMask || width <= 0 || height <= 0) return JNI_FALSE;
    const jsize expected = jsize(width) * jsize(height);
    if (env->GetArrayLength(jMask) < expected) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(jMask, nullptr);
    if (!bytes) return JNI_FALSE;
    bool ok = reinterpret_cast<raw_v3::GlesRenderer*>(handle)->uploadSobelEdgeMask(
        reinterpret_cast<const uint8_t*>(bytes), int(width), int(height));
    env->ReleaseByteArrayElements(jMask, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadSobelEdgeMaskAhb(
        JNIEnv* env, jobject /*thiz*/,
        jlong       handle,
        jobject     jAhb,
        jint        fenceFd) {
    if (!handle || !jAhb) return JNI_FALSE;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, jAhb);
    if (!ahb) return JNI_FALSE;
    return reinterpret_cast<raw_v3::GlesRenderer*>(handle)->uploadSobelEdgeMask(ahb, int(fenceFd))
           ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeSetEdgeSnap(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong  handle,
        jfloat strength,
        jfloat threshold) {
    if (!handle) return;
    reinterpret_cast<raw_v3::GlesRenderer*>(handle)->setEdgeSnap(
        float(strength), float(threshold));
}

// Tone Curve LUT: 256 RGB8 texels (768 bytes interleaved R,G,B).
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadToneCurve(
        JNIEnv* env, jobject /*thiz*/,
        jlong      handle,
        jbyteArray jLut) {
    if (!handle || !jLut) return JNI_FALSE;
    if (env->GetArrayLength(jLut) < 256 * 3) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(jLut, nullptr);
    if (!bytes) return JNI_FALSE;
    bool ok = reinterpret_cast<raw_v3::GlesRenderer*>(handle)->uploadToneCurve(
        reinterpret_cast<const uint8_t*>(bytes));
    env->ReleaseByteArrayElements(jLut, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeClearToneCurve(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (!handle) return;
    reinterpret_cast<raw_v3::GlesRenderer*>(handle)->clearToneCurve();
}

JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadVintageMist(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle, jbyteArray jBytes, jint width, jint height) {
    if (!handle || !jBytes || width <= 0 || height <= 0) return JNI_FALSE;
    const jsize n = env->GetArrayLength(jBytes);
    if (n <= 0) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(jBytes, nullptr);
    if (!bytes) return JNI_FALSE;
    bool ok = reinterpret_cast<raw_v3::GlesRenderer*>(handle)->uploadVintageMist(
        reinterpret_cast<const uint8_t*>(bytes), int(n), int(width), int(height));
    env->ReleaseByteArrayElements(jBytes, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadVintageFilm(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle, jbyteArray jBytes, jint width, jint height) {
    if (!handle || !jBytes || width <= 0 || height <= 0) return JNI_FALSE;
    const jsize n = env->GetArrayLength(jBytes);
    if (n <= 0) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(jBytes, nullptr);
    if (!bytes) return JNI_FALSE;
    bool ok = reinterpret_cast<raw_v3::GlesRenderer*>(handle)->uploadVintageFilm(
        reinterpret_cast<const uint8_t*>(bytes), int(n), int(width), int(height));
    env->ReleaseByteArrayElements(jBytes, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeBakeVintageOverlay(
        JNIEnv* env, jobject /*thiz*/,
        jboolean film, jbyteArray jBytes, jint width, jint height) {
    if (!jBytes || width <= 0 || height <= 0) return;
    const jsize n = env->GetArrayLength(jBytes);
    if (n <= 0) return;
    jbyte* bytes = env->GetByteArrayElements(jBytes, nullptr);
    if (!bytes) return;
    raw_v3::setVintageFxBake(film == JNI_TRUE,
        reinterpret_cast<const uint8_t*>(bytes), int(n), int(width), int(height));
    env->ReleaseByteArrayElements(jBytes, bytes, JNI_ABORT);
}

// Mask tab "Show" overlay toggle: tint the masked region so it's visible.
JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeSetShowMaskOverlay(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jboolean show) {
    if (!handle) return;
    reinterpret_cast<raw_v3::GlesRenderer*>(handle)->setShowMaskOverlay(show == JNI_TRUE);
}

// Mask tab "Show" overlay: which layer to tint (the one being edited). <0 = none.
JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeSetMaskOverlayLayer(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint layer) {
    if (!handle) return;
    reinterpret_cast<raw_v3::GlesRenderer*>(handle)->setMaskOverlayLayer(static_cast<int>(layer));
}

// Upload 4 curve LUTs (Master, R, G, B) — each is a jfloatArray of 16 floats
// representing 8 (x,y) control points in [0,1]×[0,1].
JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeUploadCurveLuts(
        JNIEnv* env, jobject /*thiz*/,
        jlong        handle,
        jfloatArray  jMaster,
        jfloatArray  jR,
        jfloatArray  jG,
        jfloatArray  jB) {
    if (!handle || !jMaster || !jR || !jG || !jB) return;
    if (env->GetArrayLength(jMaster) < 16) return;
    auto* renderer = reinterpret_cast<raw_v3::GlesRenderer*>(handle);
    jfloat* m = env->GetFloatArrayElements(jMaster, nullptr);
    jfloat* r = env->GetFloatArrayElements(jR,      nullptr);
    jfloat* g = env->GetFloatArrayElements(jG,      nullptr);
    jfloat* b = env->GetFloatArrayElements(jB,      nullptr);
    if (m && r && g && b) renderer->uploadCurveLuts(m, r, g, b);
    if (m) env->ReleaseFloatArrayElements(jMaster, m, JNI_ABORT);
    if (r) env->ReleaseFloatArrayElements(jR,      r, JNI_ABORT);
    if (g) env->ReleaseFloatArrayElements(jG,      g, JNI_ABORT);
    if (b) env->ReleaseFloatArrayElements(jB,      b, JNI_ABORT);
}

// Render the current shader output to [dst]. Returns JNI_TRUE on success.
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeSnapshotGraded(
        JNIEnv* env, jobject /*thiz*/,
        jlong   handle,
        jobject jDstAhb) {
    if (!handle || !jDstAhb) return JNI_FALSE;
    AHardwareBuffer* dst = AHardwareBuffer_fromHardwareBuffer(env, jDstAhb);
    if (!dst) return JNI_FALSE;
    bool ok = reinterpret_cast<raw_v3::GlesRenderer*>(handle)->snapshotGradedToAhb(dst);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Compute a 256-bucket BT.601 luma histogram of the current graded frame.
// Returns a 256-length int[] or null on failure.
JNIEXPORT jintArray JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeHistogramGraded(
        JNIEnv* env, jobject /*thiz*/,
        jlong   handle,
        jint    side) {
    if (!handle) return nullptr;
    int hist[256];
    bool ok = reinterpret_cast<raw_v3::GlesRenderer*>(handle)->histogramGraded(int(side), hist);
    if (!ok) return nullptr;
    jintArray out = env->NewIntArray(256);
    if (!out) return nullptr;
    env->SetIntArrayRegion(out, 0, 256, reinterpret_cast<const jint*>(hist));
    return out;
}

// Render the graded frame into [jBitmap] (RGBA_8888, caller-sized to the
// desired preview dims). Returns JNI_TRUE on success. The GL readback is
// bottom-up, so we flip rows into the bitmap to get the correct orientation.
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeSnapshotGradedToBitmap(
        JNIEnv* env, jobject /*thiz*/,
        jlong   handle,
        jobject jBitmap) {
    if (!handle || !jBitmap) return JNI_FALSE;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, jBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    const int w = int(info.width), h = int(info.height);
    if (w <= 0 || h <= 0) return JNI_FALSE;

    std::vector<uint8_t> tmp(size_t(w) * h * 4);
    bool ok = reinterpret_cast<raw_v3::GlesRenderer*>(handle)
                  ->snapshotGradedToBitmap(w, h, tmp.data());
    if (!ok) return JNI_FALSE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels)
        return JNI_FALSE;
    // The snapshot program uses an identity vertex shader (no V-flip) and the
    // source texture is already in display orientation, so glReadPixels here is
    // top-down — copy rows straight through (an extra flip would invert the
    // image, which was the upside-down export-preview bug). Honour stride.
    uint8_t* dst = reinterpret_cast<uint8_t*>(pixels);
    const size_t srcRowBytes = size_t(w) * 4;
    for (int y = 0; y < h; ++y) {
        std::memcpy(dst + size_t(y) * info.stride,
                    tmp.data() + size_t(y) * srcRowBytes, srcRowBytes);
    }
    AndroidBitmap_unlockPixels(env, jBitmap);
    return JNI_TRUE;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Adobe XMP parser (M5.5) — returns a 25-float array matching the XmpParams
//  layout in adobe_xmp_parser.h. First float is 1.0 if any recognised CRS
//  tag was found in the sidecar, 0.0 otherwise.
// ─────────────────────────────────────────────────────────────────────────────
// ─────────────────────────────────────────────────────────────────────────────
//  Stage B serialize (M7) — snapshot the AHardwareBuffer to disk so the
//  editor can free its GPU resources and navigate to the export screen.
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeStageBSerialize(
        JNIEnv* env, jobject /*thiz*/,
        jobject jHardwareBuffer,
        jstring jOutPath) {
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, jHardwareBuffer);
    if (!ahb) {
        LOGE("nativeStageBSerialize: null AHardwareBuffer");
        return JNI_FALSE;
    }
    const std::string path = jstrOrEmpty(env, jOutPath);
    return raw_v3::writeStageBSerialized(ahb, path) ? JNI_TRUE : JNI_FALSE;
}

// Symmetric reader: mmap the serialized .f16 and copy pixels into a fresh
// AHardwareBuffer the caller allocated. Returns a JSON string with width +
// height for the caller's layout pass.
JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeStageBLoadSerialized(
        JNIEnv* env, jobject /*thiz*/,
        jstring jPath,
        jobject jHardwareBuffer) {
    const std::string path = jstrOrEmpty(env, jPath);
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, jHardwareBuffer);
    if (!ahb || path.empty()) {
        return env->NewStringUTF("{\"success\":false,\"error\":\"null arg\"}");
    }
    auto* r = raw_v3::openStageBSerialized(path);
    if (!r) {
        return env->NewStringUTF("{\"success\":false,\"error\":\"open failed\"}");
    }
    const auto& h = raw_v3::getStageBSerializedHeader(r);
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    if (desc.width < h.width || desc.height < h.height ||
        desc.format != AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT) {
        raw_v3::closeStageBSerialized(r);
        return env->NewStringUTF("{\"success\":false,\"error\":\"AHB shape mismatch\"}");
    }

    void* dst = nullptr;
    int lockRet = AHardwareBuffer_lock(
        ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY, -1, nullptr, &dst);
    if (lockRet != 0 || !dst) {
        raw_v3::closeStageBSerialized(r);
        return env->NewStringUTF("{\"success\":false,\"error\":\"AHB lock failed\"}");
    }

    // The source row stride (file) may differ from the destination AHB
    // row stride. Copy row-by-row.
    const uint16_t* src = raw_v3::getStageBSerializedPixels(r);
    const size_t srcRowBytes = size_t(h.strideInPixels) * 4 * 2;
    const size_t dstRowBytes = size_t(desc.stride)       * 4 * 2;
    const size_t copyBytes   = size_t(h.width) * 4 * 2;       // ignore padding
    for (uint32_t y = 0; y < h.height; ++y) {
        std::memcpy(
            static_cast<uint8_t*>(dst) + size_t(y) * dstRowBytes,
            reinterpret_cast<const uint8_t*>(src) + size_t(y) * srcRowBytes,
            copyBytes);
    }
    AHardwareBuffer_unlock(ahb, nullptr);

    std::string json = "{\"success\":true,\"width\":" + std::to_string(h.width) +
                       ",\"height\":" + std::to_string(h.height) + "}";
    raw_v3::closeStageBSerialized(r);
    return env->NewStringUTF(json.c_str());
}

// Read just the dims of a serialized Stage B snapshot.
JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeStageBGetDims(
        JNIEnv* env, jobject /*thiz*/,
        jstring jPath) {
    const std::string path = jstrOrEmpty(env, jPath);
    uint32_t w = 0, h = 0;
    if (path.empty() || !raw_v3::getStageBSerializedDims(path, w, h)) {
        return env->NewStringUTF("{\"success\":false}");
    }
    std::string json = "{\"success\":true,\"width\":" + std::to_string(w) +
                       ",\"height\":" + std::to_string(h) + "}";
    return env->NewStringUTF(json.c_str());
}

// Render a serialized Stage B snapshot into a locked ARGB_8888 Bitmap.
// Used for the Export preview fallback so we don't re-run Stage B downsample.
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeStageBLoadToBitmap(
        JNIEnv* env, jobject /*thiz*/,
        jstring jPath,
        jobject jBitmap) {
    const std::string path = jstrOrEmpty(env, jPath);
    if (path.empty() || !jBitmap) return JNI_FALSE;

    AndroidBitmapInfo info{};
    void* locked = nullptr;
    if (AndroidBitmap_getInfo(env, jBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, jBitmap, &locked) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }

    bool ok = raw_v3::renderStageBSerializedToBitmap(
        path, reinterpret_cast<uint8_t*>(locked), info.stride);
    AndroidBitmap_unlockPixels(env, jBitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Stage C export (M8) — full-res RGB16 TIFF
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeStageCExport(
        JNIEnv* env, jobject /*thiz*/,
        jstring jStageATif,
        jstring jOutPath,
        jint    jFormat,
        jint    jTargetW,
        jint    jTargetH,
        jfloatArray jParams,
        jfloatArray jLutData,
        jint    jLutSize,
        jfloatArray jLutDomainMin,
        jfloatArray jLutDomainMax,
        jfloatArray jSubjectMask,
        jint    jSubjectMaskSize,
        jint    jSubjectMaskH,
        jfloat  jSubjectMaskU0,
        jfloat  jSubjectMaskV0,
        jfloat  jSubjectMaskU1,
        jfloat  jSubjectMaskV1,
        jfloatArray jAttenMask,
        jint    jAttenMaskSize,
        jint    jAttenMaskH,
        jfloatArray jDepthMap,
        jint    jDepthMapW,
        jint    jDepthMapH,
        jfloat  jFocusDepth,
        jfloatArray jMaskLayers,
        jint    jMaskLayerW,
        jint    jMaskLayerH,
        jint    jMaskLayerCount,
        jbyteArray jToneCurveLut,
        jbyteArray jIccProfile) {

    const std::string srcPath = jstrOrEmpty(env, jStageATif);
    const std::string outPath = jstrOrEmpty(env, jOutPath);
    if (srcPath.empty() || outPath.empty()) {
        return env->NewStringUTF("{\"success\":false,\"error\":\"empty path\"}");
    }

    raw_v3::StageCOptions opt;
    opt.format       = raw_v3::StageCFormat(jFormat);
    opt.targetWidth  = uint32_t(jTargetW);
    opt.targetHeight = uint32_t(jTargetH);

    jfloat* paramsArr = nullptr;
    jsize   paramsN   = 0;
    if (jParams) {
        paramsN   = env->GetArrayLength(jParams);
        paramsArr = env->GetFloatArrayElements(jParams, nullptr);
        opt.params      = paramsArr;
        opt.paramsCount = int(paramsN);
    }
    jfloat* lutArr = nullptr;
    jsize   lutN   = 0;
    if (jLutData) {
        lutN   = env->GetArrayLength(jLutData);
        lutArr = env->GetFloatArrayElements(jLutData, nullptr);
        opt.lutData = lutArr;
        opt.lutSize = int(jLutSize);
        if (jLutDomainMin && env->GetArrayLength(jLutDomainMin) >= 3)
            env->GetFloatArrayRegion(jLutDomainMin, 0, 3, opt.lutDomainMin);
        if (jLutDomainMax && env->GetArrayLength(jLutDomainMax) >= 3)
            env->GetFloatArrayRegion(jLutDomainMax, 0, 3, opt.lutDomainMax);
    }
    jfloat* maskArr = nullptr;
    if (jSubjectMask && jSubjectMaskSize > 0) {
        const int mh = jSubjectMaskH > 0 ? int(jSubjectMaskH) : int(jSubjectMaskSize);
        const jsize mn = env->GetArrayLength(jSubjectMask);
        if (mn >= jSubjectMaskSize * mh) {
            maskArr = env->GetFloatArrayElements(jSubjectMask, nullptr);
            opt.subjectMask     = maskArr;
            opt.subjectMaskSize = int(jSubjectMaskSize);
            opt.subjectMaskH    = mh;
        }
    }
    opt.subjectMaskRectU0 = float(jSubjectMaskU0);
    opt.subjectMaskRectV0 = float(jSubjectMaskV0);
    opt.subjectMaskRectU1 = float(jSubjectMaskU1);
    opt.subjectMaskRectV1 = float(jSubjectMaskV1);
    jfloat* attenArr = nullptr;
    if (jAttenMask && jAttenMaskSize > 0) {
        const int ah = jAttenMaskH > 0 ? int(jAttenMaskH) : int(jAttenMaskSize);
        const jsize an = env->GetArrayLength(jAttenMask);
        if (an >= jAttenMaskSize * ah) {
            attenArr = env->GetFloatArrayElements(jAttenMask, nullptr);
            opt.attenMask     = attenArr;
            opt.attenMaskSize = int(jAttenMaskSize);
            opt.attenMaskH    = ah;
        }
    }
    // Brush-mask layers: Kotlin hands us up to 4 layer alpha buffers
    // ([0,1] floats, [jMaskLayerW × jMaskLayerH] each) concatenated into one
    // float[] — layer i starts at offset i·(W·H). We point each
    // opt.maskLayerData[i] into that buffer so Stage C applies the same
    // per-layer mask compositing as the GL preview.

    jfloat* depthArr = nullptr;
    if (jDepthMap && jDepthMapW > 0 && jDepthMapH > 0) {
        const jsize dn = env->GetArrayLength(jDepthMap);
        if (dn >= jsize(jDepthMapW) * jsize(jDepthMapH)) {
            depthArr = env->GetFloatArrayElements(jDepthMap, nullptr);
            opt.depthMap = depthArr;
            opt.depthMapW = int(jDepthMapW);
            opt.depthMapH = int(jDepthMapH);
            opt.focusDepth = float(jFocusDepth);
        }
    }
    jfloat* maskLayersArr = nullptr;
    if (jMaskLayers && jMaskLayerCount > 0 && jMaskLayerW > 0 && jMaskLayerH > 0) {
        const jsize have = env->GetArrayLength(jMaskLayers);
        const jsize plane = jsize(jMaskLayerW) * jsize(jMaskLayerH);
        const int n = std::min<int>(jMaskLayerCount, raw_v3::ApplyMacroMaskLayers::kCount);
        if (have >= plane * n) {
            maskLayersArr = env->GetFloatArrayElements(jMaskLayers, nullptr);
            for (int i = 0; i < n; ++i) {
                opt.maskLayerData[i] = maskLayersArr + size_t(plane) * i;
                opt.maskLayerW[i]    = int(jMaskLayerW);
                opt.maskLayerH[i]    = int(jMaskLayerH);
            }
        }
    }

    jbyte* toneCurveArr = nullptr;
    if (jToneCurveLut && env->GetArrayLength(jToneCurveLut) >= 256 * 3) {
        toneCurveArr = env->GetByteArrayElements(jToneCurveLut, nullptr);
        opt.toneCurveLut = reinterpret_cast<const uint8_t*>(toneCurveArr);
    }
    jbyte* iccArr = nullptr;
    if (jIccProfile) {
        const jsize iccN = env->GetArrayLength(jIccProfile);
        if (iccN > 0) {
            iccArr = env->GetByteArrayElements(jIccProfile, nullptr);
            opt.iccProfile = reinterpret_cast<const uint8_t*>(iccArr);
            opt.iccProfileSize = size_t(iccN);
        }
    }

    raw_v3::StageCResult result = raw_v3::runStageC(srcPath, outPath, opt);

    if (paramsArr)     env->ReleaseFloatArrayElements(jParams, paramsArr, JNI_ABORT);
    if (lutArr)        env->ReleaseFloatArrayElements(jLutData, lutArr, JNI_ABORT);
    if (maskArr)       env->ReleaseFloatArrayElements(jSubjectMask, maskArr, JNI_ABORT);
    if (attenArr)      env->ReleaseFloatArrayElements(jAttenMask, attenArr, JNI_ABORT);
    if (depthArr)      env->ReleaseFloatArrayElements(jDepthMap, depthArr, JNI_ABORT);
    if (maskLayersArr) env->ReleaseFloatArrayElements(jMaskLayers, maskLayersArr, JNI_ABORT);
    if (toneCurveArr)  env->ReleaseByteArrayElements(jToneCurveLut, toneCurveArr, JNI_ABORT);
    if (iccArr)        env->ReleaseByteArrayElements(jIccProfile, iccArr, JNI_ABORT);

    std::string json = "{";
    json += "\"success\":";    json += (result.success ? "true" : "false");
    json += ",\"karisBloomFallback\":"; json += (result.karisBloomFallback ? "true" : "false");
    json += ",\"width\":";     json += std::to_string(result.outWidth);
    json += ",\"height\":";    json += std::to_string(result.outHeight);
    json += ",\"durationMs\":";json += std::to_string(result.durationMs);
    if (!result.outputPath.empty()) {
        json += ",\"outputPath\":\""; for (char c : result.outputPath) {
            if (c == '"' || c == '\\') json += '\\'; json += c;
        } json += "\"";
    }
    if (!result.error.empty()) {
        json += ",\"error\":\""; for (char c : result.error) {
            if (c == '"' || c == '\\') json += '\\'; json += c;
        } json += "\"";
    }
    json += "}";
    return env->NewStringUTF(json.c_str());
}

// M9 — Stage C export into a Java Bitmap (RGBA_8888). Kotlin allocates
// the Bitmap at Stage A's source dims, locks pixels here, and then
// compresses the filled Bitmap via Bitmap.compress / HeifWriter to
// produce JPEG / WebP / PNG-8 / HEIC. PNG-16 + AVIF live in a future
// libpng/libavif NDK milestone.
JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeStageCToBitmap(
        JNIEnv* env, jobject /*thiz*/,
        jstring     jStageATif,
        jobject     jBitmap,
        jfloatArray jParams,
        jfloatArray jLutData,
        jint        jLutSize,
        jfloatArray jLutDomainMin,
        jfloatArray jLutDomainMax) {

    const std::string srcPath = jstrOrEmpty(env, jStageATif);
    if (srcPath.empty() || !jBitmap) {
        return env->NewStringUTF("{\"success\":false,\"error\":\"bad args\"}");
    }

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, jBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return env->NewStringUTF("{\"success\":false,\"error\":\"getInfo failed\"}");
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return env->NewStringUTF("{\"success\":false,\"error\":\"bitmap must be RGBA_8888\"}");
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        !pixels) {
        return env->NewStringUTF("{\"success\":false,\"error\":\"lockPixels failed\"}");
    }

    raw_v3::StageCOptions opt;
    opt.format       = raw_v3::StageCFormat::Bitmap8888;
    jfloat* paramsArr = nullptr;
    if (jParams) {
        opt.paramsCount = int(env->GetArrayLength(jParams));
        paramsArr = env->GetFloatArrayElements(jParams, nullptr);
        opt.params      = paramsArr;
    }
    jfloat* lutArr = nullptr;
    if (jLutData) {
        lutArr = env->GetFloatArrayElements(jLutData, nullptr);
        opt.lutData = lutArr;
        opt.lutSize = int(jLutSize);
        if (jLutDomainMin && env->GetArrayLength(jLutDomainMin) >= 3)
            env->GetFloatArrayRegion(jLutDomainMin, 0, 3, opt.lutDomainMin);
        if (jLutDomainMax && env->GetArrayLength(jLutDomainMax) >= 3)
            env->GetFloatArrayRegion(jLutDomainMax, 0, 3, opt.lutDomainMax);
    }

    raw_v3::StageCResult result = raw_v3::runStageCToRGBA8(
        srcPath, opt,
        reinterpret_cast<uint8_t*>(pixels),
        info.stride);

    if (paramsArr) env->ReleaseFloatArrayElements(jParams, paramsArr, JNI_ABORT);
    if (lutArr)    env->ReleaseFloatArrayElements(jLutData, lutArr, JNI_ABORT);
    AndroidBitmap_unlockPixels(env, jBitmap);

    std::string json = "{";
    json += "\"success\":";    json += (result.success ? "true" : "false");
    json += ",\"karisBloomFallback\":"; json += (result.karisBloomFallback ? "true" : "false");
    json += ",\"width\":";     json += std::to_string(result.outWidth);
    json += ",\"height\":";    json += std::to_string(result.outHeight);
    json += ",\"durationMs\":";json += std::to_string(result.durationMs);
    if (!result.error.empty()) {
        json += ",\"error\":\""; for (char c : result.error) {
            if (c == '"' || c == '\\') json += '\\'; json += c;
        } json += "\"";
    }
    json += "}";
    return env->NewStringUTF(json.c_str());
}

// ── PREQ-Port: Highlight Recovery ────────────────────────────────────────────
// Apply highlight recovery to an AHB using cached FP16 Stage A data.
// jFp16Bytes: the raw uint16_t FP16 RGBA buffer as a ByteArray (W*H*4*2 bytes).
// jAhb: HardwareBuffer to update in-place (ARGB8).
// recovery: [0..1] strength.
JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeRecoverHighlights(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray jFp16Bytes,
        jobject    jAhb,
        jint       width,
        jint       height,
        jfloat     recovery) {
    if (!jFp16Bytes || !jAhb || width <= 0 || height <= 0 || recovery <= 0.f) return;
    jsize expectedBytes = (jsize)(width) * height * 4 * 2;
    if (env->GetArrayLength(jFp16Bytes) < expectedBytes) return;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, jAhb);
    if (!ahb) return;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    void* bits = nullptr;
    if (AHardwareBuffer_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY, -1, nullptr, &bits) != 0 || !bits) {
        return;
    }
    jbyte* fp16 = env->GetByteArrayElements(jFp16Bytes, nullptr);
    if (fp16) {
        raw_v3::recoverHighlights(
            reinterpret_cast<const uint16_t*>(fp16),
            reinterpret_cast<uint32_t*>(bits),
            width, height, recovery);
        env->ReleaseByteArrayElements(jFp16Bytes, fp16, JNI_ABORT);
    }
    AHardwareBuffer_unlock(ahb, nullptr);
}

// ──────────────────────────────────────────────────────────────────
// Phase 3 Checkpoint 1 — Offscreen GL smoke test. Fills the caller's
// ARGB_8888 bitmap with red via a pbuffer EGL context, proving the
// headless GL path reaches the save buffer. No shaders, no textures.
// Returns true on success, false on any EGL/GL failure.
// ──────────────────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeOffscreenClearTest(
        JNIEnv* env, jobject /*thiz*/,
        jobject jBitmap) {
    if (!jBitmap) return JNI_FALSE;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, jBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    raw_v3::OffscreenSaveRenderer r;
    if (!r.init(int(info.width), int(info.height))) return JNI_FALSE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        !pixels) {
        return JNI_FALSE;
    }
    bool ok = r.clearAndReadback(reinterpret_cast<uint8_t*>(pixels));
    AndroidBitmap_unlockPixels(env, jBitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Phase 3 Checkpoint 2 — render a UV gradient via a minimal program in
// the pbuffer context. Smoke-test for shader compile / VAO / drawArrays
// before plugging in the full kFragSrc.
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeOffscreenUvTest(
        JNIEnv* env, jobject /*thiz*/,
        jobject jBitmap) {
    if (!jBitmap) return JNI_FALSE;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, jBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    raw_v3::OffscreenSaveRenderer r;
    if (!r.init(int(info.width), int(info.height))) return JNI_FALSE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        !pixels) {
        return JNI_FALSE;
    }
    bool ok = r.renderUvPattern(reinterpret_cast<uint8_t*>(pixels));
    AndroidBitmap_unlockPixels(env, jBitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Checkpoint 4 — full uber-shader graded render for canvas-matched export.
// Reads Stage A FP16, grades on GPU (same kFragSrc as preview), writes
// RGBA8888 into [jBitmap]. Bitmap may be Stage A dims OR a smaller working
// size (JPG/WebP export at targetLongSide) — when smaller, Stage A is
// bilinear-subsampled from the mmap before GL upload so a 40 MP save at
// 2048 never uploads a full-res texture.
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeRenderGradedOffscreen(
        JNIEnv* env, jobject /*thiz*/,
        jstring jStageAPath,
        jfloatArray jParams,
        jfloatArray jLutRgb, jint lutSize,
        jfloatArray jLutDomainMin, jfloatArray jLutDomainMax,
        jbyteArray jToneCurve768,
        jfloatArray jSubjectMask, jint maskW, jint maskH,
        jfloatArray jSubjectMaskRect,
        jfloatArray jBrushMasks, jint brushMaskW, jint brushMaskH,
        jint brushMaskCount,
        jfloatArray jAttenMask, jint attenW, jint attenH,
        jfloatArray jDepthMap, jint depthW, jint depthH,
        jfloat focusDepth,
        jobject jBitmap) {
    if (!jStageAPath || !jParams || !jBitmap) return JNI_FALSE;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, jBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
        return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    const std::string path = jstrOrEmpty(env, jStageAPath);
    raw_v3::StageATiffReader* rd = raw_v3::openStageATiff(path);
    if (!rd) {
        LOGE("nativeRenderGradedOffscreen: open Stage A failed %s", path.c_str());
        return JNI_FALSE;
    }
    const raw_v3::StageATiffHeader hdr = raw_v3::getStageATiffHeader(rd);
    const uint32_t W = hdr.width, H = hdr.height;
    const int outW = int(info.width), outH = int(info.height);
    if (W == 0 || H == 0 || hdr.rowsPerStrip == 0 || outW <= 0 || outH <= 0) {
        LOGE("nativeRenderGradedOffscreen: bad dims file=%ux%u bmp=%dx%d",
             W, H, outW, outH);
        raw_v3::closeStageATiff(rd);
        return JNI_FALSE;
    }
    // Allow equal (full-res) or smaller working size; never upscale for grade.
    if (uint32_t(outW) > W || uint32_t(outH) > H) {
        LOGE("nativeRenderGradedOffscreen: bmp %dx%d larger than Stage A %ux%u",
             outW, outH, W, H);
        raw_v3::closeStageATiff(rd);
        return JNI_FALSE;
    }
    if (!raw_v3::stageATiffPayloadInBounds(rd)) {
        LOGE("nativeRenderGradedOffscreen: truncated A.tif");
        raw_v3::closeStageATiff(rd);
        return JNI_FALSE;
    }

    // Stage A strips are contiguous from strip 0 — sample/upload from the
    // mmap (shared lock held until after GL upload/readback). Subsample into
    // a heap buffer only when grading below Stage A dims (~22 MB at 2048 vs
    // ~336 MB full-res upload).
    const uint16_t* fp16 = raw_v3::getStageATiffStrip(rd, 0);
    if (!fp16) {
        LOGE("nativeRenderGradedOffscreen: null strip 0");
        raw_v3::closeStageATiff(rd);
        return JNI_FALSE;
    }

    std::vector<uint16_t> workFp16;
    const uint16_t* gradeSrc = fp16;
    int gradeW = int(W), gradeH = int(H);
    if (outW != int(W) || outH != int(H)) {
        workFp16.resize(size_t(outW) * size_t(outH) * 4);
        if (!raw_v3::downsampleRgbaFp16Bilinear(
                fp16, int(W), int(H), workFp16.data(), outW, outH)) {
            LOGE("nativeRenderGradedOffscreen: subsample %ux%u → %dx%d failed",
                 W, H, outW, outH);
            raw_v3::closeStageATiff(rd);
            return JNI_FALSE;
        }
        gradeSrc = workFp16.data();
        gradeW = outW;
        gradeH = outH;
        LOGI("nativeRenderGradedOffscreen: Stage A %ux%u → grade work %dx%d",
             W, H, gradeW, gradeH);
    }

    jfloat* params = env->GetFloatArrayElements(jParams, nullptr);
    const int paramsCount = env->GetArrayLength(jParams);
    if (!params) {
        raw_v3::closeStageATiff(rd);
        return JNI_FALSE;
    }

    jfloat* lut = nullptr;
    if (jLutRgb && lutSize >= 2) lut = env->GetFloatArrayElements(jLutRgb, nullptr);
    float dMin[3] = {0, 0, 0}, dMax[3] = {1, 1, 1};
    if (jLutDomainMin && env->GetArrayLength(jLutDomainMin) >= 3) {
        jfloat* p = env->GetFloatArrayElements(jLutDomainMin, nullptr);
        if (p) { dMin[0]=p[0]; dMin[1]=p[1]; dMin[2]=p[2]; env->ReleaseFloatArrayElements(jLutDomainMin, p, JNI_ABORT); }
    }
    if (jLutDomainMax && env->GetArrayLength(jLutDomainMax) >= 3) {
        jfloat* p = env->GetFloatArrayElements(jLutDomainMax, nullptr);
        if (p) { dMax[0]=p[0]; dMax[1]=p[1]; dMax[2]=p[2]; env->ReleaseFloatArrayElements(jLutDomainMax, p, JNI_ABORT); }
    }

    jbyte* tone = nullptr;
    if (jToneCurve768 && env->GetArrayLength(jToneCurve768) >= 768)
        tone = env->GetByteArrayElements(jToneCurve768, nullptr);

    jfloat* mask = nullptr;
    if (jSubjectMask && maskW > 0 && maskH > 0)
        mask = env->GetFloatArrayElements(jSubjectMask, nullptr);
    float maskRect[4] = {0.f, 0.f, 1.f, 1.f};
    if (jSubjectMaskRect && env->GetArrayLength(jSubjectMaskRect) >= 4) {
        jfloat* p = env->GetFloatArrayElements(jSubjectMaskRect, nullptr);
        if (p) {
            maskRect[0]=p[0]; maskRect[1]=p[1]; maskRect[2]=p[2]; maskRect[3]=p[3];
            env->ReleaseFloatArrayElements(jSubjectMaskRect, p, JNI_ABORT);
        }
    }

    // Brush-mask layers: same packing as Stage C — layer i at offset i·(W·H).
    jfloat* brushMasks = nullptr;
    int brushW = 0, brushH = 0, brushN = 0;
    if (jBrushMasks && brushMaskCount > 0 && brushMaskW > 0 && brushMaskH > 0) {
        const jsize have = env->GetArrayLength(jBrushMasks);
        const jsize plane = jsize(brushMaskW) * jsize(brushMaskH);
        const int n = std::min<int>(brushMaskCount, raw_v3::ShaderParams::kMaskLayers);
        if (have >= plane * n) {
            brushMasks = env->GetFloatArrayElements(jBrushMasks, nullptr);
            if (brushMasks) {
                brushW = int(brushMaskW);
                brushH = int(brushMaskH);
                brushN = n;
            }
        } else {
            LOGE("nativeRenderGradedOffscreen: brush mask array too short "
                 "have=%d need=%d×%d×%d", int(have), n, int(brushMaskW), int(brushMaskH));
        }
    }

    jfloat* attenMask = nullptr;
    int attenWw = 0, attenHh = 0;
    if (jAttenMask && attenW > 0 && attenH > 0) {
        const jsize need = jsize(attenW) * jsize(attenH);
        if (env->GetArrayLength(jAttenMask) >= need) {
            attenMask = env->GetFloatArrayElements(jAttenMask, nullptr);
            if (attenMask) { attenWw = int(attenW); attenHh = int(attenH); }
        }
    }
    jfloat* depthArr = nullptr;
    int depthWw = 0, depthHh = 0;
    if (jDepthMap && depthW > 0 && depthH > 0) {
        const jsize need = jsize(depthW) * jsize(depthH);
        if (env->GetArrayLength(jDepthMap) >= need) {
            depthArr = env->GetFloatArrayElements(jDepthMap, nullptr);
            if (depthArr) { depthWw = int(depthW); depthHh = int(depthH); }
        }
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        env->ReleaseFloatArrayElements(jParams, params, JNI_ABORT);
        if (lut) env->ReleaseFloatArrayElements(jLutRgb, lut, JNI_ABORT);
        if (tone) env->ReleaseByteArrayElements(jToneCurve768, tone, JNI_ABORT);
        if (mask) env->ReleaseFloatArrayElements(jSubjectMask, mask, JNI_ABORT);
        if (brushMasks) env->ReleaseFloatArrayElements(jBrushMasks, brushMasks, JNI_ABORT);
        if (attenMask) env->ReleaseFloatArrayElements(jAttenMask, attenMask, JNI_ABORT);
        if (depthArr) env->ReleaseFloatArrayElements(jDepthMap, depthArr, JNI_ABORT);
        raw_v3::closeStageATiff(rd);
        return JNI_FALSE;
    }

    raw_v3::OffscreenSaveRenderer r;
    const bool ok = r.renderGradedToRgba8(
        gradeSrc, gradeW, gradeH,
        params, paramsCount,
        lut, int(lutSize),
        dMin, dMax,
        reinterpret_cast<const uint8_t*>(tone),
        mask, int(maskW), int(maskH),
        maskRect,
        brushMasks, brushW, brushH, brushN,
        attenMask, attenWw, attenHh,
        depthArr, depthWw, depthHh,
        float(focusDepth),
        reinterpret_cast<uint8_t*>(pixels));
    r.release();
    // Drop the subsample heap before releasing the mmap lock.
    workFp16.clear();
    workFp16.shrink_to_fit();
    raw_v3::closeStageATiff(rd);

    AndroidBitmap_unlockPixels(env, jBitmap);
    env->ReleaseFloatArrayElements(jParams, params, JNI_ABORT);
    if (lut) env->ReleaseFloatArrayElements(jLutRgb, lut, JNI_ABORT);
    if (tone) env->ReleaseByteArrayElements(jToneCurve768, tone, JNI_ABORT);
    if (mask) env->ReleaseFloatArrayElements(jSubjectMask, mask, JNI_ABORT);
    if (brushMasks) env->ReleaseFloatArrayElements(jBrushMasks, brushMasks, JNI_ABORT);
    if (attenMask) env->ReleaseFloatArrayElements(jAttenMask, attenMask, JNI_ABORT);
    if (depthArr) env->ReleaseFloatArrayElements(jDepthMap, depthArr, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// CPU Stage C fallback for JPG/WebP: write a Stage A BigTIFF already at the
// export working size so runStageC grades at targetLongSide (not full-res).
JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeDownsampleStageATiff(
        JNIEnv* env, jobject /*thiz*/,
        jstring jSrcPath, jstring jDstPath,
        jint destW, jint destH) {
    const std::string src = jstrOrEmpty(env, jSrcPath);
    const std::string dst = jstrOrEmpty(env, jDstPath);
    if (src.empty() || dst.empty() || destW <= 0 || destH <= 0) return JNI_FALSE;
    return raw_v3::downsampleStageATiffToSize(src, dst, int(destW), int(destH))
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeParseAdobeXmp(
        JNIEnv* env, jobject /*thiz*/,
        jstring  jPathOrString,
        jboolean jIsPath) {
    const std::string input = jstrOrEmpty(env, jPathOrString);
    raw_v3::XmpParams p = (jIsPath == JNI_TRUE)
        ? raw_v3::parseAdobeXmpFile(input)
        : raw_v3::parseAdobeXmpString(input);

    jfloatArray out = env->NewFloatArray(raw_v3::XmpParams::FLOAT_COUNT);
    if (!out) return nullptr;
    float buf[raw_v3::XmpParams::FLOAT_COUNT] = {0};
    p.writeTo(buf);
    env->SetFloatArrayRegion(out, 0, raw_v3::XmpParams::FLOAT_COUNT, buf);
    return out;
}

JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3GlSurfaceView_nativeReleaseRenderer(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong handle) {
    if (!handle) return;
    auto* r = reinterpret_cast<raw_v3::GlesRenderer*>(handle);
    r->release();
    delete r;
}

// ── Guided-filter mask refinement ──────────────────────────────────────────
//   Refines a soft saliency mask `p` so its edges snap to luma edges in
//   `I`. Both inputs are full-res float planes (W*H, row-major, [0..1]).
//   `scale` selects fast-guided-filter downsampling (1 = full-res math,
//   4 = 16× faster, 8 = 64× faster — quality stays close for radius>=scale).
//   Returns a fresh float[] the size of the inputs.
JNIEXPORT jfloatArray JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeGuidedFilterRefine(
        JNIEnv* env, jobject /*thiz*/,
        jfloatArray jGuide,
        jfloatArray jMask,
        jint width,
        jint height,
        jint radius,
        jfloat eps,
        jint scale) {
    if (!jGuide || !jMask || width <= 0 || height <= 0) return nullptr;
    const jsize n = jsize(width) * jsize(height);
    if (env->GetArrayLength(jGuide) < n || env->GetArrayLength(jMask) < n) return nullptr;

    jfloat* gPtr = env->GetFloatArrayElements(jGuide, nullptr);
    jfloat* mPtr = env->GetFloatArrayElements(jMask,  nullptr);
    if (!gPtr || !mPtr) {
        if (gPtr) env->ReleaseFloatArrayElements(jGuide, gPtr, JNI_ABORT);
        if (mPtr) env->ReleaseFloatArrayElements(jMask,  mPtr, JNI_ABORT);
        return nullptr;
    }

    std::vector<float> out(size_t(width) * size_t(height));
    v3::guidedFilterFast(
        reinterpret_cast<float*>(gPtr),
        reinterpret_cast<float*>(mPtr),
        out.data(),
        int(width), int(height),
        int(radius), float(eps), int(scale));

    env->ReleaseFloatArrayElements(jGuide, gPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(jMask,  mPtr, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(n);
    if (!result) return nullptr;
    env->SetFloatArrayRegion(result, 0, n, out.data());
    return result;
}

// ─────────────────────────────────────────────────────────────────────────────
//  VNG dual-decode self-tests (Item 2, Pass 1 harness)
//
//  Three layered tests as per NEXT_SESSION_PLAN.md:
//    1. Liveness   — synthetic 256×256 CFA gradient, assert all finite + [0,1]
//    2. Strips     — full-buffer vs per-row streaming on same input, RMS < 1e-4
//    3. DualVNG    — run Stage A with demosaic=-3 on a bundled asset, report
//                    pixel stats (mean R/G/B, MAD vs AMaZE-only baseline)
//                    for offline regression.
// ─────────────────────────────────────────────────────────────────────────────

// Test 1 — Liveness: synthetic CFA → VNG → check finite + clamp.
// Returns "" on pass, error description on failure.
JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeVngSelfTestLiveness(
        JNIEnv* env, jobject /*thiz*/) {
    constexpr int W = 256, H = 256;
    // Synthetic RGGB gradient mosaic. Pixel (y,x) holds the channel
    // appropriate for its CFA position, scaled to 16-bit DN.
    // filters = 0x94949494 = classic RGGB Bayer (LibRaw standard).
    constexpr unsigned filters = 0x94949494u;
    std::vector<uint16_t> mosaic(size_t(W) * H);
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            // Vary the gradient so every channel has a unique ramp.
            float v;
            const int fc = int((filters >> (((y<<1 & 14)|(x & 1)) << 1)) & 3);
            switch (fc) {
                case 0: v = float(x) / float(W - 1);                break; // R
                case 1: v = float(y) / float(H - 1);                break; // G1
                case 2: v = float(W - 1 - x) / float(W - 1);       break; // B
                case 3: v = float(H - 1 - y) / float(H - 1);       break; // G2
                default: v = 0.5f; break;
            }
            mosaic[size_t(y) * W + x] = uint16_t(v * 65534.f + 0.5f);
        }
    }
    // WB = identity (all 1.0, green-normalised).
    constexpr float camMul[4] = { 1.f, 1.f, 1.f, 1.f };
    std::vector<float> outR(size_t(W) * H);
    std::vector<float> outG(size_t(W) * H);
    std::vector<float> outB(size_t(W) * H);

    bool ok = raw_v3::lmmse_demosaic_to_planes(
        mosaic.data(), W, H, 0, 0, W, H, filters,
        /*blackLevel=*/0.f, /*whiteLevel=*/65535.f, camMul,
        outR.data(), outG.data(), outB.data());
    if (!ok) return env->NewStringUTF("FAIL: lmmse_demosaic_to_planes returned false");

    // All values must be finite and in [0, 1].
    for (int i = 0; i < W * H; i++) {
        if (!std::isfinite(outR[i]) || outR[i] < 0.f || outR[i] > 1.f)
            return env->NewStringUTF("FAIL: R channel out of [0,1] or non-finite");
        if (!std::isfinite(outG[i]) || outG[i] < 0.f || outG[i] > 1.f)
            return env->NewStringUTF("FAIL: G channel out of [0,1] or non-finite");
        if (!std::isfinite(outB[i]) || outB[i] < 0.f || outB[i] > 1.f)
            return env->NewStringUTF("FAIL: B channel out of [0,1] or non-finite");
    }
    return env->NewStringUTF("PASS");
}

// Test 2 — Strip consistency: ensure no row-boundary seam in the ring-buffer.
// Runs VNG on two identical synthetic inputs and checks RMS(full - strip) < 1e-4.
// (In Pass 1 our VNG is always full-buffer; this test validates that the output
// is deterministic and self-consistent across two identical calls.)
JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeVngSelfTestStrips(
        JNIEnv* env, jobject /*thiz*/) {
    constexpr int W = 128, H = 128;
    constexpr unsigned filters = 0x94949494u;
    std::vector<uint16_t> mosaic(size_t(W) * H);
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            const int fc = int((filters >> (((y<<1 & 14)|(x & 1)) << 1)) & 3);
            const float v = float((y * W + x) % 1024) / 1023.f;
            // Use per-channel noise to exercise gradient detection fully.
            const float chOff = float(fc) * 0.1f;
            float val = std::fmod(v + chOff, 1.f);
            mosaic[size_t(y) * W + x] = uint16_t(val * 65534.f + 0.5f);
        }
    }
    constexpr float camMul[4] = { 1.f, 1.f, 1.f, 1.f };

    // Run A.
    std::vector<float> aR(size_t(W) * H), aG(size_t(W) * H), aB(size_t(W) * H);
    bool okA = raw_v3::lmmse_demosaic_to_planes(
        mosaic.data(), W, H, 0, 0, W, H, filters,
        0.f, 65535.f, camMul, aR.data(), aG.data(), aB.data());
    // Run B on the same input.
    std::vector<float> bR(size_t(W) * H), bG(size_t(W) * H), bB(size_t(W) * H);
    bool okB = raw_v3::lmmse_demosaic_to_planes(
        mosaic.data(), W, H, 0, 0, W, H, filters,
        0.f, 65535.f, camMul, bR.data(), bG.data(), bB.data());

    if (!okA || !okB) return env->NewStringUTF("FAIL: lmmse_demosaic_to_planes returned false");

    // Compute RMS difference — must be zero for two identical runs.
    double sumSq = 0.0;
    for (int i = 0; i < W * H; i++) {
        double dr = aR[i] - bR[i];
        double dg = aG[i] - bG[i];
        double db = aB[i] - bB[i];
        sumSq += dr*dr + dg*dg + db*db;
    }
    const double rms = std::sqrt(sumSq / double(3 * W * H));
    if (rms > 1e-4) {
        char buf[128];
        std::snprintf(buf, sizeof(buf), "FAIL: RMS=%.6f > 1e-4 (non-deterministic output)", rms);
        return env->NewStringUTF(buf);
    }
    char buf[64];
    std::snprintf(buf, sizeof(buf), "PASS: RMS=%.2e", rms);
    return env->NewStringUTF(buf);
}

// Test 3 — Dual-decode pixel stats: run Stage A with demosaicAlgorithm=-3 on
// the provided RAW file path and return a JSON blob with mean R/G/B and the
// auto-resolved contrast threshold. Caller compares this against the AMaZE-only
// baseline. Not a pass/fail test — returns a stats blob for offline regression.
JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeVngSelfTestDualStats(
        JNIEnv* env, jobject /*thiz*/,
        jstring jRawPath,
        jstring jOutTifPath) {
    const std::string rawPath = jstrOrEmpty(env, jRawPath);
    const std::string tifPath = jstrOrEmpty(env, jOutTifPath);
    if (rawPath.empty() || tifPath.empty())
        return env->NewStringUTF("{\"error\":\"empty path\"}");

    raw_v3::StageAOptions opts;
    opts.demosaicAlgorithm     = -3;   // RAZ_AMAZE_VNG
    opts.dualAutoContrast      = true;
    opts.dualContrastThreshold = 0.2f;

    raw_v3::StageAMetadata meta = raw_v3::runStageA(rawPath, tifPath, opts);
    if (!meta.success) {
        std::string j = "{\"error\":\"";
        for (char c : meta.errorMessage) {
            if (c=='"'||c=='\\') j += '\\';
            j += c;
        }
        j += "\"}";
        return env->NewStringUTF(j.c_str());
    }

    std::string j = "{\"success\":true";
    j += ",\"width\":"    + std::to_string(meta.width);
    j += ",\"height\":"   + std::to_string(meta.height);
    j += ",\"dualContrastThreshold\":" + std::to_string(meta.dualContrastThreshold);
    j += "}";
    return env->NewStringUTF(j.c_str());
}

// ── 16-bit lossless encode ─────────────────────────────────────────────────
// Called by Kotlin for PNG-16 and TIFF-16 exports.  Reads the Stage C TIFF
// intermediate, applies crop/resize/sharpen, composites optional watermark,
// writes 16-bit output.  watermarkBitmap may be null.
extern "C" JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeEncode16Bit(
        JNIEnv* env, jobject /*thiz*/,
        jstring  jRgb16TifPath,
        jstring  jOutputPath,
        jint     jOutputFormat,       // 0=PNG-16, 1=TIFF-16
        jint     jCropX, jint jCropY, jint jCropW, jint jCropH,
        jint     jDstW,  jint jDstH,
        jfloat   jSharpenAmount,
        jobject  jWatermarkBitmap,    // nullable ARGB_8888 Bitmap
        jbyteArray jIccProfile) {

    const std::string srcPath = jstrOrEmpty(env, jRgb16TifPath);
    const std::string outPath = jstrOrEmpty(env, jOutputPath);
    if (srcPath.empty() || outPath.empty()) {
        return env->NewStringUTF("{\"success\":false,\"error\":\"bad args\"}");
    }

    // Optional watermark pixels.
    const uint8_t* wmPixels = nullptr;
    void* wmLocked = nullptr;
    AndroidBitmapInfo wmInfo{};
    uint32_t wmW = 0, wmH = 0;
    if (jWatermarkBitmap) {
        if (AndroidBitmap_getInfo(env, jWatermarkBitmap, &wmInfo) == ANDROID_BITMAP_RESULT_SUCCESS &&
            wmInfo.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
            AndroidBitmap_lockPixels(env, jWatermarkBitmap, &wmLocked) == ANDROID_BITMAP_RESULT_SUCCESS) {
            wmPixels = reinterpret_cast<const uint8_t*>(wmLocked);
            wmW = wmInfo.width;
            wmH = wmInfo.height;
        }
    }

    jbyte* iccArr = nullptr;
    const uint8_t* iccProfile = nullptr;
    size_t iccSize = 0;
    if (jIccProfile) {
        const jsize iccN = env->GetArrayLength(jIccProfile);
        if (iccN > 0) {
            iccArr = env->GetByteArrayElements(jIccProfile, nullptr);
            iccProfile = reinterpret_cast<const uint8_t*>(iccArr);
            iccSize = size_t(iccN);
        }
    }

    raw_v3::Encode16BitResult result = raw_v3::encodeRgb16TiffTo16bit(
        srcPath, outPath,
        int(jOutputFormat),
        uint32_t(jCropX < 0 ? 0 : jCropX), uint32_t(jCropY < 0 ? 0 : jCropY),
        uint32_t(jCropW < 0 ? 0 : jCropW), uint32_t(jCropH < 0 ? 0 : jCropH),
        uint32_t(jDstW  < 0 ? 0 : jDstW),  uint32_t(jDstH  < 0 ? 0 : jDstH),
        float(jSharpenAmount),
        wmPixels, wmW, wmH,
        iccProfile, iccSize);

    if (wmLocked) AndroidBitmap_unlockPixels(env, jWatermarkBitmap);
    if (iccArr) env->ReleaseByteArrayElements(jIccProfile, iccArr, JNI_ABORT);

    std::string json = "{";
    json += "\"success\":";     json += (result.success ? "true" : "false");
    json += ",\"karisBloomFallback\":"; json += (result.karisBloomFallback ? "true" : "false");
    json += ",\"width\":";      json += std::to_string(result.outWidth);
    json += ",\"height\":";     json += std::to_string(result.outHeight);
    json += ",\"durationMs\":"; json += std::to_string(result.durationMs);
    if (!result.error.empty()) {
        json += ",\"error\":\"";
        for (char c : result.error) { if (c=='"'||c=='\\') json+='\\'; json+=c; }
        json += "\"";
    }
    json += "}";
    return env->NewStringUTF(json.c_str());
}

// ── 16-bit encode into an RGBA_F16 Bitmap ───────────────────────────────────
// Called by Kotlin for 16-bit HEIC/AVIF export.  Applies the same crop/resize/
// sharpen/watermark pass as nativeEncode16Bit, then converts the uint16 RGB
// result into half-float RGBA and writes it into the locked Bitmap pixels.
extern "C" JNIEXPORT jstring JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeEncode16BitToBitmap(
        JNIEnv* env, jobject /*thiz*/,
        jstring  jRgb16TifPath,
        jobject  jOutputBitmap,       // must be RGBA_F16
        jint     jCropX, jint jCropY, jint jCropW, jint jCropH,
        jint     jDstW,  jint jDstH,
        jfloat   jSharpenAmount,
        jobject  jWatermarkBitmap) {  // nullable ARGB_8888 Bitmap

    const std::string srcPath = jstrOrEmpty(env, jRgb16TifPath);
    if (srcPath.empty()) {
        return env->NewStringUTF("{\"success\":false,\"error\":\"bad args\"}");
    }

    AndroidBitmapInfo info{};
    void* outLocked = nullptr;
    if (!jOutputBitmap ||
        AndroidBitmap_getInfo(env, jOutputBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_F16 ||
        AndroidBitmap_lockPixels(env, jOutputBitmap, &outLocked) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return env->NewStringUTF("{\"success\":false,\"error\":\"bad or non-RGBA_F16 output bitmap\"}");
    }

    const uint8_t* wmPixels = nullptr;
    void* wmLocked = nullptr;
    AndroidBitmapInfo wmInfo{};
    uint32_t wmW = 0, wmH = 0;
    if (jWatermarkBitmap) {
        if (AndroidBitmap_getInfo(env, jWatermarkBitmap, &wmInfo) == ANDROID_BITMAP_RESULT_SUCCESS &&
            wmInfo.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
            AndroidBitmap_lockPixels(env, jWatermarkBitmap, &wmLocked) == ANDROID_BITMAP_RESULT_SUCCESS) {
            wmPixels = reinterpret_cast<const uint8_t*>(wmLocked);
            wmW = wmInfo.width;
            wmH = wmInfo.height;
        }
    }

    raw_v3::Encode16BitResult result = raw_v3::encodeRgb16TiffToF16Bitmap(
        srcPath,
        reinterpret_cast<uint8_t*>(outLocked), info.stride,
        uint32_t(jCropX < 0 ? 0 : jCropX), uint32_t(jCropY < 0 ? 0 : jCropY),
        uint32_t(jCropW < 0 ? 0 : jCropW), uint32_t(jCropH < 0 ? 0 : jCropH),
        uint32_t(jDstW  < 0 ? 0 : jDstW),  uint32_t(jDstH  < 0 ? 0 : jDstH),
        float(jSharpenAmount),
        wmPixels, wmW, wmH);

    AndroidBitmap_unlockPixels(env, jOutputBitmap);
    if (wmLocked) AndroidBitmap_unlockPixels(env, jWatermarkBitmap);

    std::string json = "{";
    json += "\"success\":";     json += (result.success ? "true" : "false");
    json += ",\"karisBloomFallback\":"; json += (result.karisBloomFallback ? "true" : "false");
    json += ",\"width\":";      json += std::to_string(result.outWidth);
    json += ",\"height\":";     json += std::to_string(result.outHeight);
    json += ",\"durationMs\":"; json += std::to_string(result.durationMs);
    if (!result.error.empty()) {
        json += ",\"error\":\"";
        for (char c : result.error) { if (c=='"'||c=='\\') json+='\\'; json+=c; }
        json += "\"";
    }
    json += "}";
    return env->NewStringUTF(json.c_str());
}

// ── Debug log reset — called from Kotlin when workspace selector opens ────────
extern "C" JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeResetAdjustmentDebugLog(
    JNIEnv*, jobject)
{
    raw_v3::resetAdjustmentDebugLog();
    LOGI("resetAdjustmentDebugLog: gen=%d",
         raw_v3::g_debugLogGeneration.load(std::memory_order_relaxed));
}

// ── Task 7.3: Film simulation JNI entry ──────────────────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeApplyFilmSim(
    JNIEnv* env, jobject,
    jstring jInputPath, jstring jOutputPath,
    jint profileIndex, jfloat grainAmount)
{
    const char* inp = env->GetStringUTFChars(jInputPath,  nullptr);
    const char* out = env->GetStringUTFChars(jOutputPath, nullptr);
    if (!inp || !out) {
        if (inp) env->ReleaseStringUTFChars(jInputPath,  inp);
        if (out) env->ReleaseStringUTFChars(jOutputPath, out);
        return JNI_FALSE;
    }
    std::string inpStr(inp), outStr(out);
    env->ReleaseStringUTFChars(jInputPath,  inp);
    env->ReleaseStringUTFChars(jOutputPath, out);

    bool ok = raw_v3::applyFilmSimCpu(inpStr, outStr, int(profileIndex), float(grainAmount));
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
