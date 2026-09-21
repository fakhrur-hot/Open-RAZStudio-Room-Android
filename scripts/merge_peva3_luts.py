#!/usr/bin/env python3
"""Merge peva3-converted cubes into feature/photo-editor assets/luts.

Steps:
  1. Normalize & dedupe against existing assets (and within peva3 dump).
  2. Split _GenreStaging into Portrait / Landscape / Nature & Wildlife /
     Lifestyle & Commercial by filename keywords.
  3. Convert .cube → .smcube (via cube_to_smcube helpers).
  4. Optionally consolidate Negative Old / Negative  New → Negative Color.

Run AFTER BatchPeva3XmpConvertTest:
  python scripts/merge_peva3_luts.py
  python scripts/merge_peva3_luts.py --consolidate-negatives
"""
from __future__ import annotations

import argparse
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CUBES = ROOT / "_tmp_peva3_cubes"
ASSETS = ROOT / "feature" / "photo-editor" / "src" / "main" / "assets" / "luts"

# Ensure we can import cube_to_smcube from the same scripts/ folder.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from cube_to_smcube import convert_file  # noqa: E402


def norm_key(name: str) -> str:
    s = name.lower()
    s = re.sub(r"\.(cube|smcube)$", "", s)
    s = s.replace("&", "and")
    s = re.sub(r"[^a-z0-9]+", "", s)
    # Collapse common film stock aliases so peva3 "Kodak Portra 400" matches
    # existing "Portra_400" / "kodak_portra_400".
    for prefix in ("kodak", "fuji", "fujifilm", "fujicolor", "ilford", "agfa", "cinestill"):
        if s.startswith(prefix):
            s = s[len(prefix) :]
            break
    return s


GENRE_RULES: list[tuple[str, list[str]]] = [
    ("Portrait", ["portrait", "wedding", "newborn", "beauty", "fashion", "skin",
                  "maternity", "baby"]),
    ("Nature & Wildlife", ["wildlife", "bird", "macro", "forest", "nature"]),
    ("Landscape", ["landscape", "astro", "aerial", "underwater", "seascape", "mountain",
                   "milky", "startrail", "nightsky"]),
    ("Correction", ["recovery", "enhance", "haze", "detailpop", "clarity"]),
    ("Black & White", ["bandw", "b&w", "monochrome", "zonefocus", "zone focus"]),
    ("Lifestyle & Commercial", ["food", "product", "realestate", "real-estate", "street",
                                "concert", "sports", "architecture", "interior"]),
]


def genre_dest(stem: str) -> str:
    low = stem.lower().replace(" ", "").replace("_", "").replace("-", "")
    for cat, keys in GENRE_RULES:
        if any(k.replace(" ", "").replace("-", "") in low for k in keys):
            return cat
    return "Lifestyle & Commercial"


def existing_keys(assets: Path) -> dict[str, Path]:
    out: dict[str, Path] = {}
    if not assets.is_dir():
        return out
    for p in assets.rglob("*"):
        if p.suffix.lower() in (".cube", ".smcube"):
            out[norm_key(p.name)] = p
    return out


def consolidate_negatives(assets: Path, dry: bool) -> int:
    dest = assets / "Negative Color"
    dest.mkdir(parents=True, exist_ok=True)
    moved = 0
    keys = {norm_key(p.name): p for p in dest.glob("*") if p.suffix.lower() in (".cube", ".smcube")}
    for folder in ("Negative Old", "Negative  New", "Negative New"):
        src = assets / folder
        if not src.is_dir():
            continue
        for f in list(src.iterdir()):
            if f.suffix.lower() not in (".cube", ".smcube"):
                continue
            k = norm_key(f.name)
            if k in keys:
                print(f"  drop dup {folder}/{f.name} (= {keys[k].name})")
                if not dry:
                    f.unlink()
                continue
            target = dest / f.name
            print(f"  move {folder}/{f.name} -> Negative Color/")
            if not dry:
                if target.exists():
                    f.unlink()
                else:
                    shutil.move(str(f), str(target))
                    keys[k] = target
            moved += 1
        if not dry:
            # Remove folder if empty
            if src.is_dir() and not any(src.iterdir()):
                src.rmdir()
                print(f"  removed empty {folder}/")
    return moved


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--consolidate-negatives", action="store_true",
                    help="Merge Negative Old / Negative  New into Negative Color")
    ap.add_argument("--keep-ascii", action="store_true",
                    help="Copy .cube instead of converting to .smcube")
    args = ap.parse_args()

    if args.consolidate_negatives:
        print("Consolidating Negative* folders…")
        n = consolidate_negatives(ASSETS, args.dry_run)
        print(f"consolidated moves={n}")

    if not CUBES.is_dir():
        print(f"missing {CUBES} — run BatchPeva3XmpConvertTest first", file=sys.stderr)
        return 1

    keys = existing_keys(ASSETS)
    added = 0
    skipped = 0
    failed = 0

    for cube in sorted(CUBES.rglob("*.cube")):
        # Staging category from convert test path: _tmp_peva3_cubes/<Cat>/file.cube
        rel_cat = cube.parent.name
        if rel_cat == "_GenreStaging":
            dest_cat = genre_dest(cube.stem)
        else:
            dest_cat = rel_cat

        k = norm_key(cube.stem)
        if k in keys:
            print(f"SKIP dup {dest_cat}/{cube.stem} ↔ {keys[k].relative_to(ASSETS)}")
            skipped += 1
            continue

        dest_dir = ASSETS / dest_cat
        if args.keep_ascii:
            dest = dest_dir / (cube.stem + ".cube")
        else:
            dest = dest_dir / (cube.stem + ".smcube")

        print(f"ADD {dest.relative_to(ASSETS)}")
        if not args.dry_run:
            dest_dir.mkdir(parents=True, exist_ok=True)
            try:
                if args.keep_ascii:
                    shutil.copy2(cube, dest)
                else:
                    convert_file(cube, dest)
                keys[k] = dest
                added += 1
            except Exception as e:
                print(f"FAIL {cube}: {e}", file=sys.stderr)
                failed += 1
        else:
            added += 1

    print(f"DONE added={added} skipped_dup={skipped} failed={failed}")
    return 0 if failed == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
