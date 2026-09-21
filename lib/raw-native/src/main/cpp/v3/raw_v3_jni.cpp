/*
 * StudioRoom — RAW Pipeline v3
 * raw_v3_jni.cpp — JNI Bindings for Dual-Demosaic Debug Exports and AhbMaskLoader
 */

#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <string>
#include "dual_blend.h"
#include "ahb_mask_loader.h"

static std::string jstrOrEmpty(JNIEnv* env, jstring jStr) {
    if (!jStr) return "";
    const char* chars = env->GetStringUTFChars(jStr, nullptr);
    if (!chars) return "";
    std::string result(chars);
    env->ReleaseStringUTFChars(jStr, chars);
    return result;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeExportDebugMap(
        JNIEnv* env, jobject, jint type, jstring jPath) {
    std::string path = jstrOrEmpty(env, jPath);
    if (path.empty()) return JNI_FALSE;

    bool success = false;
    switch (type) {
        case 0: raw_v3::saveBlendMapPng(path.c_str(), nullptr, 0, 0); success = true; break; // Note: updated signature or call sites as needed
        case 1: raw_v3::saveEdgeConfidencePng(path.c_str(), 0, 0); success = true; break;
        case 2: raw_v3::saveTextureConfidencePng(path.c_str(), 0, 0); success = true; break;
        case 3: raw_v3::saveNoiseConfidencePng(path.c_str(), 0, 0); success = true; break;
        case 4: raw_v3::saveHighlightConfidencePng(path.c_str(), 0, 0); success = true; break;
        default: break;
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeCreateAhbMaskLoader(
        JNIEnv*, jobject) {
    auto* loader = new raw_v3::AhbMaskLoader();
    return reinterpret_cast<jlong>(loader);
}

JNIEXPORT void JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeDestroyAhbMaskLoader(
        JNIEnv*, jobject, jlong nativePtr) {
    auto* loader = reinterpret_cast<raw_v3::AhbMaskLoader*>(nativePtr);
    if (loader) {
        delete loader;
    }
}

JNIEXPORT jint JNICALL
Java_com_RAZStudio_StudioRoom_feature_photo_1editor_raw_1v3_RawV3Engine_nativeImportAhbMask(
        JNIEnv* env, jobject, jlong nativePtr, jobject jAhbBuffer, jlong displayPtr, jlong generation) {
    auto* loader = reinterpret_cast<raw_v3::AhbMaskLoader*>(nativePtr);
    if (!loader || !jAhbBuffer) return 0;

    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, jAhbBuffer);
    if (!buffer) return 0;

    EGLDisplay display = reinterpret_cast<EGLDisplay>(displayPtr);
    GLuint texId = loader->getOrImportTexture(display, buffer, static_cast<uint64_t>(generation));
    return static_cast<jint>(texId);
}

} // extern "C"
