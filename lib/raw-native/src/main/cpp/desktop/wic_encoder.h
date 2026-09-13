/*
 * wic_encoder — 8-bit JPEG / PNG output via Windows Imaging Component.
 *
 * WHY WIC INSTEAD OF libjpeg-turbo + libpng
 * -----------------------------------------
 * Android does NOT encode JPEG/PNG in this C++ core — it does it in Kotlin
 * (Bitmap.compress / ImageCompressor). So there is no native encoder to match
 * byte-for-byte; any desktop encoder is new code by definition, and the parity
 * that actually matters (pixel VALUES) is already guaranteed upstream by
 * runStageCToRGBA8.
 *
 * Given that, WIC wins on every axis that is left:
 *   - zero new third-party dependencies (ships with Windows; this project
 *     already vendors LibRaw + libxml2 and does not need two more)
 *   - native ICC embedding (IWICColorContext) and EXIF writing
 *     (IWICMetadataQueryWriter) — otherwise both would need reimplementing
 *     per container on top of the TIFF work already done
 *   - maintained by the OS vendor
 *
 * Trade-off, stated plainly: this file is Windows-only. That is already true
 * of the compat/ shims (dirent, sys/mman, unistd), so razbatch is a Windows
 * tool today regardless. A POSIX build would need a different encoder here.
 */

#ifndef RAZ_WIC_ENCODER_H
#define RAZ_WIC_ENCODER_H

#include <cstdint>
#include <string>

namespace raz {

/** Metadata to embed. Empty strings / non-positive numbers are skipped. */
struct EncodeMeta {
    const unsigned char* icc     = nullptr;
    size_t               iccSize = 0;
    std::string make, model, dateTime, dateTimeOriginal, lensModel;
    float  exposureTime = 0.f;   // seconds
    float  fNumber      = 0.f;
    float  focalLength  = 0.f;   // mm
    int    iso          = 0;
};

enum class WicFormat { Jpeg, Png };

/**
 * Encode [rgba] (8-bit RGBA, [stride] bytes per row) to [path].
 *
 * @param quality JPEG quality 1..100; ignored for PNG.
 * @param err     receives a human-readable reason on failure.
 * @return true on success.
 */
bool encodeWic(const std::string& path,
               const uint8_t* rgba, uint32_t width, uint32_t height, uint32_t stride,
               WicFormat format, int quality,
               const EncodeMeta& meta, std::string* err);

}  // namespace raz

#endif  // RAZ_WIC_ENCODER_H
