/*
 * StudioRoom — RAW Pipeline v3 — 3D LUT loader.
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Parses a `.cube` 3D LUT (Adobe / DaVinci Resolve format) and uploads it
 * as a GL_TEXTURE_3D with hardware trilinear filtering. See Plan.md §4.1.
 *
 * Format reference: Adobe "Cube LUT Specification" 1.0 (Sep 2013).
 *
 *   TITLE "..."              optional
 *   LUT_3D_SIZE N            required, N ∈ {2..256}, commonly 17/25/32/33/65
 *   DOMAIN_MIN r g b         optional, default 0 0 0
 *   DOMAIN_MAX r g b         optional, default 1 1 1
 *   r g b                    N³ rows of triplets, B outer / G middle / R inner
 *
 * Comments start with `#`. Blank lines ignored.
 */

#pragma once

// parseCubeFile() is pure C++ and is used by the desktop batch engine
// (razbatch), which has no GLES. Only the texture-upload half needs GL, so the
// GL include and that declaration are guarded — otherwise this header cannot be
// included on desktop at all.
#ifndef RAZ_NO_EGL
#include <GLES3/gl3.h>
#endif
#include <cstdint>
#include <string>
#include <vector>

namespace raw_v3 {

struct CubeLut {
    int      size = 0;             // LUT_3D_SIZE (cube edge length)
    float    domainMin[3] = {0, 0, 0};
    float    domainMax[3] = {1, 1, 1};
    // Packed RGB triplets, length = size³ × 3. Row-major in the .cube file
    // order: R fastest, then G, then B (matches the spec). For GL_TEXTURE_3D
    // upload the same ordering matches GL's convention (R == x, G == y, B == z).
    std::vector<float> rgb;
    std::string title;
};

/** Parse the .cube file at [path]. Returns size=0 on failure. */
CubeLut parseCubeFile(const std::string& path);

/**
 * Upload a parsed CubeLut into a GL_TEXTURE_3D and return the texture name.
 * Trilinear filtering, clamp-to-edge. Returns 0 on failure. Caller owns the
 * texture and must glDeleteTextures it on session teardown.
 */
#ifndef RAZ_NO_EGL
GLuint uploadCubeLutAsTexture3D(const CubeLut& lut);
#endif

}  // namespace raw_v3
