#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstring>
#include <cstdio>
#include "libraw/libraw.h"
#include "lensfun_android.h"

// Persistent database instance — loaded once, reused for every correction call.
static LfDatabase* g_lfDb = nullptr;

#define LOG_TAG "LensCorrector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Escape a C string for embedding in a JSON value (handles quotes and backslashes).
static std::string jsonEscape(const char* s) {
    if (!s) return "";
    std::string out;
    out.reserve(64);
    for (; *s; ++s) {
        if      (*s == '"')  { out += "\\\""; }
        else if (*s == '\\') { out += "\\\\"; }
        else                 { out += *s; }
    }
    return out;
}

extern "C" {

/**
 * Reads camera/lens EXIF from a RAW file without demosaicing.
 * Uses LibRaw open_buffer() only — no unpack()/dcraw_process() needed.
 *
 * Fields used:
 *   imgdata.idata.make / model     → camMake / camModel
 *   imgdata.lens.LensMake / Lens   → lensMake / lensModel
 *   imgdata.other.focal_len        → focalLength
 *   imgdata.other.aperture         → aperture
 *   imgdata.other.iso_speed        → isoSpeed  (N3)
 *   imgdata.other.shutter          → shutter   (N3)
 *
 * Returns JSON on success, null if the file cannot be opened.
 */
JNIEXPORT jstring JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_readLensMetadata(
    JNIEnv* env, jobject /*obj*/, jbyteArray rawData) {

    jsize  len  = env->GetArrayLength(rawData);
    jbyte* data = env->GetByteArrayElements(rawData, nullptr);

    LibRaw raw;
    int ret = raw.open_buffer(data, (size_t)len);
    env->ReleaseByteArrayElements(rawData, data, JNI_ABORT);

    if (ret != LIBRAW_SUCCESS) {
        LOGE("readLensMetadata: open_buffer failed: %s", libraw_strerror(ret));
        return nullptr;
    }

    const char* make      = raw.imgdata.idata.make;
    const char* model     = raw.imgdata.idata.model;
    const char* cdesc      = raw.imgdata.idata.cdesc;
    const char* lensMake  = raw.imgdata.lens.LensMake;
    const char* lensModel = raw.imgdata.lens.Lens;
    // LibRaw resolves rebrands (Leica-on-Panasonic, Samsung-on-Pentax,
    // Sigma-on-Sony etc.) into normalized_make / normalized_model. These
    // are the authoritative key for lens-DB lookups — `make/model` will
    // say "Panasonic" / "DC-S5" while normalized says "Leica" / "S5" for
    // a Leica DG lens on a Panasonic body. lensfun lookups against
    // `make/model` would miss these rebranded combinations.
    const char* normMake  = raw.imgdata.idata.normalized_make;
    const char* normModel = raw.imgdata.idata.normalized_model;

    // focal_len, aperture, iso_speed and shutter live in imgdata.other
    float focal    = raw.imgdata.other.focal_len;
    float aperture = raw.imgdata.other.aperture;
    int   isoSpeed = (int)raw.imgdata.other.iso_speed;
    float shutter  = raw.imgdata.other.shutter;
    // Capture timestamp (time_t = seconds since Unix epoch). Surfaced as
    // long for JSON round-trip; the Kotlin side converts to ISO 8601 for
    // sidecar XMP serialisation. Falls back to file mtime when 0.
    long long timestamp = (long long)raw.imgdata.other.timestamp;

    // For first-party lenses (Canon EF, Nikon F, Sony FE, …) lensMake is often
    // empty; fall back to the normalized camera maker so Lensfun can still
    // find the profile even on rebranded bodies.
    const char* lm = (lensMake && lensMake[0] != '\0') ? lensMake :
                     (normMake && normMake[0] != '\0') ? normMake : make;

    LOGI("readLensMetadata: make='%s' model='%s' normMake='%s' normModel='%s' "
         "lensMake='%s' lens='%s' focal=%.1f aperture=%.1f iso=%d shutter=%.6f ts=%lld",
         make ? make : "", model ? model : "",
         normMake ? normMake : "", normModel ? normModel : "",
         lm   ? lm   : "", lensModel ? lensModel : "",
         (double)focal, (double)aperture, isoSpeed, (double)shutter, timestamp);

    char buf[1024];
    std::snprintf(buf, sizeof(buf),
        "{\"camMake\":\"%s\",\"camModel\":\"%s\",\"cdesc\":\"%s\","
        "\"normalizedMake\":\"%s\",\"normalizedModel\":\"%s\","
        "\"lensMake\":\"%s\",\"lensModel\":\"%s\","
        "\"focalLength\":%.1f,\"aperture\":%.2f,"
        "\"isoSpeed\":%d,\"shutter\":%.6f,\"timestamp\":%lld}",
        jsonEscape(make      ? make      : "").c_str(),
        jsonEscape(model     ? model     : "").c_str(),
        jsonEscape(cdesc     ? cdesc     : "").c_str(),
        jsonEscape(normMake  ? normMake  : "").c_str(),
        jsonEscape(normModel ? normModel : "").c_str(),
        jsonEscape(lm        ? lm        : "").c_str(),
        jsonEscape(lensModel ? lensModel : "").c_str(),
        (double)focal,
        (double)aperture,
        isoSpeed,
        (double)shutter,
        timestamp);

    return env->NewStringUTF(buf);
}

/**
 * Initialise the Lensfun lens database from a directory of .xml files.
 * Idempotent: re-loading replaces the existing database.
 */
JNIEXPORT jboolean JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_initLensfunDb(
    JNIEnv* env, jobject /*obj*/, jstring dbPath) {
    const char* path = env->GetStringUTFChars(dbPath, nullptr);

    delete g_lfDb;
    g_lfDb = new LfDatabase();
    int n = lfa_load_database(*g_lfDb, path);
    env->ReleaseStringUTFChars(dbPath, path);

    if (n == 0) {
        LOGE("initLensfunDb: no XML files loaded from %s", path);
        delete g_lfDb;
        g_lfDb = nullptr;
        return JNI_FALSE;
    }
    LOGI("initLensfunDb: loaded %d files, %zu cameras, %zu lenses",
         n, g_lfDb->cameras.size(), g_lfDb->lenses.size());
    return JNI_TRUE;
}

/**
 * Apply lens corrections (distortion, vignetting, CA) in linear float32 space.
 * strength 0 = no correction, 1 = full Lensfun profile.
 * Returns corrected FloatArray, or original array if no profile found.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_raz_razstudio_lib_raw_NativeRawDecoder_applyLensCorrection(
    JNIEnv* env, jobject /*obj*/,
    jfloatArray pixels, jint width, jint height,
    jstring jCamMake, jstring jCamModel,
    jstring jLensMake, jstring jLensModel,
    jfloat focalLength, jfloat aperture,
    jfloat distStrength, jfloat vigStrength, jfloat caStrength) {

    if (!g_lfDb) {
        LOGE("applyLensCorrection: database not initialised");
        return pixels;
    }

    const char* camMake  = env->GetStringUTFChars(jCamMake,  nullptr);
    const char* camModel = env->GetStringUTFChars(jCamModel, nullptr);
    const char* lensMake = env->GetStringUTFChars(jLensMake, nullptr);
    const char* lensModel= env->GetStringUTFChars(jLensModel,nullptr);

    const LfCameraProfile* cam  = lfa_find_camera(*g_lfDb, camMake, camModel);
    const LfLensProfile*   lens = lfa_find_lens(*g_lfDb, cam, lensMake, lensModel, focalLength);

    env->ReleaseStringUTFChars(jCamMake,   camMake);
    env->ReleaseStringUTFChars(jCamModel,  camModel);
    env->ReleaseStringUTFChars(jLensMake,  lensMake);
    env->ReleaseStringUTFChars(jLensModel, lensModel);

    if (!lens) {
        LOGI("applyLensCorrection: no lens profile found");
        return pixels;
    }

    jsize count = env->GetArrayLength(pixels);
    jfloat* data = env->GetFloatArrayElements(pixels, nullptr);

    lfa_apply_corrections(data, width, height, lens, cam,
                          focalLength, aperture,
                          distStrength, vigStrength, caStrength);

    env->ReleaseFloatArrayElements(pixels, data, 0); // 0 = copy back
    return pixels;
}

}  // extern "C"
