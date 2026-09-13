#pragma once
#include <vector>
#include <string>

namespace razgui {

/**
 * AI macro generators: convert DCE lightness scores into adjustment presets.
 * These macros get baked into the 410-float ShaderParams blob.
 */
class AiMacroGenerators {
public:
    /**
     * Generate AI Color Enhance preset from DCE lightness score.
     *
     * dceScore: average lift from Zero-DCE model (0..1 range).
     *           Typically 0..0.5 for most images; >0.6 = very dark.
     *
     * Returns: adjustments vector [slot] = value, populated for slots that
     *          need non-identity values. Empty slots are left at identity.
     *
     * Strategy: For low-light images (dceScore > ~0.3), blend in saturation
     * boost, vibrance, slight exposure lift, and warmth tint. Smoothly scales
     * from no-op at dceScore=0 to max enhancement at dceScore=1.
     */
    static std::vector<std::pair<int, float>> generateAiColorEnhance(float dceScore);

    /**
     * Generate AI Expose preset: smart exposure + blown-pixel recovery.
     *
     * dceScore: lightness score from Zero-DCE (same as above).
     * blowFraction: fraction of pixels above 90% brightness (0..1).
     *
     * Returns: adjustments for exposure, tone regions, and filmic shoulder.
     *
     * Strategy:
     * - Low-light (dceScore > 0.3): lift exposure, widen shadows.
     * - Blown highlights (blowFraction > 0.05): apply filmic shoulder to recover detail.
     * - Combines both axes: dark+blown → aggressive recovery; dark+clean → mild lift.
     */
    static std::vector<std::pair<int, float>> generateAiExpose(float dceScore, float blowFraction);

private:
    static constexpr float DCE_THRESHOLD = 0.3f;   // Below = well-lit, no enhancement
    static constexpr float DCE_MAX_BOOST = 1.0f;   // Saturation boost at high DCE
    static constexpr float BLOW_THRESHOLD = 0.05f; // Threshold for filmic shoulder
};

} // namespace razgui
