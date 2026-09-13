/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * JNI bridge for the LMMSE + Thresholded USM enhancement pipeline.
 * Operates directly on an Android Bitmap via jnigraphics (lockPixels).
 */

#include "lmmse_enhance.h"

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#define TAG "LmmseEnhance"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_ai_1enhance_data_LmmseNative_nativeEnhance(
    JNIEnv* env,
    jclass /* clazz */,
    jobject bitmap,
    jint windowSize,
    jfloat noiseVariance,
    jfloat usmRadius,
    jfloat usmAmount,
    jfloat usmThreshold,
    jboolean skipDenoise,
    jboolean skipSharpen
) {
    // Validate bitmap
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to get bitmap info");
        return JNI_FALSE;
    }

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported bitmap format: %d (expected RGBA_8888)", info.format);
        return JNI_FALSE;
    }

    // Lock pixels
    void* pixelPtr = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixelPtr) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock bitmap pixels");
        return JNI_FALSE;
    }

    // Build params
    lmmse_enhance::Params params{};
    params.windowSize = static_cast<int>(windowSize);
    params.noiseVariance = static_cast<float>(noiseVariance);
    params.usmRadius = static_cast<float>(usmRadius);
    params.usmAmount = static_cast<float>(usmAmount);
    params.usmThreshold = static_cast<float>(usmThreshold);
    params.skipDenoise = skipDenoise == JNI_TRUE;
    params.skipSharpen = skipSharpen == JNI_TRUE;

    // Run pipeline in-place
    lmmse_enhance::enhance(
        static_cast<uint8_t*>(pixelPtr),
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        static_cast<int>(info.stride),
        params
    );

    // Unlock
    AndroidBitmap_unlockPixels(env, bitmap);

    return JNI_TRUE;
}

} // extern "C"
