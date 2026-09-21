#!/usr/bin/env python3
"""
One-step "Sony PP7 (S-Log2/S-Gamut) -> graded Rec.709" look LUTs, for people who
shoot 8-bit JPEG in PP7 on an A7 II and want a single LUT that both converts AND
grades — no chain-ordering mistakes.

Applies raw-alchemy's core lesson (feed a creative LUT its authored input space)
by COMPOSING, in one .cube:  S-Log2 signal -> decode -> S-Gamut->Rec.709 -> BT.709
OETF (= the correct Rec.709 the looks are authored for) -> creative look.

Reuses the VERIFIED technical conversion (scripts/gen_sony_luts.py) and the ORIGINAL
creative looks (scripts/gen_creative_luts.py) — nothing copied from third-party LUTs.
"""
import os
import sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_sony_luts import (               # verified S-Log2/S-Gamut -> Rec.709
    slog2_to_linear, S_GAMUT_TO_XYZ, XYZ_TO_REC709, rec709_oetf,
)
from gen_creative_luts import (           # original creative looks (Rec.709 in)
    look_teal_orange, look_warm_film, look_cool_cinematic,
    look_bleach_bypass, look_vintage_fade,
)

SIZE = 33
_M = XYZ_TO_REC709 @ S_GAMUT_TO_XYZ       # S-Gamut(linear) -> Rec.709(linear)

def pp7_to_rec709(rgb):
    """S-Log2/S-Gamut signal [0,1] -> Rec.709 encoded [0,1] (the technical base)."""
    lin = slog2_to_linear(rgb) @ _M.T
    return np.clip(rec709_oetf(np.clip(lin, 0.0, None)), 0.0, 1.0)

def _identity(x):
    return x

# (file, title, creative-look fn applied AFTER the technical convert)
LOOKS = [
    ("Sony_PP7_to_Rec709_Neutral.cube", "Sony PP7 to Rec709 Neutral", _identity),
    ("Sony_PP7_Teal_and_Orange.cube",   "Sony PP7 Teal and Orange",   look_teal_orange),
    ("Sony_PP7_Warm_Film.cube",         "Sony PP7 Warm Film",         look_warm_film),
    ("Sony_PP7_Cool_Cinematic.cube",    "Sony PP7 Cool Cinematic",    look_cool_cinematic),
    ("Sony_PP7_Bleach_Bypass.cube",     "Sony PP7 Bleach Bypass",     look_bleach_bypass),
    ("Sony_PP7_Vintage_Fade.cube",      "Sony PP7 Vintage Fade",      look_vintage_fade),
]

def emit(fname, title, look, out_dir):
    grid = np.linspace(0.0, 1.0, SIZE)
    lines = []
    for b in grid:
        for g in grid:
            for r in grid:
                base = pp7_to_rec709(np.array([[r, g, b]]))[0]   # -> Rec.709
                o = look(base)                                    # -> graded
                o = np.clip(o, 0.0, 1.0)
                lines.append("%.6f %.6f %.6f" % (o[0], o[1], o[2]))
    header = (
        f'TITLE "{title}"\n'
        "# One-step Sony PP7 (S-Log2/S-Gamut) -> graded Rec.709 for 8-bit JPEG\n"
        "# shooters. Technical convert (verified) composed with an original look.\n"
        "# Apply this SINGLE LUT to a PP7 JPEG. scripts/gen_pp7_looks.py\n"
        f"LUT_3D_SIZE {SIZE}\nDOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n"
    )
    path = os.path.join(out_dir, fname)
    with open(path, "w", newline="\n") as f:
        f.write(header); f.write("\n".join(lines)); f.write("\n")
    # anchor: PP7 18% gray signal 0.323 should still land near Rec.709 mid ~0.41
    g = look(pp7_to_rec709(np.array([[0.323, 0.323, 0.323]])))[0]
    print(f"  {title:26s} 18%gray->({g[0]:.3f},{g[1]:.3f},{g[2]:.3f})  wrote {fname}")

if __name__ == "__main__":
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out_dir, exist_ok=True)
    for f, t, fn in LOOKS:
        emit(f, t, fn, out_dir)
