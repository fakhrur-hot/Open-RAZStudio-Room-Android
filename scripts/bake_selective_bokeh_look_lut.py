#!/usr/bin/env python3
"""Bake selective-bokeh look (filmicLuma@0.65 + oklabHlChroma@0.70) to a 33^3 .cube.

Mirrors Fixed16bit shader_sources applyFilmicLuma + applyOklabHlChroma.
Spatial depth/CoC/bokeh are NOT baked.
"""
from __future__ import annotations

from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
WINBATCH = ROOT.parent / "WinBatch"


def filmic_luma_curve(x: np.ndarray) -> np.ndarray:
    v = np.clip(x, 0.0, 1.0)
    shadow = v + 0.025 * (1.0 - v)
    s_curve = shadow * shadow * (3.0 - 2.0 * shadow)
    return np.clip(s_curve / (s_curve + 0.18), 0.0, 1.0)


def apply_filmic_luma(rgb: np.ndarray, strength: float = 0.65) -> np.ndarray:
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    mapped = filmic_luma_curve(y)
    scale = mapped / np.maximum(y, 1e-4)
    out = rgb * scale[..., None]
    return rgb + (out - rgb) * strength


def srgb_to_lin(c: np.ndarray) -> np.ndarray:
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def lin_to_srgb(c: np.ndarray) -> np.ndarray:
    c = np.maximum(c, 0.0)
    return np.where(c <= 0.0031308, 12.92 * c, 1.055 * (c ** (1.0 / 2.4)) - 0.055)


def linear_to_oklab(c: np.ndarray) -> np.ndarray:
    r, g, b = c[..., 0], c[..., 1], c[..., 2]
    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    l_ = np.cbrt(np.maximum(l, 0))
    m_ = np.cbrt(np.maximum(m, 0))
    s_ = np.cbrt(np.maximum(s, 0))
    L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_
    a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_
    bb = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_
    return np.stack([L, a, bb], axis=-1)


def oklab_to_linear(lab: np.ndarray) -> np.ndarray:
    L, a, bb = lab[..., 0], lab[..., 1], lab[..., 2]
    l_ = L + 0.3963377774 * a + 0.2158037573 * bb
    m_ = L - 0.1055613458 * a - 0.0638541728 * bb
    s_ = L - 0.0894789779 * a - 1.2914855480 * bb
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
    r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
    g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
    b = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
    return np.stack([r, g, b], axis=-1)


def smoothstep(e0: float, e1: float, x: np.ndarray) -> np.ndarray:
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def oklab_chroma_mul(L: np.ndarray) -> np.ndarray:
    mid = 1.0 + 0.08 * smoothstep(0.15, 0.40, L) * (1.0 - smoothstep(0.45, 0.60, L))
    hl = smoothstep(0.55, 1.0, L)
    hl_mul = 1.0 + (0.40 - 1.0) * hl
    return mid + (hl_mul - mid) * hl


def apply_oklab_hl(rgb: np.ndarray, strength: float = 0.70) -> np.ndarray:
    lin = srgb_to_lin(np.clip(rgb, 0.0, 1.0))
    lab = linear_to_oklab(lin)
    mul = oklab_chroma_mul(lab[..., 0])
    lab2 = lab.copy()
    lab2[..., 1] *= mul
    lab2[..., 2] *= mul
    out = np.clip(lin_to_srgb(oklab_to_linear(lab2)), 0.0, 1.0)
    return rgb + (out - rgb) * strength


def transform(rgb: np.ndarray) -> np.ndarray:
    return apply_oklab_hl(apply_filmic_luma(rgb, 0.65), 0.70)


def write_cube(path: Path, title: str, data: np.ndarray, n: int) -> None:
    lines = [
        f'TITLE "{title}"',
        "# Baked from Fixed16bit selective-bokeh look (filmicLuma@0.65 + oklabHlChroma@0.70).",
        "# Spatial depth/CoC/bokeh NOT included — apply those live. Domain: display-referred sRGB.",
        "# Mirror of shader_sources applyFilmicLuma + applyOklabHlChroma (2026-09-15).",
        f"LUT_3D_SIZE {n}",
        "DOMAIN_MIN 0.0 0.0 0.0",
        "DOMAIN_MAX 1.0 1.0 1.0",
    ]
    for ri in range(n):
        for gi in range(n):
            for bi in range(n):
                r, g, b = data[ri, gi, bi]
                lines.append(f"{r:.6f} {g:.6f} {b:.6f}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {path} ({len(lines)} lines)")


def main() -> None:
    n = 33
    xs = np.linspace(0.0, 1.0, n)
    grid = np.stack(np.meshgrid(xs, xs, xs, indexing="ij"), axis=-1)
    out = transform(grid.reshape(-1, 3).astype(np.float64)).reshape(n, n, n, 3)
    print("black", transform(np.array([[0.0, 0.0, 0.0]]))[0])
    print("mid  ", transform(np.array([[0.5, 0.5, 0.5]]))[0])
    print("white", transform(np.array([[1.0, 1.0, 1.0]]))[0])

    title = "Selective Bokeh Look"
    write_cube(WINBATCH / "presets" / "selective_bokeh_look.cube", title, out, n)
    write_cube(ROOT / "scripts" / "out_selective_bokeh" / "selective_bokeh_look.cube", title, out, n)

    note = WINBATCH / "SELECTIVE_BOKEH_LOOK.md"
    note.write_text(
        """# Selective Bokeh Look — WinBatch parity

## What this is
Display-referred sRGB 33³ cube baking the **colour** half of the Fixed16bit
depth-aware selective bokeh look:

| Slot | Name | Auto (depth+bokeh) | Baked strength |
|------|------|--------------------|----------------|
| 451 | filmicLuma | 0.65 | 0.65 |
| 452 | oklabHlChroma | 0.70 | 0.70 |

File: `presets/selective_bokeh_look.cube`

## What is NOT in the cube
- Depth / BiRefNet masks
- Depth→CoC gate
- Subject protect / Gaussian bokeh / bloom / sharpen

Those stay spatial. WinBatch should consume ShaderParams FLOAT_COUNT=453
when the desktop twin grows a depth path; until then load this cube for
the grade-only half.

## Re-bake
```
python Fixed16bit/scripts/bake_selective_bokeh_look_lut.py
```
""",
        encoding="utf-8",
    )
    print("note", note)


if __name__ == "__main__":
    main()
