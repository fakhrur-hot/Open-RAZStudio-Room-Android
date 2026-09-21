"""Diagnostic: find the actual mark vs wordmark vs underline regions in the source."""
from PIL import Image
from pathlib import Path

ROOT = Path(r"C:\Users\Public\Kiro\StudioRoom\Fixed16bit")
src = Image.open(ROOT / "image.png").convert("RGBA")
w, h = src.size
side = min(w, h)
left = (w - side) // 2
top = (h - side) // 2
sq = src.crop((left, top, left + side, top + side))
W, H = sq.size
print(f"Square crop: {W}x{H}")

# Scan each row for any non-black pixel — gives a vertical profile of content.
px = sq.load()
row_has_content = []
for y in range(H):
    found = False
    for x in range(W):
        r, g, b, a = px[x, y]
        if r > 30 or g > 30 or b > 30:
            found = True
            break
    row_has_content.append(found)

# Find content bands (runs of True separated by black gaps)
bands = []
in_band = False
for y, c in enumerate(row_has_content):
    if c and not in_band:
        in_band = True
        start = y
    elif not c and in_band:
        in_band = False
        bands.append((start, y - 1))
if in_band:
    bands.append((start, H - 1))

print(f"Content bands (y-ranges): {bands}")
for i, (s, e) in enumerate(bands):
    print(f"  band {i}: y={s}..{e}  height={e-s+1}  ratio={s/H:.3f}..{(e+1)/H:.3f}")
