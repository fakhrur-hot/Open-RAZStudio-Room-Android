// Single-channel guided filter (He, Sun, Tang 2010), no external deps.
// Refines a soft saliency mask `p` so its edges snap to luma edges in `I`.
//
// Both `guide` and `mask` are float32 planes of size W*H, row-major.
// `radius` is the box-filter half-window in pixels. `eps` is the
// regularization term (variance scale); 1e-3..1e-2 works for [0..1] luma.
//
// Output `out` (size W*H, caller-allocated) is the refined matte in [0..1].
//
// Memory: ~7 W*H float scratch buffers internally; e.g. 6000x4000 ≈ 670 MB.
// For full-res passes prefer downsampled-fast-guided-filter (`scale` > 1) —
// run the linear-model estimation at W/scale × H/scale and upsample a,b
// back to full res before the final reconstruction.
//
// Pure C++17, no OpenCV. Uses two-pass separable box filter (O(W*H)).

#pragma once

#include <cstdint>

namespace v3 {

void guidedFilter(
    const float* guide,
    const float* mask,
    float*       out,
    int          width,
    int          height,
    int          radius,
    float        eps);

// Fast guided filter — estimates a,b at 1/scale resolution and upsamples
// before the final per-pixel reconstruction at full resolution. Quality is
// near-identical to the full-res filter for radius >= scale; runtime drops
// roughly by scale^2. Use scale=4 or 8 for 6000x4000 images.
void guidedFilterFast(
    const float* guide,
    const float* mask,
    float*       out,
    int          width,
    int          height,
    int          radius,
    float        eps,
    int          scale);

} // namespace v3
