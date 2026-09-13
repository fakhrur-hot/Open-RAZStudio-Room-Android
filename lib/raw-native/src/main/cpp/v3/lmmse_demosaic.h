#pragma once
#include <cstdint>
namespace raw_v3 {
bool lmmse_demosaic_to_planes(
    const uint16_t* rawImg,
    int rawW, int rawH,
    int cropL, int cropT,
    int outW, int outH,
    unsigned filters,
    float blackLevel, float whiteLevel,
    const float camMul[4],
    float* outR, float* outG, float* outB);
}
