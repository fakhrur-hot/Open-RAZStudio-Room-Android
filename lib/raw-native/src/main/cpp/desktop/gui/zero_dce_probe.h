#pragma once
#include <cstdint>
#include <memory>
#include <vector>
#include <string>

namespace razgui {

/**
 * Zero-DCE light probe for AI feature detection.
 *
 * Loads and runs the zero_dce.onnx model (256×256 RGBA input) to detect
 * low-light scenes. Output: average lift score (0..1) indicating how much
 * lightening the image needs.
 *
 * Used by:
 * - AI Color Enhance: decides whether to apply DCE-based color tuning
 * - AI Expose: scores low-light for smart exposure adjustments
 */
class ZeroDceProbe {
public:
    /**
     * Create and initialize the probe. Returns null on failure.
     * modelPath: path to zero_dce.onnx (e.g., "C:\...\models\zero_dce.onnx")
     */
    static std::unique_ptr<ZeroDceProbe> create(const std::string& modelPath);

    ~ZeroDceProbe();

    /**
     * Run inference on an RGBA8 image (any size).
     * Internally downscales to 256×256, runs the model, returns average lift.
     * Returns -1 on error or if model is unavailable.
     *
     * lift: magnitude of enhancement curves the model would apply (0..1 range).
     *       Higher = darker image (more enhancement needed).
     *       Lower = brighter image (less enhancement needed).
     */
    float probeAverageLift(const uint8_t* rgba, int width, int height) const;

    /**
     * Whether the probe is ready to use.
     */
    bool isReady() const { return ready_; }

    ZeroDceProbe() = default;  // Protected for make_unique, external construction via create()

private:
    bool ready_ = false;
    // ONNX Runtime session and environment held here (opaque to header)
    class Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace razgui
