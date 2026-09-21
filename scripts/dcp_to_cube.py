#!/usr/bin/env python3
"""Bake an Adobe .dcp camera profile's creative half into a 3D .cube LUT.

A .dcp is a TIFF-structured camera profile. Two of its tags carry the *look*
(the rest — ColorMatrix/ForwardMatrix/HueSatMap — is sensor calibration, which
is already handled by our own RAW pipeline and must NOT be baked in):

  ProfileLookTable (50982) + Dims (50981)
      A 3D table indexed by HSV, storing (hue shift °, saturation scale,
      value scale) per cell. Ordering per DNG 1.4: value outer, hue middle,
      saturation inner. This is the film simulation's colour character.
  ProfileToneCurve (50940)
      128 (x, y) pairs — the filmic tone response (toe, shoulder, where the
      highlights roll off). This is what makes Eterna flat and Velvia punchy;
      without it every film sim collapses to nearly the same look.

Usage:
  python scripts/dcp_to_cube.py <file-or-dir> --out <dir> [--size 33]
                                [--no-tone] [--strength 1.0]

  --no-tone    bake ONLY the look table (colour twists, no tone curve). Use
               when the LUT will sit on already-tone-mapped pixels and the
               profile's own curve would double up.
  --strength   scale the whole look 0..1 (0.5 = half-strength film sim).

The LUT maps display-referred sRGB in → sRGB out: the table math runs in
linear light (ProfileLookTableEncoding 0 = linear, the default), so the input
is linearised first and re-encoded after.
"""
import argparse
import glob
import os
import struct
import sys

import numpy as np

TAG_TONE_CURVE = 50940
TAG_LOOK_DIMS = 50981
TAG_LOOK_DATA = 50982
TAG_LOOK_ENCODING = 51109
TAG_NAME = 50936
TYPE_SIZE = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8, 11: 4, 12: 8}


def read_dcp(path):
    d = open(path, "rb").read()
    if d[:2] not in (b"II", b"MM"):
        raise ValueError(f"{path}: not a TIFF-structured DCP")
    bo = "<" if d[:2] == b"II" else ">"
    off = struct.unpack(bo + "I", d[4:8])[0]
    count = struct.unpack(bo + "H", d[off:off + 2])[0]
    tags = {}
    for i in range(count):
        e = off + 2 + i * 12
        tag, typ, cnt = struct.unpack(bo + "HHI", d[e:e + 8])
        size = TYPE_SIZE.get(typ, 1) * cnt
        if size <= 4:
            payload = d[e + 8:e + 8 + size]
        else:
            ptr = struct.unpack(bo + "I", d[e + 8:e + 12])[0]
            payload = d[ptr:ptr + size]
        tags[tag] = (typ, cnt, payload, bo)
    return tags


def floats(tags, tag):
    if tag not in tags:
        return None
    typ, cnt, payload, bo = tags[tag]
    return np.frombuffer(payload[:4 * cnt], dtype=bo + "f4").astype(np.float64)


def longs(tags, tag):
    if tag not in tags:
        return None
    typ, cnt, payload, bo = tags[tag]
    return list(struct.unpack(bo + "I" * cnt, payload[:4 * cnt]))


def name_of(tags, fallback):
    if TAG_NAME not in tags:
        return fallback
    return tags[TAG_NAME][2].split(b"\0")[0].decode("latin1") or fallback


def srgb_to_linear(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(c):
    c = np.clip(c, 0.0, None)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)


def rgb_to_hsv(rgb):
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    mx = np.max(rgb, axis=-1)
    mn = np.min(rgb, axis=-1)
    d = mx - mn
    h = np.zeros_like(mx)
    nz = d > 1e-12
    idx = nz & (mx == r)
    h[idx] = ((g - b)[idx] / d[idx]) % 6.0
    idx = nz & (mx == g)
    h[idx] = ((b - r)[idx] / d[idx]) + 2.0
    idx = nz & (mx == b)
    h[idx] = ((r - g)[idx] / d[idx]) + 4.0
    h *= 60.0
    s = np.where(mx > 1e-12, d / np.maximum(mx, 1e-12), 0.0)
    return h, s, mx


def hsv_to_rgb(h, s, v):
    h = np.mod(h, 360.0) / 60.0
    i = np.floor(h).astype(np.int32)
    f = h - i
    p = v * (1 - s)
    q = v * (1 - s * f)
    t = v * (1 - s * (1 - f))
    i = i % 6
    out = np.zeros(h.shape + (3,), dtype=np.float64)
    for k, (rr, gg, bb) in enumerate([(v, t, p), (q, v, p), (p, v, t),
                                      (p, q, v), (t, p, v), (v, p, q)]):
        m = i == k
        out[m, 0] = rr[m] if np.ndim(rr) else rr
        out[m, 1] = gg[m] if np.ndim(gg) else gg
        out[m, 2] = bb[m] if np.ndim(bb) else bb
    return out


def apply_look_table(rgb, dims, table, strength):
    """Trilinear-sample the HSV look table and apply its deltas.

    DNG 1.4 ordering: value outermost, then hue, then saturation. Hue wraps;
    saturation and value clamp at the ends (a value above the last division
    keeps the last row's correction, which is what Adobe does)."""
    hd, sd, vd = dims
    tbl = table.reshape(vd, hd, sd, 3)
    h, s, v = rgb_to_hsv(rgb)

    hf = (h / 360.0) * hd
    h0 = np.floor(hf).astype(np.int32) % hd
    h1 = (h0 + 1) % hd
    ht = (hf - np.floor(hf))[..., None]

    sf = np.clip(s, 0, 1) * (sd - 1)
    s0 = np.clip(np.floor(sf).astype(np.int32), 0, sd - 1)
    s1 = np.clip(s0 + 1, 0, sd - 1)
    st = (sf - s0)[..., None]

    vf = np.clip(v, 0, 1) * (vd - 1)
    v0 = np.clip(np.floor(vf).astype(np.int32), 0, vd - 1)
    v1 = np.clip(v0 + 1, 0, vd - 1)
    vt = (vf - v0)[..., None]

    def cell(vi, hi, si):
        return tbl[vi, hi, si]

    c000, c001 = cell(v0, h0, s0), cell(v0, h0, s1)
    c010, c011 = cell(v0, h1, s0), cell(v0, h1, s1)
    c100, c101 = cell(v1, h0, s0), cell(v1, h0, s1)
    c110, c111 = cell(v1, h1, s0), cell(v1, h1, s1)
    c00 = c000 * (1 - st) + c001 * st
    c01 = c010 * (1 - st) + c011 * st
    c10 = c100 * (1 - st) + c101 * st
    c11 = c110 * (1 - st) + c111 * st
    c0 = c00 * (1 - ht) + c01 * ht
    c1 = c10 * (1 - ht) + c11 * ht
    delta = c0 * (1 - vt) + c1 * vt

    hue_shift = delta[..., 0] * strength
    sat_scale = 1.0 + (delta[..., 1] - 1.0) * strength
    val_scale = 1.0 + (delta[..., 2] - 1.0) * strength

    h2 = h + hue_shift
    s2 = np.clip(s * sat_scale, 0.0, 1.0)
    v2 = np.clip(v * val_scale, 0.0, None)
    return hsv_to_rgb(h2, s2, v2)


def curve_xy(curve):
    xs = curve[0::2]
    ys = curve[1::2]
    order = np.argsort(xs)
    return xs[order], ys[order]


def apply_tone_curve(rgb, curve, strength, reference=None):
    """ProfileToneCurve: 128 (x, y) pairs, monotonic, applied per channel.

    A profile's curve maps LINEAR scene values to a display rendering, so
    applying it to pixels that already went through a base rendering doubles the
    S-curve (measured: mean luma 124 -> 153 on a developed JPEG). When
    [reference] is given, the curve is applied RELATIVE to it —
    `out = profile(reference⁻¹(x))` — which cancels the shared base rendering
    and leaves only what makes THIS film simulation different from the
    reference. Pass Provia (Fuji's standard stock) to get exactly the tonal
    character a photographer means by "Velvia vs Provia"."""
    xs, ys = curve_xy(curve)
    x = np.clip(rgb, 0.0, 1.0)
    if reference is not None:
        rxs, rys = curve_xy(reference)
        # reference⁻¹: the reference curve is monotonic, so swap the axes.
        x = np.interp(x, rys, rxs)
    out = np.interp(x, xs, ys)
    return rgb + (out - rgb) * strength


def bake(path, out_dir, size, use_tone, strength, reference=None, tone_strength=None):
    tags = read_dcp(path)
    base = os.path.splitext(os.path.basename(path))[0]
    prof = name_of(tags, base)

    dims = longs(tags, TAG_LOOK_DIMS)
    table = floats(tags, TAG_LOOK_DATA)
    curve = floats(tags, TAG_TONE_CURVE)
    enc = longs(tags, TAG_LOOK_ENCODING)
    enc = enc[0] if enc else 0

    if dims is None or table is None:
        print(f"  SKIP {base}: no ProfileLookTable (calibration-only profile)")
        return None
    expected = dims[0] * dims[1] * dims[2] * 3
    if len(table) != expected:
        print(f"  SKIP {base}: look table is {len(table)} floats, expected {expected}")
        return None

    # Identity grid, red fastest (the .cube convention).
    step = np.linspace(0.0, 1.0, size)
    b, g, r = np.meshgrid(step, step, step, indexing="ij")
    grid = np.stack([r, g, b], axis=-1).reshape(-1, 3)

    work = srgb_to_linear(grid) if enc == 0 else grid.copy()
    work = apply_look_table(work, dims, table, strength)
    if use_tone and curve is not None and len(curve) >= 4:
        # Tone scales SEPARATELY from colour. Extrapolating a film curve past
        # 1.0 steepens the toe fastest: at 1.5 the Velvia curve mapped
        # everything below input 0.062 to pure black — lost shadow detail, not
        # a stronger look. Colour can be pushed well past 1.0 safely; tone
        # should usually stay at 1.0.
        work = apply_tone_curve(work, curve, tone_strength, reference)
    out = linear_to_srgb(work) if enc == 0 else work
    out = np.clip(out, 0.0, 1.0)

    os.makedirs(out_dir, exist_ok=True)
    suffix = "" if use_tone else "_look"
    dst = os.path.join(out_dir, f"{prof}{suffix}.cube")
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write(f'TITLE "{prof}"\n')
        f.write(f"# Baked from {os.path.basename(path)} by scripts/dcp_to_cube.py\n")
        f.write(f"# ProfileLookTable {dims[0]}x{dims[1]}x{dims[2]}"
                f"{' + ProfileToneCurve' if use_tone and curve is not None else ''}"
                f", look strength {strength}, tone strength {tone_strength}\n")
        f.write(f"LUT_3D_SIZE {size}\n")
        f.write("DOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n")
        for px in out:
            f.write(f"{px[0]:.6f} {px[1]:.6f} {px[2]:.6f}\n")
    tone_n = 0 if curve is None else len(curve) // 2
    print(f"  {prof:22s} look {dims[0]}x{dims[1]}x{dims[2]}  tone {tone_n} pts"
          f"  enc={'linear' if enc == 0 else 'sRGB'} -> {os.path.basename(dst)}")
    return dst


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("target")
    ap.add_argument("--out", required=True)
    ap.add_argument("--size", type=int, default=33)
    ap.add_argument("--no-tone", action="store_true")
    ap.add_argument("--strength", type=float, default=1.0)
    ap.add_argument("--tone-strength", type=float, default=None,
                    help="scale for the tone curve only (default: same as "
                         "--strength). Keep at 1.0 when pushing --strength past "
                         "1.0: an extrapolated film toe crushes shadows to black")
    ap.add_argument("--relative-to", default=None,
                    help="reference .dcp whose tone curve is divided out, so the "
                         "LUT carries only this profile's DIFFERENCE from it "
                         "(use the base film sim, e.g. Provia)")
    a = ap.parse_args()

    files = ([a.target] if os.path.isfile(a.target)
             else sorted(glob.glob(os.path.join(a.target, "*.dcp"))))
    if not files:
        print("no .dcp files found")
        return 1
    reference = None
    if a.relative_to:
        reference = floats(read_dcp(a.relative_to), TAG_TONE_CURVE)
        print(f"tone curves relative to {os.path.basename(a.relative_to)}")
    print(f"baking {len(files)} profile(s) at {a.size}^3"
          f"{' (look only)' if a.no_tone else ' (look + tone curve)'}:")
    tone_strength = a.tone_strength if a.tone_strength is not None else a.strength
    if tone_strength != a.strength:
        print(f"look strength {a.strength}, tone strength {tone_strength}")
    for f in files:
        bake(f, a.out, a.size, not a.no_tone, a.strength, reference, tone_strength)
    return 0


if __name__ == "__main__":
    sys.exit(main())
