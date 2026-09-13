#include "ai_macro_generators.h"
#include <algorithm>
#include <cmath>

namespace razgui {

// Slot indices from ShaderParams.kt / app_main.cpp
// [0] exposure, [1] contrast, [6] saturation, [7] vibrance, [8] whiteBalance, [9] tint
// [205] bloomRadius, [207] filmRolloff, [2] highlights, [3] shadows

std::vector<std::pair<int, float>> AiMacroGenerators::generateAiColorEnhance(float dceScore) {
    std::vector<std::pair<int, float>> adjustments;

    // Clamp DCE score to [0, 1]
    dceScore = std::max(0.f, std::min(1.f, dceScore));

    // If image is already well-lit, return identity (no adjustments)
    if (dceScore < DCE_THRESHOLD) {
        return adjustments;  // Empty = identity
    }

    // Smoothly interpolate enhancement strength from threshold to max
    float strength = (dceScore - DCE_THRESHOLD) / (1.f - DCE_THRESHOLD);
    strength = std::max(0.f, std::min(1.f, strength));

    // AI Color Enhance strategy:
    // 1. Mild saturation boost (0 → 0.2 at max strength)
    // 2. Vibrance boost (0 → 0.3 at max strength) — preserves desaturated colors
    // 3. Subtle warmth tint (0 → +80 at max strength)
    // 4. Slight exposure lift (0 → +0.3 EV at max strength)

    float saturation_boost = strength * 0.2f;
    float vibrance_boost = strength * 0.3f;
    float tint_warmth = strength * 80.f;  // Positive = warmer/more magenta
    float exposure_lift = strength * 0.3f;

    adjustments.push_back({6, saturation_boost});      // Saturation [6]
    adjustments.push_back({7, vibrance_boost});        // Vibrance [7]
    adjustments.push_back({9, tint_warmth});           // Tint [9] (warmth)
    adjustments.push_back({0, exposure_lift});         // Exposure [0]

    return adjustments;
}

std::vector<std::pair<int, float>> AiMacroGenerators::generateAiExpose(
    float dceScore, float blowFraction) {
    std::vector<std::pair<int, float>> adjustments;

    // Clamp inputs
    dceScore = std::max(0.f, std::min(1.f, dceScore));
    blowFraction = std::max(0.f, std::min(1.f, blowFraction));

    // If image is already well-lit and no blown highlights, return identity
    if (dceScore < DCE_THRESHOLD && blowFraction < BLOW_THRESHOLD) {
        return adjustments;
    }

    // AI Expose strategy:
    // 1. Exposure lift based on DCE score
    // 2. Shadow/highlight region expansion (tone regions)
    // 3. Filmic shoulder (rolloff) if blown pixels detected

    // Exposure lift: ramp from 0 to +1.5 EV as dceScore goes from 0.3 to 1.0
    float exposure_strength = 0.f;
    if (dceScore >= DCE_THRESHOLD) {
        exposure_strength = (dceScore - DCE_THRESHOLD) / (1.f - DCE_THRESHOLD);
        exposure_strength = std::max(0.f, std::min(1.f, exposure_strength));
    }
    float exposure_lift = exposure_strength * 1.5f;

    // Shadow expansion: lift shadows to reveal detail in low-light
    float shadow_lift = exposure_strength * 0.3f;

    // Highlight compression: recover blown-out zones
    float highlight_compress = exposure_strength * -0.2f;

    // Filmic shoulder: apply rolloff to handle blown pixels gracefully
    // Rolloff = 0 (none) to 1 (full). Triggered by blowFraction > threshold.
    float filmic_shoulder = 0.f;
    if (blowFraction > BLOW_THRESHOLD) {
        filmic_shoulder = (blowFraction - BLOW_THRESHOLD) / (1.f - BLOW_THRESHOLD);
        filmic_shoulder = std::max(0.f, std::min(1.f, filmic_shoulder));
        filmic_shoulder *= 0.6f;  // Cap at 0.6 to avoid overpowering the image
    }

    // Build adjustment vector
    if (exposure_lift != 0) adjustments.push_back({0, exposure_lift});              // Exposure [0]
    if (shadow_lift != 0) adjustments.push_back({3, shadow_lift});                  // Shadows [3]
    if (highlight_compress != 0) adjustments.push_back({2, highlight_compress});    // Highlights [2]
    if (filmic_shoulder != 0) adjustments.push_back({207, filmic_shoulder});        // Film Rolloff [207]

    return adjustments;
}

} // namespace razgui
