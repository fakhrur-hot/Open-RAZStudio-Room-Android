/*
 * android/hardware_buffer.h stub — desktop parity harness ONLY.
 * gles_renderer.h only ever names AHardwareBuffer through pointers; the
 * functions below are declared so the header parses, but the parity target
 * never calls the AHB code paths (importAhbAsTexture etc. live in
 * gles_renderer.cpp, which this target does not compile).
 */
#pragma once

typedef struct AHardwareBuffer AHardwareBuffer;

#ifdef __cplusplus
extern "C" {
#endif
void AHardwareBuffer_acquire(AHardwareBuffer* buffer);
void AHardwareBuffer_release(AHardwareBuffer* buffer);
#ifdef __cplusplus
}
#endif
