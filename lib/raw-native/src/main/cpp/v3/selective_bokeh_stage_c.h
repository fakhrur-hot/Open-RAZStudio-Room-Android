#pragma once
/* Selective disc bokeh for Stage C — mirrors kFragSrc Phase 2 (Vogel-16).
 * Locked abs CoC: coc=abs(depth-focus); bgGate*=smoothstep(0.02,0.55,coc).
 * Phase 3: cocSigned=depth-focus; nearGate/farGate split Vogel radii.
 * Phase 4: hard subject CoC=0 (dilated mask); disc samples reject subject.
 */
#include "apply_macro.h"
#include <cstdint>

namespace raw_v3 {

struct SelectiveBokehInputs {
    const ApplyMacroSubjectMask* subject = nullptr;
    const ApplyMacroSubjectMask* atten   = nullptr;
    const float* depthMap = nullptr; // [0,1] row-major depthW*depthH
    int depthW = 0;
    int depthH = 0;
    float focusDepth = 0.5f;
    float bokehBlur = 0.f;   // [179] 0..1
    float bokehSpread = 0.f; // [181] 0..1
    float bokehBalls = 0.f;  // [180] 0..1
};

/** In-place on graded RGBA8 (stride bytes/row). No-op if bokehBlur<=0 or no subject. */
void applySelectiveBokehDiscRGBA8(uint8_t* rgba, int w, int h, int strideBytes,
                                  const SelectiveBokehInputs& in);

/** In-place on planar/interleaved RGB16 (3 channels). */
void applySelectiveBokehDiscRGB16(uint16_t* rgb, int w, int h,
                                  const SelectiveBokehInputs& in);

} // namespace raw_v3
