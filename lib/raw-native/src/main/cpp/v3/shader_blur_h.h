// 9-tap separable Gaussian blur — horizontal pass (Req 7).
// Used for Orton small-radius (≤8 px) as an alternative to kBokehBlurFrag.
// Reuses existing blurFboA_/blurFboB_ — no new FBOs.
// Weight sum = 0.9994 preserved exactly (no renormalisation).
#pragma once

namespace raw_v3 {

static const char* kBlurHFrag = R"glsl(#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uTex;
uniform float h;
uniform float blurScale;

void main() {
    float s = h * blurScale;
    fragColor = texture(uTex, vec2(vTexCoord.x - 4.0*s, vTexCoord.y)) * 0.051
              + texture(uTex, vec2(vTexCoord.x - 3.0*s, vTexCoord.y)) * 0.0918
              + texture(uTex, vec2(vTexCoord.x - 2.0*s, vTexCoord.y)) * 0.12245
              + texture(uTex, vec2(vTexCoord.x - 1.0*s, vTexCoord.y)) * 0.1531
              + texture(uTex, vec2(vTexCoord.x,          vTexCoord.y)) * 0.1633
              + texture(uTex, vec2(vTexCoord.x + 1.0*s, vTexCoord.y)) * 0.1531
              + texture(uTex, vec2(vTexCoord.x + 2.0*s, vTexCoord.y)) * 0.12245
              + texture(uTex, vec2(vTexCoord.x + 3.0*s, vTexCoord.y)) * 0.0918
              + texture(uTex, vec2(vTexCoord.x + 4.0*s, vTexCoord.y)) * 0.051;
}
)glsl";

} // namespace raw_v3
