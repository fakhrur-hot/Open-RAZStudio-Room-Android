# Selective bokeh — Phase 3 (signed CoC)

**Locked (unchanged magnitude):** `cocAbs = abs(depth - focus)`;
`bgGate *= smoothstep(0.02, 0.55, cocAbs)` — abs golden 8/8 still the contract.

**Phase 3:** `cocSigned = depth - focus`
| signed | meaning | path |
|--------|---------|------|
| < 0 | near / foreground | `nearGate` → smaller Vogel radius |
| = 0 | focus plane | both gates 0 → original |
| > 0 | far / background | `farGate` → larger Vogel radius |

Phase 2 Vogel-16 disc consumer kept (not Gaussian). Stage C mirrors near/far.

**Out of scope:** Phase 4 hard subject CoC=0, Phase 5 optical finishing, grade/sharpen.
