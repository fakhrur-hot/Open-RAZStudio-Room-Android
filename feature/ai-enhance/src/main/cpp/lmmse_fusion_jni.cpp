/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * JNI bridge for the fusion tone-mapping path (enhanceFusion). Applies a
 * subject-masked shadow/highlight/saturation grade in-place on a mutable
 * ARGB_8888 Bitmap and returns the same Bitmap.
 *
 * Symbol matches LmmseFusionBridge in the photo-editor module's
 * raw_v3.segmentation package — the resulting symbol is compiled into the
 * shared liblmmse_enhance.so, which the merged APK exposes to both modules.
 */

#include "lmmse_enhance.h"

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#define TAG "LmmseFusion"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jobject JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_segmentation_LmmseFusionBridge_enhanceLMMSENative(
    JNIEnv* env,
    jobject /* this */,
    jobject bitmap,
    jfloatArray subjectMask,
    jint maskWidth,
    jint maskHeight,
    jfloat shadowBoost,
    jfloat highlightBoost,
    jfloat saturation
) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("getInfo failed");
        return bitmap; // hand the original back; Kotlin treats it as a no-op
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("unsupported format %d (need RGBA_8888)", info.format);
        return bitmap;
    }

    const int w = static_cast<int>(info.width);
    const int h = static_cast<int>(info.height);

    // Mask is optional and may be any resolution (it is bilinear-sampled).
    // Use it only when its length matches maskWidth*maskHeight; otherwise
    // grade uniformly rather than risk an out-of-bounds read.
    float* maskPtr = nullptr;
    jfloat* maskElems = nullptr;
    int maskW = 0, maskH = 0;
    if (subjectMask != nullptr && maskWidth > 0 && maskHeight > 0) {
        const jsize maskLen = env->GetArrayLength(subjectMask);
        if (maskLen == static_cast<jsize>(maskWidth) * maskHeight) {
            maskElems = env->GetFloatArrayElements(subjectMask, nullptr);
            maskPtr = maskElems;
            maskW = maskWidth;
            maskH = maskHeight;
        } else {
            LOGI("mask len %d != %dx%d — grading uniformly", maskLen, maskWidth, maskHeight);
        }
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("lockPixels failed");
        if (maskElems) env->ReleaseFloatArrayElements(subjectMask, maskElems, JNI_ABORT);
        return bitmap;
    }

    lmmse_enhance::enhanceFusion(
        static_cast<uint8_t*>(pixels), w, h, static_cast<int>(info.stride),
        maskPtr, maskW, maskH,
        static_cast<float>(shadowBoost),
        static_cast<float>(highlightBoost),
        static_cast<float>(saturation)
    );

    AndroidBitmap_unlockPixels(env, bitmap);
    // JNI_ABORT: we only read the mask, so skip the (expensive) copy-back.
    if (maskElems) env->ReleaseFloatArrayElements(subjectMask, maskElems, JNI_ABORT);

    return bitmap;
}

} // extern "C"
