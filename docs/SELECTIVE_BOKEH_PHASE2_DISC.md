# Selective bokeh — Phase 2 (disc / aperture)

**Locked:** CoC gate math in `shader_sources.cpp` (golden 8/8). Do not change
`coc = abs(depth - focus)` or `bgGate *= smoothstep(0.02, 0.55, coc)`.

**This phase:** when `uDepthMapEnabled`, bokeh blur **consumes** `bgGate`
(already CoC×subject×atten) as **Vogel-disc radius** on `uTex`, not as a mix
weight against fixed-radius `uBlurTex`.

| CoC / bgGate | Result |
|--------------|--------|
| 0 | r=0 → original pixel |
| small | slight disc defocus |
| large | strong disc defocus |

Depth-off path keeps legacy Gaussian `uBlurTex` mix.

**Not in this phase:** signed CoC, hard mask zero refactor, optical finishing.
