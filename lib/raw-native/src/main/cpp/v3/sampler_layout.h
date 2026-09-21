#pragma once

namespace raw_v3 {

struct SamplerLayout {
    int extraMasks;
    int fxVintage;
    int totalSamplers;
    int vintageUnitBase;
    const char* name;
};

inline constexpr SamplerLayout kSamplerLayouts[] = {
    {3, 1, 18, 16, "4-mask+fx"},
    {1, 1, 16,  6, "2-mask+fx"},
    {3, 0, 16, -1, "4-mask"},
    {1, 0, 14, -1, "2-mask"},
    {0, 0, 13, -1, "1-mask"},
};

inline constexpr int kSamplerLayoutCount =
    static_cast<int>(sizeof(kSamplerLayouts) / sizeof(kSamplerLayouts[0]));

inline constexpr int firstSamplerLayoutFor(int maxTextureUnits) {
    for (int i = 0; i < kSamplerLayoutCount; ++i) {
        if (kSamplerLayouts[i].totalSamplers <= maxTextureUnits) return i;
    }
    return -1;
}

}  // namespace raw_v3
