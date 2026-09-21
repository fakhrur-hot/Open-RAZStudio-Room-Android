/*
 * StudioRoom — RAW Pipeline v3 — Stage C export (M8).
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Walks the Stage A TIFF at full resolution in 256-row bands, applies the
 * shared apply_macro kernel pixel-by-pixel, writes the result as
 * uncompressed 16-bit RGB TIFF (M8). M9 will add 8-bit codecs (JPG / WebP /
 * HEIC / AVIF) sharing this same per-pixel kernel.
 *
 * Memory budget at full-res (5500 × 3669):
 *   • Stage A mmap            ~150 MB (OS-paged, not heap)
 *   • Band float scratch      ~17 MB (256 × 5500 × 3 × 4)
 *   • TIFF output ramp via stdio fwrite — kernel-buffered
 *   Total native heap = ~17 MB transient. JVM heap untouched.
 */

#pragma once

#include <cstdint>
#include <string>

namespace raw_v3 {

enum class StageCFormat : int {
    Tiff16 = 0,
    // M9: 8-bit hybrid path. NDK fills an Android Bitmap (RGBA_8888);
    // Kotlin compresses via Bitmap.compress / HeifWriter / etc. The
    // enum is informational only here — the format-specific encoding
    // lives Kotlin-side. PNG-16 + AVIF are NDK follow-ups.
    Bitmap8888 = 1,
};

struct StageCResult {
    bool        success = false;
    bool        karisBloomFallback = false;
    uint32_t    outWidth = 0;
    uint32_t    outHeight = 0;
    std::string outputPath;
    std::string error;
    int64_t     durationMs = 0;
};

struct StageCOptions {
    StageCFormat format = StageCFormat::Tiff16;
    // Output dimensions. 0 = keep source dims.
    uint32_t     targetWidth  = 0;
    uint32_t     targetHeight = 0;
    // ShaderParams blob — float layout per ShaderParams.kt (57 floats).
    const float* params      = nullptr;
    int          paramsCount = 0;
    // Optional 3D LUT. data must be size³ × 3 floats, RGB.
    const float* lutData = nullptr;
    int          lutSize = 0;
    float        lutDomainMin[3] = {0.f, 0.f, 0.f};
    float        lutDomainMax[3] = {1.f, 1.f, 1.f};
    // Optional U2Net subject mask, [0,1], row-major [subjectMaskSize²].
    // Used to feather smart sharpness toward the subject. null = no feather.
    const float* subjectMask = nullptr;
    int          subjectMaskSize = 0;   // mask width
    int          subjectMaskH = 0;      // mask height; 0 = square (== width). The editor
                                        // passes the 1024px refined (image-aspect) mask.
    // Letterbox rect for the subject mask — same as GL uSubjectMaskRect.
    // Maps image [0,1] UV space into mask texture UV space. Default (0,0,1,1)
    // = identity (square source, or caller didn't set). Must match the value
    // passed to GlesRenderer::setSubjectMaskInnerRect() for preview↔export parity.
    float subjectMaskRectU0 = 0.f;
    float subjectMaskRectV0 = 0.f;
    float subjectMaskRectU1 = 1.f;
    float subjectMaskRectV1 = 1.f;
    // Orton/bokeh sky-attenuation — Cityscapes max(sky, terrain), same 320²
    // grid + letterbox rect as the subject mask (mirrors uBokehAttenuation).
    // null = gate off (matches GL when cityscapes not ready).
    const float* attenMask = nullptr;
    int          attenMaskSize = 0;   // width
    int          attenMaskH = 0;      // height; 0 = square
    // Depth	o CoC for Stage C selective bokeh (mirrors GL unit-10 .g + focus).
    // null / 0 size = depth-off path (subject+atten gate only, still disc-blur).
    const float* depthMap = nullptr; // [0,1] row-major
    int          depthMapW = 0;
    int          depthMapH = 0;
    float        focusDepth = 0.5f;  // subject-median depth plane
    // M12.2c.2b — Up to 4 brush-mask layers ([0,1] alpha, row-major). Each
    // layer carries its own adjustments via the ShaderParams blob ([134..140]
    // for layer 0, [157..177] for layers 1..3). Layers with data=nullptr are
    // skipped. Mirrors the GL preview's multi-layer Mask tab.
    static constexpr int kMaskLayers = 4;
    const float* maskLayerData[kMaskLayers] = {nullptr, nullptr, nullptr, nullptr};
    int          maskLayerW[kMaskLayers]    = {0, 0, 0, 0};
    int          maskLayerH[kMaskLayers]    = {0, 0, 0, 0};
    // Optional Tone Curve LUT: 256 RGB8 texels (768 bytes interleaved R,G,B),
    // index = input intensity. Applied per-pixel after grading + 3D LUT,
    // matching the GL uber-shader. null = identity (no curve).
    const uint8_t* toneCurveLut = nullptr;
    // Optional ICC profile bytes to embed as TIFF tag 34675. null = no ICC.
    const uint8_t* iccProfile = nullptr;
    size_t         iccProfileSize = 0;
    /*
     * Optional EXIF metadata for the TIFF-16 writer (StageCExif below).
     *
     * OPT-IN: null — which is what every Android caller passes — produces a
     * BYTE-IDENTICAL file to the pre-EXIF writer. Only the desktop razbatch
     * CLI sets it today. Empty strings / non-positive numbers inside the
     * struct are individually skipped, so partial metadata is safe.
     *
     * Typed as void* to keep this header free of <string>; stage_c_export.cpp
     * reinterprets it as its internal TiffExif, whose layout is StageCExif.
     */
    const void* exif = nullptr;
};

/*
 * Layout-compatible mirror of stage_c_export.cpp's internal TiffExif. Fill one
 * of these and assign &it to StageCOptions::exif.
 *
 * Values map to: baseline main-IFD tags Make(271) Model(272) DateTime(306)
 * Artist(315) Copyright(33432), plus an EXIF sub-IFD (0x8769) carrying
 * ExposureTime(0x829A) FNumber(0x829D) ISO(0x8827) DateTimeOriginal(0x9003)
 * FocalLength(0x920A) LensModel(0xA434).
 */
struct StageCExif {
    std::string make, model, dateTime, artist, copyright;
    std::string lensModel, dateTimeOriginal;
    float  exposureTime = 0.f;   // seconds (e.g. 1/30 -> 0.0333)
    float  fNumber      = 0.f;
    float  focalLength  = 0.f;   // mm
    int    iso          = 0;
};

StageCResult runStageC(const std::string& stageATifPath,
                       const std::string& outputPath,
                       const StageCOptions& options);

struct Encode16BitResult {
    bool        success  = false;
    bool        karisBloomFallback = false;
    uint32_t    outWidth = 0, outHeight = 0;
    std::string outputPath;
    std::string error;
    int64_t     durationMs = 0;
};

/**
 * Read the Stage C RGB16 TIFF intermediate, apply crop + area-filter resize +
 * Laplacian post-sharpen + optional watermark composite, then write a 16-bit
 * lossless output file.
 *
 * outputFormat: 0 = PNG-16, 1 = TIFF-16.
 * cropX/Y/W/H : crop rect in source pixels (0,0,0,0 = no crop).
 * dstW/dstH   : resize target (0,0 = keep post-crop dims).
 * sharpenAmount: Laplacian strength [0,1] (ShaderParams slot 379).
 * watermarkArgb: optional ARGB_8888 row-major pixel buffer (wmW×wmH == dstW×dstH).
 */
Encode16BitResult encodeRgb16TiffTo16bit(
        const std::string& rgb16TifPath,
        const std::string& outputPath,
        int outputFormat,
        uint32_t cropX, uint32_t cropY, uint32_t cropW, uint32_t cropH,
        uint32_t dstW, uint32_t dstH,
        float sharpenAmount,
        const uint8_t* watermarkArgb, uint32_t wmW, uint32_t wmH,
        const uint8_t* iccProfile = nullptr,
        size_t iccProfileSize = 0);

/**
 * Same 16-bit post-processing as [encodeRgb16TiffTo16bit], but renders the
 * result into a caller-locked Android Bitmap configured as RGBA_F16.
 * [outPixels] is the locked buffer, [outStride] is the byte stride per row.
 * Used for 16-bit HEIC/AVIF export where the downstream encoder accepts
 * high-bit-depth Bitmap input.
 */
Encode16BitResult encodeRgb16TiffToF16Bitmap(
        const std::string& rgb16TifPath,
        uint8_t* outPixels, uint32_t outStride,
        uint32_t cropX, uint32_t cropY, uint32_t cropW, uint32_t cropH,
        uint32_t dstW, uint32_t dstH,
        float sharpenAmount,
        const uint8_t* watermarkArgb, uint32_t wmW, uint32_t wmH);

/**
 * M9 — render Stage A full-res through the same apply_macro kernel and
 * write the result as RGBA_8888 into [outPixels] (caller-allocated,
 * row-major, [outStride] bytes per row, contiguous within each row).
 * Used by the Bitmap-backed export path: Kotlin allocates a Bitmap,
 * locks its pixels, passes the pointer down, then compresses the
 * filled Bitmap via Bitmap.compress / HeifWriter / etc.
 *
 * [outPixels] must have at least srcH × outStride bytes. [outStride]
 * must be at least srcW × 4 (the row may include padding which is
 * preserved untouched).
 *
 * Returns success + dims + duration; no file is written. The kernel
 * still gamma-quantises through `* 255 + 0.5` from Stage A's already-
 * gamma-encoded sRGB float — same as live preview.
 */
StageCResult runStageCToRGBA8(const std::string& stageATifPath,
                              const StageCOptions& options,
                              uint8_t* outPixels,
                              uint32_t outStride);

}  // namespace raw_v3
