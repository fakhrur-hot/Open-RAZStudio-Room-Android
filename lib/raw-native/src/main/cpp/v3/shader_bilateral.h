// Bilateral denoise fragment shader (Req 3).
// Spatial pre-pass: runs at full preview resolution before the uber-shader.
// Ported from Haxademic smartDeNoise — GLES 3.0, highp, circular kernel.
#pragma once

namespace raw_v3 {

static const char* kBilateralFrag = R"glsl(#version 300 es
precision highp float;
precision highp int;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uTex;
uniform float uSigma;
uniform float uKSigma;
uniform float uThreshold;

#define INV_SQRT_OF_2PI 0.39894228040143267793994605993439
#define INV_PI          0.31830988618379067153776752674503

void main() {
    if (uSigma <= 0.0 && uThreshold <= 0.0) {
        fragColor = texture(uTex, vTexCoord);
        return;
    }
    float sigma = max(uSigma, 0.001);
    float threshold = max(uThreshold, 0.001);
    float radius = round(uKSigma * sigma);
    float radQ = radius * radius;
    float invSigmaQx2      = 0.5 / (sigma * sigma);
    float invSigmaQx2PI    = INV_PI * invSigmaQx2;
    float invThresholdSqx2    = 0.5 / (threshold * threshold);
    float invThresholdSqrt2PI = INV_SQRT_OF_2PI / threshold;
    vec4  centrPx = texture(uTex, vTexCoord);
    float zBuff = 0.0;
    vec4  aBuff = vec4(0.0);
    vec2  size = vec2(textureSize(uTex, 0));
    for (float dx = -radius; dx <= radius; dx += 1.0) {
        float pt = sqrt(radQ - dx * dx);
        for (float dy = -pt; dy <= pt; dy += 1.0) {
            vec2  d = vec2(dx, dy);
            float blurFactor  = exp(-dot(d, d) * invSigmaQx2) * invSigmaQx2PI;
            vec4  walkPx      = texture(uTex, vTexCoord + d / size);
            vec4  dC          = walkPx - centrPx;
            float deltaFactor = exp(-dot(dC, dC) * invThresholdSqx2)
                              * invThresholdSqrt2PI * blurFactor;
            zBuff += deltaFactor;
            aBuff += deltaFactor * walkPx;
        }
    }
    fragColor = aBuff / zBuff;
}
)glsl";

} // namespace raw_v3
