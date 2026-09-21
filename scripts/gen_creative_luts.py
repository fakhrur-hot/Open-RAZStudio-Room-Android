#!/usr/bin/env python3
"""
Generate ORIGINAL creative-look .cube LUTs from scratch (no third-party LUT IP).

These operate on already-display-referred Rec.709/sRGB input [0,1] (identity
domain) and apply original tone/colour math, so they layer AFTER a technical
S-Log->Rec.709 conversion or on ordinary photos. Category = "RAZ Looks".

All maths here is first-principles (lift/gamma/gain, luma-weighted split-tone,
sigmoid contrast, saturation) — authored, not copied.
"""
import os
import sys
import numpy as np

SIZE = 33
LUMA = np.array([0.2126, 0.7152, 0.0722])

def luma(rgb):
    return rgb @ LUMA

def saturation(rgb, s):
    g = luma(rgb)[..., None]
    return g + (rgb - g) * s

def contrast(rgb, k, pivot=0.435):
    # Smooth sigmoid-ish contrast about a pivot (k>1 adds contrast).
    return np.clip((rgb - pivot) * k + pivot, 0.0, 1.0)

def split_tone(rgb, shadow, highlight, amount):
    y = luma(rgb)[..., None]
    sh = np.array(shadow) / 255.0
    hi = np.array(highlight) / 255.0
    return rgb + amount * ((1.0 - y) * (sh - 0.5) + y * (hi - 0.5))

def lift_gamma_gain(rgb, lift, gamma, gain):
    x = np.clip(rgb, 0.0, 1.0)
    x = x + np.array(lift) * (1.0 - x)          # lift raises blacks
    x = np.power(np.clip(x, 1e-6, 1.0), 1.0 / np.array(gamma))
    return np.clip(x * np.array(gain), 0.0, 1.0)

# ── Looks (each: rgb[...,3] in [0,1] -> rgb out) ─────────────────────────────
def look_teal_orange(rgb):
    x = contrast(rgb, 1.12)
    x = split_tone(x, shadow=(60, 120, 140), highlight=(255, 170, 90), amount=0.22)
    return np.clip(saturation(x, 1.10), 0, 1)

def look_warm_film(rgb):
    x = lift_gamma_gain(rgb, lift=(0.02, 0.015, 0.0), gamma=(1.0, 1.0, 1.05), gain=(1.03, 1.0, 0.97))
    x = split_tone(x, shadow=(120, 110, 90), highlight=(255, 235, 200), amount=0.16)
    x = contrast(x, 1.06)
    return np.clip(saturation(x, 0.94), 0, 1)

def look_cool_cinematic(rgb):
    x = contrast(rgb, 1.16)
    x = split_tone(x, shadow=(70, 90, 130), highlight=(210, 220, 235), amount=0.18)
    return np.clip(saturation(x, 0.98), 0, 1)

def look_bleach_bypass(rgb):
    y = luma(rgb)[..., None]
    desat = saturation(rgb, 0.35)
    overlay = np.where(y < 0.5, 2 * desat * y, 1 - 2 * (1 - desat) * (1 - y))  # luma overlay
    x = 0.5 * desat + 0.5 * overlay
    return np.clip(contrast(x, 1.14), 0, 1)

def look_vintage_fade(rgb):
    x = lift_gamma_gain(rgb, lift=(0.06, 0.05, 0.04), gamma=(1.0, 1.0, 1.0), gain=(1.0, 0.99, 0.95))
    x = split_tone(x, shadow=(90, 100, 80), highlight=(240, 225, 205), amount=0.14)
    x = contrast(x, 0.94)  # <1 = softer, faded
    return np.clip(saturation(x, 0.85), 0, 1)

LOOKS = [
    ("RAZ_Teal_and_Orange.cube",  "RAZ Teal and Orange",  look_teal_orange),
    ("RAZ_Warm_Film.cube",        "RAZ Warm Film",        look_warm_film),
    ("RAZ_Cool_Cinematic.cube",   "RAZ Cool Cinematic",   look_cool_cinematic),
    ("RAZ_Bleach_Bypass.cube",    "RAZ Bleach Bypass",    look_bleach_bypass),
    ("RAZ_Vintage_Fade.cube",     "RAZ Vintage Fade",     look_vintage_fade),
]

def emit(fname, title, fn, out_dir):
    grid = np.linspace(0.0, 1.0, SIZE)
    lines = []
    for b in grid:
        for g in grid:
            for r in grid:
                o = fn(np.array([r, g, b]))
                lines.append("%.6f %.6f %.6f" % (o[0], o[1], o[2]))
    header = (
        f'TITLE "{title}"\n'
        "# Original RAZStudio creative look (first-principles tone/colour math,\n"
        "# no third-party LUT IP). Input = Rec.709/sRGB. scripts/gen_creative_luts.py\n"
        f"LUT_3D_SIZE {SIZE}\nDOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n"
    )
    path = os.path.join(out_dir, fname)
    with open(path, "w", newline="\n") as f:
        f.write(header); f.write("\n".join(lines)); f.write("\n")
    # sanity: identity gray stays neutral-ish, endpoints in range
    mid = fn(np.array([0.5, 0.5, 0.5]))
    print(f"  {title:22s} mid(0.5)->({mid[0]:.3f},{mid[1]:.3f},{mid[2]:.3f})  wrote {os.path.basename(path)}")

if __name__ == "__main__":
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out_dir, exist_ok=True)
    for fname, title, fn in LOOKS:
        emit(fname, title, fn, out_dir)
