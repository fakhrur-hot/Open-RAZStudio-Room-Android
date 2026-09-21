#!/usr/bin/env python3
"""Convert Adobe .cube ASCII LUTs to binary .smcube (smol-cube SML1 / ALut / f16).

Layout matches lib/raw-native/.../lut3d.cpp parseSmcube:
  magic "SML1"
  chunk { "ALut", u64 size LE, payload }
  payload = 7×u32 LE header (channels=3, dim=3, dtype=1 f16, filter=0,
             size_x, size_y, size_z) + row-major f16 RGB (R fastest).

Usage:
  python scripts/cube_to_smcube.py <dir> [--replace]
  python scripts/cube_to_smcube.py <dir> --out <generated/luts>   # AGP build task
"""
from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path


def parse_cube(path: Path) -> tuple[int, list[float]]:
    size = None
    rgb: list[float] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        if s.upper().startswith("TITLE"):
            continue
        if s.upper().startswith("DOMAIN_"):
            continue
        if s.upper().startswith("LUT_3D_SIZE"):
            size = int(s.split()[-1])
            continue
        if s.upper().startswith("LUT_1D_SIZE"):
            raise ValueError(f"1D LUT not supported: {path}")
        parts = s.split()
        if len(parts) >= 3:
            try:
                rgb.extend(float(parts[i]) for i in range(3))
            except ValueError:
                continue
    if size is None:
        raise ValueError(f"no LUT_3D_SIZE in {path}")
    expect = size * size * size * 3
    if len(rgb) != expect:
        raise ValueError(f"{path}: got {len(rgb)} floats, want {expect} for {size}^3")
    return size, rgb


def float_to_f16_bits(x: float) -> int:
    """IEEE-754 binary16 round-to-nearest-even (enough for LUT lattices)."""
    x = max(0.0, min(1.0, float(x)))  # LUT domain is [0,1]
    import struct as st

    f32 = st.unpack(">I", st.pack(">f", x))[0]
    sign = (f32 >> 16) & 0x8000
    exp = (f32 >> 23) & 0xFF
    mant = f32 & 0x7FFFFF
    if exp == 255:
        return sign | 0x7C00 | (mant != 0)
    if exp > 142:  # overflow → max finite
        return sign | 0x7BFF
    if exp < 113:  # underflow → 0 (subnormals unused for LUT)
        return sign
    new_exp = exp - 127 + 15
    new_mant = mant >> 13
    round_bit = (mant >> 12) & 1
    sticky = mant & 0xFFF
    bits = sign | (new_exp << 10) | new_mant
    if round_bit and (sticky or (new_mant & 1)):
        bits += 1
    return bits & 0xFFFF


def write_smcube(path: Path, size: int, rgb: list[float]) -> None:
    f16 = bytearray()
    for v in rgb:
        f16 += struct.pack("<H", float_to_f16_bits(v))
    header = struct.pack(
        "<7I",
        3,  # channels
        3,  # dimension
        1,  # dtype f16
        0,  # filter none
        size,
        size,
        size,
    )
    payload = header + f16
    chunk = b"ALut" + struct.pack("<Q", len(payload)) + payload
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"SML1" + chunk)


def convert_file(src: Path, dst: Path) -> None:
    size, rgb = parse_cube(src)
    write_smcube(dst, size, rgb)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("dir", type=Path, help="Directory of .cube files (recursive)")
    ap.add_argument("--replace", action="store_true", help="Delete .cube after writing .smcube")
    ap.add_argument(
        "--out",
        type=Path,
        default=None,
        help="Write .smcube mirror tree here (AGP generated assets). "
             "Existing .smcube under dir are copied through.",
    )
    args = ap.parse_args()
    root = args.dir
    cubes = list(root.rglob("*.cube"))
    smcubes_src = list(root.rglob("*.smcube")) if args.out else []
    if not cubes and not smcubes_src:
        print(f"no .cube/.smcube under {root}", file=sys.stderr)
        return 1
    n = 0
    failed = 0
    for c in cubes:
        if args.out is not None:
            rel = c.relative_to(root)
            out = args.out / rel.with_suffix(".smcube")
        else:
            out = c.with_suffix(".smcube")
        try:
            convert_file(c, out)
            n += 1
            if args.replace and args.out is None:
                c.unlink()
        except Exception as e:
            print(f"FAIL {c}: {e}", file=sys.stderr)
            failed += 1
    # Pass through pre-baked .smcube assets unchanged.
    if args.out is not None:
        import shutil
        for s in smcubes_src:
            rel = s.relative_to(root)
            dst = args.out / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            if not dst.exists() or dst.stat().st_mtime < s.stat().st_mtime:
                shutil.copy2(s, dst)
                n += 1
    print(f"converted {n} -> .smcube (failed={failed})")
    return 0 if failed == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
