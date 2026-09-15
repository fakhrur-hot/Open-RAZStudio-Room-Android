// Laplacian 3×3 sharpen fragment shader (Req 6).
// Post-uber output sharpening — cheap single-pass convolution.
// Ported from Haxademic — GLES 3.0, highp, 9-texel kernel.
// Selective-bokeh: optional subject-only gate so background OOF stays soft.
#pragma once

namespace raw_v3 {

static const char* kSharpenFrag = R"glsl(#version 300 es
precision highp float;
precision highp int;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uTex;
uniform float uSharpness;
uniform vec2  uTexelSize;
// Subject-aware sharpen (selective bokeh look). When uSubjectOnly==1 and
// the mask is ready, sharpen mixes in only on the subject (gate = p²).
uniform sampler2D uSubjectMask;
uniform int   uSubjectMaskEnabled;
uniform vec4  uSubjectMaskRect;
uniform int   uSubjectOnly;

void main() {
    vec4 center = texture(uTex, vTexCoord);
    if (uSharpness == 0.0) { fragColor = center; return; }
    const vec2 offsets[9] = vec2[](
        vec2(-1,-1), vec2(0,-1), vec2(1,-1),
        vec2(-1, 0), vec2(0, 0), vec2(1, 0),
        vec2(-1, 1), vec2(0, 1), vec2(1, 1));
    vec4 n[9];
    for (int i = 0; i < 9; i++)
        n[i] = texture(uTex, vTexCoord + offsets[i] * uSharpness * uTexelSize);
    vec4 sharp = n[4] * 9.0 - (n[0]+n[1]+n[2]+n[3]+n[5]+n[6]+n[7]+n[8]);
    float gate = 1.0;
    if (uSubjectOnly == 1 && uSubjectMaskEnabled == 1) {
        vec2 maskUV = mix(uSubjectMaskRect.xy, uSubjectMaskRect.zw, vTexCoord);
        float p = clamp(texture(uSubjectMask, maskUV).r, 0.0, 1.0);
        gate = p * p;
    }
    fragColor = mix(center, sharp, gate);
}
)glsl";

} // namespace raw_v3
