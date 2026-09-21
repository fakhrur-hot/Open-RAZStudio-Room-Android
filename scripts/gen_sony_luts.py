#!/usr/bin/env python3
"""
Generate mathematically-correct Sony log -> Rec.709 conversion .cube LUTs.

Covers the three Sony picture-profile bases StudioRoom ships:
  * S-Log2 / S-Gamut         -> Rec.709   (A7 II PP7)
  * S-Log3 / S-Gamut3        -> Rec.709   (newer bodies, wide gamut)
  * S-Log3 / S-Gamut3.Cine   -> Rec.709   (newer bodies, cine gamut)

Every constant is transcribed from an authoritative, verifiable source
(colour-science, which cites Sony's technical summaries) — nothing from memory:

  S-Log2 decode  = (219/155) * SLog1 * 0.9
      SLog1(y) = 10^((y-0.616596-0.03)/0.432699) - 0.037584   (y >= bp2)
               = (y - bp2)/5.0                                 (y <  bp2)
      bp2 = 0.030001222851889303
  S-Log3 decode  (returns reflection directly; code 420 -> 0.18):
      x = 10^((y*1023 - 420)/261.5) * (0.18 + 0.01) - 0.01     (y >= bp3)
        = (y*1023 - 95) * 0.01125 / (171.2102946929 - 95)      (y <  bp3)
      bp3 = 171.2102946929/1023
  Gamut NPMs (RGB->XYZ, D65) verbatim from colour-science; S-Gamut3 shares
  S-Gamut's primaries so it reuses that NPM. Composed with Rec.709 XYZ->RGB
  (also D65 -> no chromatic adaptation). Output = BT.709 OETF.

Input convention: .cube input [0,1] is the FULL-RANGE log signal (S-Log3 maps it
to 10-bit code as y*1023), because StudioRoom applies LUTs to full-range RGB.
"""
import os
import sys
import numpy as np

SIZE = 33

# ── Log decodes ─────────────────────────────────────────────────────────────
BP2 = 0.030001222851889303
def slog2_to_linear(y):
    s1 = np.where(
        y >= BP2,
        np.power(10.0, (y - 0.616596 - 0.03) / 0.432699) - 0.037584,
        (y - BP2) / 5.0,
    )
    return (219.0 / 155.0) * s1 * 0.9

BP3 = 171.2102946929 / 1023.0
def slog3_to_linear(y):
    c = y * 1023.0
    return np.where(
        y >= BP3,
        np.power(10.0, (c - 420.0) / 261.5) * (0.18 + 0.01) - 0.01,
        (c - 95.0) * 0.01125000 / (171.2102946929 - 95.0),
    )

# HLG (BT.2100) inverse OETF -> scene-linear (normalised 0-1, 1.0 = peak). For an
# SDR Rec.709 down-convert we use the RELATIVE method (inverse OETF, no OOTF/system
# gamma), which the widely-used BBC HLG->SDR LUT follows; documented as such, since
# a full OOTF tone-map is a display-nits choice, not a single canonical answer.
_HLG_A, _HLG_B, _HLG_C = 0.17883277, 0.28466892, 0.55991073
def hlg_to_linear(y):
    return np.where(
        y <= 0.5,
        (y * y) / 3.0,
        (np.exp((y - _HLG_C) / _HLG_A) + _HLG_B) / 12.0,
    )

# ── Gamut NPMs (RGB->XYZ, D65), verbatim from colour-science ────────────────
S_GAMUT_TO_XYZ = np.array([            # also used for S-Gamut3 (shared primaries)
    [ 0.7064827132, 0.1288010498, 0.1151721641],
    [ 0.2709796708, 0.7866064112, -0.0575860820],
    [-0.0096778454, 0.0046000375, 1.0941355587],
])
S_GAMUT3_CINE_TO_XYZ = np.array([
    [ 0.5990839208, 0.2489255161, 0.1024464902],
    [ 0.2150758201, 0.8850685017, -0.1001443219],
    [-0.0320658495, -0.0276583907, 1.1487819910],
])

def npm(primaries, wp):
    r, g, b = primaries
    P = np.array([
        [r[0]/r[1], g[0]/g[1], b[0]/b[1]],
        [1.0, 1.0, 1.0],
        [(1-r[0]-r[1])/r[1], (1-g[0]-g[1])/g[1], (1-b[0]-b[1])/b[1]],
    ])
    W = np.array([wp[0]/wp[1], 1.0, (1-wp[0]-wp[1])/wp[1]])
    return P * np.linalg.solve(P, W)

D65 = (0.3127, 0.3290)
REC709 = [(0.640, 0.330), (0.300, 0.600), (0.150, 0.060)]
BT2020 = [(0.708, 0.292), (0.170, 0.797), (0.131, 0.046)]
XYZ_TO_REC709 = np.linalg.inv(npm(REC709, D65))
BT2020_TO_XYZ = npm(BT2020, D65)     # for the HLG (BT.2020) source

def rec709_oetf(L):
    L = np.clip(L, 0.0, 1.0)
    return np.where(L < 0.018, 4.5 * L, 1.099 * np.power(L, 0.45) - 0.099)

def make_convert(decode, gamut_to_xyz):
    M = XYZ_TO_REC709 @ gamut_to_xyz     # gamut(linear) -> Rec.709(linear), D65
    def convert(rgb):
        lin = decode(rgb) @ M.T
        return np.clip(rec709_oetf(np.clip(lin, 0.0, None)), 0.0, 1.0)
    return convert, M

# ── Per-variant definitions ─────────────────────────────────────────────────
VARIANTS = [
    dict(file="Sony_SLog2_to_Rec709.cube",           title="Sony SLog2 SGamut to Rec709",
         decode=slog2_to_linear, gamut=S_GAMUT_TO_XYZ,       gray=0.323, white=0.582),
    dict(file="Sony_SLog3_SGamut3_to_Rec709.cube",    title="Sony SLog3 SGamut3 to Rec709",
         decode=slog3_to_linear, gamut=S_GAMUT_TO_XYZ,       gray=420/1023, white=598/1023),
    dict(file="Sony_SLog3_SGamut3Cine_to_Rec709.cube",title="Sony SLog3 SGamut3Cine to Rec709",
         decode=slog3_to_linear, gamut=S_GAMUT3_CINE_TO_XYZ, gray=420/1023, white=598/1023),
    # Extra pairing (only non-redundant one — S-Gamut3 shares S-Gamut primaries).
    dict(file="Sony_SLog2_SGamut3Cine_to_Rec709.cube",title="Sony SLog2 SGamut3Cine to Rec709",
         decode=slog2_to_linear, gamut=S_GAMUT3_CINE_TO_XYZ, gray=0.323, white=0.582),
    # HLG (PP10, BT.2020) -> Rec.709 SDR, relative method (see hlg_to_linear note).
    dict(file="Sony_HLG_BT2020_to_Rec709.cube",       title="Sony HLG BT2020 to Rec709",
         decode=hlg_to_linear,   gamut=BT2020_TO_XYZ,        gray=0.5, white=0.75),
]

def emit(v, out_dir):
    convert, M = make_convert(v["decode"], v["gamut"])
    print(f"\n== {v['title']} ==")
    for lbl, y in [("18% gray", v["gray"]), ("90% white", v["white"]), ("black", 0.0), ("clip 100%", 1.0)]:
        arr = np.array([[y, y, y]])
        lin = v["decode"](arr)[0, 0]
        out = convert(arr)[0, 0]
        print(f"  {lbl:10s} sig={y:.4f} -> lin={lin:.4f} -> 709={out:.4f}")
    grid = np.linspace(0.0, 1.0, SIZE)
    lines = []
    for b in grid:
        for g in grid:
            for r in grid:
                o = convert(np.array([[r, g, b]]))[0]
                lines.append("%.6f %.6f %.6f" % (o[0], o[1], o[2]))
    header = (
        f'TITLE "{v["title"]}"\n'
        "# Mathematically generated (Sony log decode + gamut->Rec.709 matrix +\n"
        "# BT.709 OETF). Input = full-range log signal [0,1]. scripts/gen_sony_luts.py\n"
        f"LUT_3D_SIZE {SIZE}\nDOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n"
    )
    path = os.path.join(out_dir, v["file"])
    with open(path, "w", newline="\n") as f:
        f.write(header); f.write("\n".join(lines)); f.write("\n")
    print(f"  wrote {path}")

if __name__ == "__main__":
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out_dir, exist_ok=True)
    for v in VARIANTS:
        emit(v, out_dir)
