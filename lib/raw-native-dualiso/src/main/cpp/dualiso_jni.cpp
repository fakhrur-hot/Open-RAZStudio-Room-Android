/*
 * JNI surface for the Magic-Lantern dual-ISO module.
 * Licensed under the GNU General Public License v2 or later — see
 * LICENSE-GPL2 in this module's root.
 */

#include <jni.h>
#include <android/log.h>
#include <vector>

#include "dualiso_blend.h"

#define LOG_TAG "DualIso"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_raz_razstudio_lib_dualiso_DualIsoNative_nativeHdrCheck(
    JNIEnv* env, jclass /*clazz*/,
    jshortArray bayer, jint width, jint height, jint black, jint white) {

    if (bayer == nullptr) return JNI_FALSE;
    const jsize len = env->GetArrayLength(bayer);
    if (len < static_cast<jsize>(width) * height) {
        LOGW("hdrCheck: buffer too small (%d < %d)", (int)len, width * height);
        return JNI_FALSE;
    }

    jshort* raw = env->GetShortArrayElements(bayer, nullptr);
    if (raw == nullptr) return JNI_FALSE;

    // jshort is signed 16-bit; bayer data is unsigned. Reinterpret is
    // safe because the C++ kernel masks to 14 bits before use.
    const bool result = dualiso::hdr_check(
        reinterpret_cast<const uint16_t*>(raw), width, height, black, white);

    env->ReleaseShortArrayElements(bayer, raw, JNI_ABORT);   // read-only
    LOGI("hdrCheck %dx%d black=%d white=%d → %s",
         width, height, black, white, result ? "DUAL_ISO" : "single");
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_raz_razstudio_lib_dualiso_DualIsoNative_nativeDetectFull(
    JNIEnv* env, jclass /*clazz*/,
    jshortArray bayer, jint width, jint height, jint black, jint white) {

    if (bayer == nullptr) return -1;
    const jsize len = env->GetArrayLength(bayer);
    if (len < static_cast<jsize>(width) * height) {
        LOGW("detectFull: buffer too small");
        return -1;
    }
    jshort* raw = env->GetShortArrayElements(bayer, nullptr);
    if (raw == nullptr) return -1;
    const int status = dualiso::detect_full(
        reinterpret_cast<const uint16_t*>(raw), width, height, black, white);
    env->ReleaseShortArrayElements(bayer, raw, JNI_ABORT);
    LOGI("detectFull %dx%d → status=0x%02x", width, height, status);
    return status;
}

JNIEXPORT jboolean JNICALL
Java_com_raz_razstudio_lib_dualiso_DualIsoNative_nativeBlendMean23(
    JNIEnv* env, jclass /*clazz*/,
    jshortArray bayer, jint width, jint height, jint black, jint white) {

    if (bayer == nullptr) return JNI_FALSE;
    const jsize len = env->GetArrayLength(bayer);
    if (len < static_cast<jsize>(width) * height) {
        LOGW("blendMean23: buffer too small");
        return JNI_FALSE;
    }

    jshort* raw = env->GetShortArrayElements(bayer, nullptr);
    if (raw == nullptr) return JNI_FALSE;

    const bool ok = dualiso::blend_mean23(
        reinterpret_cast<uint16_t*>(raw), width, height, black, white);

    // Commit the (possibly modified) buffer back to the Java array.
    env->ReleaseShortArrayElements(bayer, raw, ok ? 0 : JNI_ABORT);
    LOGI("blendMean23 %dx%d → %s", width, height, ok ? "ok" : "fail");
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
