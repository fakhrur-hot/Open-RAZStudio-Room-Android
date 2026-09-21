"""
Generate Android app launcher icons from logo_source.

Icon design notes (post-squircle review):
- The launcher applies its own squircle/circle mask to adaptive icons. Adaptive
  foregrounds are 108dp viewports; the launcher crops away the outer 25-30%
  for the mask, so content must sit in the inner ~66% safe zone.
- The full source logo (black square + "r" mark + "RAZSTUDIO" wordmark) is too
  busy at typical launcher sizes — the wordmark turns into unreadable mush and
  the inner safe-zone shrink leaves visible empty padding inside the squircle.
- Fix: foreground uses ONLY the "r" mark + dot (cropped from the upper portion
  of the source), enlarged to fill the safe zone. The black background plate
  comes from `<background>` (#000000), so the foreground stays transparent.
- Legacy square + round mipmaps still show the full logo (pre-API-26 launchers
  don't apply the mask, so the wordmark stays visible there).

Densities: mdpi 48, hdpi 72, xhdpi 96, xxhdpi 144, xxxhdpi 192.
Adaptive foreground/monochrome PNGs are 108dp at each density: mdpi 108,
hdpi 162, xhdpi 216, xxhdpi 324, xxxhdpi 432.
"""

from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(r"C:\Users\Public\Kiro\StudioRoom\Fixed16bit")
SRC  = ROOT / "image.png"

RES_ROOTS = [
    ROOT / "core" / "resources" / "src" / "main"  / "res",
    ROOT / "core" / "resources" / "src" / "debug" / "res",
]
PLAYSTORE_PATHS = [
    ROOT / "core" / "resources" / "src" / "main"  / "ic_launcher-playstore.png",
    ROOT / "core" / "resources" / "src" / "debug" / "ic_launcher-playstore.png",
]

DENSITIES = {
    "mdpi":    48,
    "hdpi":    72,
    "xhdpi":   96,
    "xxhdpi": 144,
    "xxxhdpi":192,
}

# ── Load source + crop ───────────────────────────────────────────────────────
src = Image.open(SRC).convert("RGBA")

# Crop to square first (centered)
w, h = src.size
side = min(w, h)
left = (w - side) // 2
top  = (h - side) // 2
master_full_bg = src.crop((left, top, left + side, top + side))
W, H = master_full_bg.size

# Inset by 5% to skip the AA noise at the source's black-square rim. We
# diagnosed (scripts/diag_crop.py) that after this inset the content gaps are:
#   y∈[0..75]    : padding / rim
#   y∈[125..127] : tiny gap between dot and "r"
#   y∈[475..525] : gap between "r" mark and "RAZSTUDIO" wordmark
#   y∈[608..631] : gap between wordmark and underline
# So the dot + "r" glyph lives in y∈[0..474] of the inset image. We crop to
# its tight bbox below.
INSET = int(side * 0.05)
inner = master_full_bg.crop((INSET, INSET, side - INSET, side - INSET))

def non_black_bbox(im: Image.Image, threshold: int = 80):
    """Return (left, top, right, bottom) of pixels brighter than `threshold`.
    The high threshold (80, not 30) skips JPEG/AA gray noise so we get the
    real glyph bbox, not the rim of the source square."""
    px = im.load()
    iw, ih = im.size
    minx, miny, maxx, maxy = iw, ih, -1, -1
    for y in range(ih):
        for x in range(iw):
            r, g, b, a = px[x, y]
            if (r > threshold or g > threshold or b > threshold) and a > 0:
                if x < minx: minx = x
                if y < miny: miny = y
                if x > maxx: maxx = x
                if y > maxy: maxy = y
    if maxx < 0:
        return None
    return (minx, miny, maxx + 1, maxy + 1)

# Crop the "r + dot" band: top of inner image down to just above the
# wordmark gap (y=475 of the 730-tall inner).
mark_band_h = int(inner.size[1] * (475 / 730))
mark_band = inner.crop((0, 0, inner.size[0], mark_band_h))

# Tight-bbox the r + dot and pad to a square so it places centered.
mark_bbox = non_black_bbox(mark_band)
if mark_bbox:
    mark_tight = mark_band.crop(mark_bbox)
else:
    mark_tight = mark_band

mw, mh = mark_tight.size
mside = max(mw, mh)
# White-bg square for legacy icons (white because that's our new launcher bg)
mark_on_white = Image.new("RGBA", (mside, mside), (255, 255, 255, 255))
ox = (mside - mw) // 2
oy = (mside - mh) // 2
# When pasting onto white we need to recolor non-black pixels to black.
mt_px = mark_tight.load()
for ty in range(mh):
    for tx in range(mw):
        r, g, b, a = mt_px[tx, ty]
        if r > 80 or g > 80 or b > 80:
            mark_on_white.putpixel((ox + tx, oy + ty), (0, 0, 0, 255))

# Transparent-bg version with BLACK mark — for adaptive foreground on white.
mark_content = Image.new("RGBA", (mside, mside), (0, 0, 0, 0))
for ty in range(mh):
    for tx in range(mw):
        r, g, b, a = mt_px[tx, ty]
        if r > 80 or g > 80 or b > 80:
            mark_content.putpixel((ox + tx, oy + ty), (0, 0, 0, 255))

# ── Helpers ──────────────────────────────────────────────────────────────────

def write(im: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, "PNG", optimize=True)
    print(f"  wrote {path.relative_to(ROOT)}  {im.size}")

def make_square_legacy(size: int) -> Image.Image:
    """Legacy square icon (pre-API-26 launchers): white bg + black "r" mark
    centered at 55% (a bit larger than the adaptive 50% because pre-26
    launchers don't apply a squircle mask, so the visible bounds are the full
    square — extra margin would look weirdly small)."""
    out = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    inner = int(round(size * 0.55))
    scaled = mark_content.resize((inner, inner), Image.LANCZOS)
    off = (size - inner) // 2
    out.paste(scaled, (off, off), scaled)
    return out

def make_round_legacy(size: int) -> Image.Image:
    """Legacy round icon: square then circle-masked, with white kept inside."""
    square = make_square_legacy(size)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size, size), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(square, (0, 0), mask)
    return out

def make_adaptive_foreground(size: int) -> Image.Image:
    """Adaptive foreground: transparent 108dp canvas with the "r" mark + dot
    centered at ~50% size for comfortable margin inside the launcher squircle.
    `mark_content` is already black-on-transparent."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(round(size * 0.50))
    scaled = mark_content.resize((inner, inner), Image.LANCZOS)
    off = (size - inner) // 2
    canvas.paste(scaled, (off, off), scaled)
    return canvas

def make_monochrome(size: int) -> Image.Image:
    """Monochrome themed-icon: white silhouette of the mark for themed icons
    (Android tints monochrome layers from its theme regardless of source color)."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(round(size * 0.50))
    scaled = mark_content.resize((inner, inner), Image.LANCZOS)
    silhouette = Image.new("RGBA", scaled.size, (0, 0, 0, 0))
    spx = silhouette.load()
    src_px = scaled.load()
    for y in range(scaled.size[1]):
        for x in range(scaled.size[0]):
            a = src_px[x, y][3]
            if a > 0:
                spx[x, y] = (255, 255, 255, a)
    off = (size - inner) // 2
    canvas.paste(silhouette, (off, off), silhouette)
    return canvas

# ── Emit per density ─────────────────────────────────────────────────────────

print("Generating launcher icons...")
for res_root in RES_ROOTS:
    print(f"\n[{res_root.relative_to(ROOT)}]")
    for density, sz in DENSITIES.items():
        mipdir = res_root / f"mipmap-{density}"
        for old in mipdir.glob("ic_launcher.webp"):
            old.unlink()
        for old in mipdir.glob("ic_launcher_round.webp"):
            old.unlink()

        write(make_square_legacy(sz), mipdir / "ic_launcher.png")
        write(make_round_legacy(sz),  mipdir / "ic_launcher_round.png")

        adaptive_size = sz * 108 // 48
        drawdir = res_root / f"drawable-{density}"
        write(make_adaptive_foreground(adaptive_size),
              drawdir / "ic_launcher_foreground.png")
        write(make_monochrome(adaptive_size),
              drawdir / "ic_launcher_monochrome.png")

for ps in PLAYSTORE_PATHS:
    write(make_square_legacy(512), ps)

print("\nDone.")
