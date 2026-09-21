#include "sampler_layout.h"

#include <cassert>

int main() {
    using namespace raw_v3;

    const int layout16 = firstSamplerLayoutFor(16);
    assert(layout16 == 1);
    const SamplerLayout& mobile = kSamplerLayouts[layout16];
    assert(mobile.extraMasks == 1);
    assert(mobile.fxVintage == 1);
    assert(mobile.vintageUnitBase == 6);
    assert(mobile.vintageUnitBase + 1 == 7);
    assert(mobile.vintageUnitBase != 8);
    assert(mobile.vintageUnitBase != 9);
    assert(mobile.vintageUnitBase + 1 != 8);
    assert(mobile.vintageUnitBase + 1 != 9);

    // Shared fixed assignments: bokeh=8, tone curve=9, curves=12..15.
    assert(mobile.vintageUnitBase != 12);
    assert(mobile.vintageUnitBase + 1 != 12);
    assert(mobile.vintageUnitBase != 15);
    assert(mobile.vintageUnitBase + 1 != 15);

    assert(firstSamplerLayoutFor(18) == 0);
    assert(firstSamplerLayoutFor(14) == 3);
    assert(firstSamplerLayoutFor(13) == 4);
    assert(firstSamplerLayoutFor(12) == -1);

    const SamplerLayout& full = kSamplerLayouts[0];
    assert(full.vintageUnitBase == 16);
    assert(full.vintageUnitBase + 1 == 17);
    assert(full.vintageUnitBase > 15);
    assert(full.extraMasks == 3);
    assert(full.vintageUnitBase != 3 && full.vintageUnitBase != 5);
    assert(full.vintageUnitBase + 1 != 3 && full.vintageUnitBase + 1 != 5);
    return 0;
}
