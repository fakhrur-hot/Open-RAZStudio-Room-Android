/*
 * StudioRoom — RAW Pipeline v3
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Film simulation engine: 8 Fujifilm-inspired profiles with optional grain.
 * Hybrid execution: LibRaw native matrix injection (profiles 2,3,5,6,7) and
 * post-decode pixel loop (profiles 1,4). Grain always runs post-decode.
 */

#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>

// Forward-declare LibRaw so the header doesn't pull in the full libraw include
// chain. Callers that need setup_native_libraw_profile() include libraw/libraw.h
// before this header.
class LibRaw;

namespace raw_v3 {

// ── Profile index (Tasks 3.1, 4) ─────────────────────────────────────────────
// Ordinal MUST match FilmProfile.ordinal in Kotlin — do not reorder.
enum ProfileIndex {
    PROFILE_DEFAULT        = 0,
    PROFILE_CLASSIC_NEG    = 1,
    PROFILE_VELVIA         = 2,
    PROFILE_PROVIA         = 3,
    PROFILE_ACROS          = 4,
    PROFILE_CLASSIC_CHROME = 5,
    PROFILE_ASTIA          = 6,
    PROFILE_ETERNA         = 7,
    // ── X-Trans II/III/IV/V additions — ordinals must match FilmProfile.kt ──
    PROFILE_PRO_NEG_STD    = 8,
    PROFILE_PRO_NEG_HI     = 9,
    PROFILE_ETERNA_BLEACH  = 10,
    PROFILE_NOSTALGIC_NEG  = 11,
    PROFILE_REALA_ACE      = 12,
    PROFILE_ACROS_Y        = 13,
    PROFILE_ACROS_R        = 14,
    PROFILE_ACROS_G        = 15,
};

// ── LibRaw native matrix injection (Tasks 3.2, 4) ────────────────────────────
/**
 * Inject a custom color matrix + uniform gamma into LibRaw's internal engine.
 * MUST be called AFTER LibRaw::unpack() and BEFORE LibRaw::dcraw_process().
 *
 * Eligible profiles (2,3,5,6,7) populate imgdata.color.rgb_cam[3][4] and
 * imgdata.params.gamm[]. LibRaw then applies the matrix inside its own
 * SIMD/AVX-optimised loop — no separate post-decode pixel scan needed.
 *
 * Ineligible profiles (0=DEFAULT, 1=CLASSIC_NEG, 4=ACROS) return false;
 * the caller must use the post-decode CPU path via applyFilmSimCpu().
 *
 * @return true  → native path set up; call dcraw_process() then write TIFF.
 *         false → profile needs post-decode CPU path.
 */
bool setup_native_libraw_profile(LibRaw& processor, int profile);

// ── Per-pixel transform (Tasks 3.3, 5) ───────────────────────────────────────
/**
 * Apply film simulation transform to a single FP32 pixel in-place.
 * [rgb] points to 3 consecutive floats normalized to [0,1].
 * Returns immediately for PROFILE_DEFAULT (no-op).
 *
 * @param rgb          R/G/B float[3], modified in-place.
 * @param profile      ProfileIndex enum value (0–7).
 * @param grain_amount Grain amplitude [0..0.10]; 0 = disabled.
 * @param grain_seed   Per-pixel deterministic seed (e.g. row*width+col).
 */
inline void apply_profile_transforms(
    float* rgb,        // sRGB-encoded FP16 values from the Stage A TIFF, normalised [0,1]
    int profile,
    float grain_amount,
    uint32_t grain_seed)
{
    if (profile == PROFILE_DEFAULT) return;

    // ── sRGB → linear ────────────────────────────────────────────────────────
    // Stage A TIFF stores sRGB-gamma-encoded values (linearToSrgb applied in
    // stage_a.cpp). All colour matrix work must happen in linear light.
    auto toLinear = [](float v) -> float {
        if (v <= 0.f)        return 0.f;
        if (v >= 1.f)        return 1.f;
        if (v <= 0.04045f)   return v * (1.f / 12.92f);
        return std::pow((v + 0.055f) * (1.f / 1.055f), 2.4f);
    };
    auto toSrgb = [](float v) -> float {
        if (v <= 0.f)        return 0.f;
        if (v >= 1.f)        return 1.f;
        if (v <= 0.0031308f) return v * 12.92f;
        return 1.055f * std::pow(v, 1.f / 2.4f) - 0.055f;
    };

    float r = toLinear(rgb[0]);
    float g = toLinear(rgb[1]);
    float b = toLinear(rgb[2]);
    float nr = r, ng = g, nb = b;

    switch (profile) {
        case PROFILE_CLASSIC_NEG:
            // Muted greens, dense shadows, cold cyan cast.
            nr = (r * 0.95f) + (g * 0.02f) + (b * 0.01f);
            ng = (r * 0.01f) + (g * 0.82f) + (b * 0.02f);
            nb = (r * 0.03f) + (g * 0.05f) + (b * 0.92f);
            nr = std::clamp(nr, 0.f, 1.f);
            ng = std::clamp(ng, 0.f, 1.f);
            nb = std::clamp(nb, 0.f, 1.f);
            // Asymmetric tone: darken slightly, blue shadows cooler
            nr = std::pow(nr, 1.20f);
            ng = std::pow(ng, 1.20f);
            nb = std::pow(nb, 1.10f);
            break;

        case PROFILE_VELVIA:
            // Ultra-saturated: negative crosstalk widens gamut.
            nr =  (r * 1.15f) - (g * 0.10f) - (b * 0.05f);
            ng = -(r * 0.05f) + (g * 1.20f) - (b * 0.15f);
            nb = -(r * 0.02f) - (g * 0.08f) + (b * 1.10f);
            nr = std::clamp(nr, 0.f, 1.f);
            ng = std::clamp(ng, 0.f, 1.f);
            nb = std::clamp(nb, 0.f, 1.f);
            // Slight midtone darkening in linear space → richer, punchier
            nr = std::pow(nr, 1.05f);
            ng = std::pow(ng, 1.05f);
            nb = std::pow(nb, 1.05f);
            break;

        case PROFILE_PROVIA:
            // Near-unity: natural fidelity, accurate skin tones.
            nr =  (r * 1.02f) - (g * 0.01f) - (b * 0.01f);
            ng = -(r * 0.01f) + (g * 1.02f) - (b * 0.01f);
            nb = -(r * 0.01f) - (g * 0.01f) + (b * 1.04f);
            nr = std::clamp(nr, 0.f, 1.f);
            ng = std::clamp(ng, 0.f, 1.f);
            nb = std::clamp(nb, 0.f, 1.f);
            // No tone curve adjustment — smooth neutral gradations
            break;

        case PROFILE_ACROS: {
            // Panchromatic collapse: 0.28R + 0.63G + 0.09B in linear light.
            // Green-weighted → bright foliage/skin, dark blue skies.
            float y = (0.28f * r) + (0.63f * g) + (0.09f * b);
            y = std::clamp(y, 0.f, 1.f);
            y = std::pow(y, 1.15f);   // contrast boost in linear → punchy B&W
            nr = ng = nb = y;
            break;
        }

        case PROFILE_CLASSIC_CHROME:
            // Low saturation, muted warm tones, cool documentary cast.
            nr =  (r * 1.05f) - (g * 0.05f);
            ng = -(r * 0.02f) + (g * 0.94f) + (b * 0.08f);
            nb = -(r * 0.01f) - (g * 0.06f) + (b * 1.07f);
            nr = std::clamp(nr, 0.f, 1.f);
            ng = std::clamp(ng, 0.f, 1.f);
            nb = std::clamp(nb, 0.f, 1.f);
            // Asymmetric tone: slightly cooler blue
            nr = std::pow(nr, 1.15f);
            ng = std::pow(ng, 1.15f);
            nb = std::pow(nb, 1.12f);
            break;

        case PROFILE_ASTIA:
            // Fashion/portrait: saturated secondaries, soft accurate skin.
            nr =  (r * 1.06f) - (g * 0.04f) - (b * 0.02f);
            ng = -(r * 0.04f) + (g * 1.12f) - (b * 0.08f);
            nb = -(r * 0.01f) - (g * 0.09f) + (b * 1.10f);
            nr = std::clamp(nr, 0.f, 1.f);
            ng = std::clamp(ng, 0.f, 1.f);
            nb = std::clamp(nb, 0.f, 1.f);
            // Gentle toe darkening
            nr = std::pow(nr, 1.02f);
            ng = std::pow(ng, 1.02f);
            nb = std::pow(nb, 1.02f);
            break;

        case PROFILE_ETERNA:
            // Flat cinematic: deeply muted saturation, lifted shadows.
            nr = (r * 0.88f) + (g * 0.06f) + (b * 0.06f);
            ng = (r * 0.05f) + (g * 0.88f) + (b * 0.07f);
            nb = (r * 0.05f) + (g * 0.05f) + (b * 0.90f);
            nr = std::clamp(nr, 0.f, 1.f);
            ng = std::clamp(ng, 0.f, 1.f);
            nb = std::clamp(nb, 0.f, 1.f);
            // gamma < 1.0 in linear lifts shadows → flat/log-like look
            nr = std::pow(nr, 0.92f);
            ng = std::pow(ng, 0.92f);
            nb = std::pow(nb, 0.92f);
            break;

        case PROFILE_PRO_NEG_STD:
            // Studio portrait: flat contrast, wide tonal range, accurate skin.
            // Near-unity matrix; pulled-down contrast via gentle S-curve midtone lift.
            nr = (r * 0.98f) - (g * 0.01f) + (b * 0.00f);
            ng = (r * 0.00f) + (g * 0.97f) - (b * 0.01f);
            nb = (r * 0.01f) - (g * 0.01f) + (b * 0.96f);
            nr = std::clamp(nr, 0.f, 1.f);
            ng = std::clamp(ng, 0.f, 1.f);
            nb = std::clamp(nb, 0.f, 1.f);
            // gamma > 1.0 darkens slightly → lower apparent contrast in midtones
            nr = std::pow(nr, 1.08f);
            ng = std::pow(ng, 1.08f);
            nb = std::pow(nb, 1.08f);
            break;

        case PROFILE_PRO_NEG_HI:
            // Outdoor portrait: crisper contrast than Std, controlled highlights.
            nr =  (r * 1.01f) - (g * 0.01f) + (b * 0.00f);
            ng = -(r * 0.01f) + (g * 1.00f) + (b * 0.00f);
            nb =  (r * 0.00f) - (g * 0.01f) + (b * 1.01f);
            nr = std::clamp(nr, 0.f, 1.f);
            ng = std::clamp(ng, 0.f, 1.f);
            nb = std::clamp(nb, 0.f, 1.f);
            // Neutral gamma — lets the matrix give the slight pop without crushing
            nr = std::pow(nr, 1.03f);
            ng = std::pow(ng, 1.03f);
            nb = std::pow(nb, 1.03f);
            break;

        case PROFILE_ETERNA_BLEACH:
            // Cinema bleach bypass: high contrast, very low saturation, cool silver.
            // Desaturate then push contrast strongly.
            {
                float lum = (0.2126f * r) + (0.7152f * g) + (0.0722f * b);
                // Retain a fraction of the chroma so it's not pure mono
                nr = lum + 0.15f * (r - lum);
                ng = lum + 0.15f * (g - lum);
                nb = lum + 0.15f * (b - lum);
                nr = std::clamp(nr, 0.f, 1.f);
                ng = std::clamp(ng, 0.f, 1.f);
                nb = std::clamp(nb, 0.f, 1.f);
                // Strong contrast curve: darken shadows, protect highlights
                nr = std::pow(nr, 1.40f);
                ng = std::pow(ng, 1.40f);
                nb = std::pow(nb, 1.35f);  // slightly cooler blue toe
            }
            break;

        case PROFILE_NOSTALGIC_NEG:
            // 1970s American New Color: warm, low contrast, high saturation.
            // Warm push (lift R, suppress B), Eterna-style shadow lift,
            // Velvia-style chroma boost.
            nr =  (r * 1.08f) + (g * 0.02f) - (b * 0.05f);
            ng = -(r * 0.01f) + (g * 1.00f) + (b * 0.02f);
            nb = -(r * 0.04f) - (g * 0.03f) + (b * 0.88f);
            nr = std::clamp(nr, 0.f, 1.f);
            ng = std::clamp(ng, 0.f, 1.f);
            nb = std::clamp(nb, 0.f, 1.f);
            // Shadow lift (gamma < 1) + warm cast persists through encode
            nr = std::pow(nr, 0.94f);
            ng = std::pow(ng, 0.96f);
            nb = std::pow(nb, 0.98f);  // blue stays slightly darker → warm
            break;

        case PROFILE_REALA_ACE:
            // Print-film: deep soft shadows, hard highlights, no digital muddiness.
            // Slight desaturation in shadows, boosted mid-chroma.
            nr =  (r * 1.04f) - (g * 0.02f) - (b * 0.01f);
            ng = -(r * 0.01f) + (g * 1.03f) - (b * 0.01f);
            nb = -(r * 0.01f) - (g * 0.02f) + (b * 1.06f);
            nr = std::clamp(nr, 0.f, 1.f);
            ng = std::clamp(ng, 0.f, 1.f);
            nb = std::clamp(nb, 0.f, 1.f);
            // Moderate darkening — print paper response (deeper blacks)
            nr = std::pow(nr, 1.10f);
            ng = std::pow(ng, 1.10f);
            nb = std::pow(nb, 1.10f);
            break;

        case PROFILE_ACROS_Y: {
            // Acros with yellow filter: boosts skin/warm tones, darkens blue sky.
            // Panchromatic weights shifted: R↑ G= B↓
            float y = (0.40f * r) + (0.55f * g) + (0.05f * b);
            y = std::clamp(y, 0.f, 1.f);
            y = std::pow(y, 1.15f);
            nr = ng = nb = y;
            break;
        }

        case PROFILE_ACROS_R: {
            // Acros with red filter: very dark blue sky, high drama, bright warm skin.
            float y = (0.55f * r) + (0.40f * g) + (0.05f * b);
            y = std::clamp(y, 0.f, 1.f);
            y = std::pow(y, 1.20f);  // extra contrast for landscape drama
            nr = ng = nb = y;
            break;
        }

        case PROFILE_ACROS_G: {
            // Acros with green filter: bright foliage, natural skin, darkened red lips.
            float y = (0.18f * r) + (0.70f * g) + (0.12f * b);
            y = std::clamp(y, 0.f, 1.f);
            y = std::pow(y, 1.12f);
            nr = ng = nb = y;
            break;
        }

        default:
            break;
    }

    // ── linear → sRGB re-encode ──────────────────────────────────────────────
    nr = toSrgb(nr);
    ng = toSrgb(ng);
    nb = toSrgb(nb);

    // ── Grain pass — applied in sRGB domain to match display noise ───────────
    if (grain_amount > 0.f) {
        float luma = (0.2126f * nr) + (0.7152f * ng) + (0.0722f * nb);
        float mask = 4.f * luma * (1.f - luma);  // parabolic midtone mask
        float n = static_cast<float>(grain_seed * 1664525u + 1013904223u) / 4294967296.f;
        float grain = (n - 0.5f) * 2.f * grain_amount * mask;
        nr = std::clamp(nr + grain, 0.f, 1.f);
        ng = std::clamp(ng + grain, 0.f, 1.f);
        nb = std::clamp(nb + grain, 0.f, 1.f);
    }

    rgb[0] = nr;
    rgb[1] = ng;
    rgb[2] = nb;
}

// ── Hybrid router (Tasks 3.4, 6) ─────────────────────────────────────────────
/**
 * Apply film simulation to a Stage A BigTIFF (Task 6).
 *
 * Reads [inputTifPath] (pristine linear TIFF, read-only), applies the profile
 * matrix + gamma + grain using the hybrid path (LibRaw native for profiles
 * 2,3,5,6,7; post-decode pixel loop for 1,4), and writes to [outputTifPath].
 *
 * On failure: [outputTifPath] is deleted (atomic — no partial file).
 *
 * @return true on success; false if outputTifPath was not created.
 */
bool applyFilmSimCpu(
    const std::string& inputTifPath,
    const std::string& outputTifPath,
    int profileIndex,
    float grainAmount);

}  // namespace raw_v3
