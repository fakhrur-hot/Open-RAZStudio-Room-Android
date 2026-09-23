#ifndef RAW_V3_JPEG_DUAL_RECON_H
#define RAW_V3_JPEG_DUAL_RECON_H

namespace raw_v3 {

/**
 * JPEG Dual Reconstruction Lite — spatial pre-pass (not demosaic).
 * Strength 0 is a no-op. Radii scale with longSide / 2560.
 */
template <typename T>
void applyJpegDualRecon(
    T* pixels,
    int w,
    int h,
    int strideInPixels,
    int channels,
    float strength,
    float cleanBias,
    float detailBias);

}  // namespace raw_v3

#endif
