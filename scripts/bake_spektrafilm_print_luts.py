#!/usr/bin/env python3
"""Bake display-referred sRGB film×paper cubes from upstream spektrafilm.

License: derived cubes are CC BY-SA 4.0 (Andrea Volpato / spektrafilm).
No GPLv3 engine code is copied into StudioRoom — this script runs against a
local checkout under `_tmp_spektrafilm_upstream/` (or PYTHONPATH).

Grain / halation / diffusion are off in lut_mode (pointwise bake only).
StudioRoom display names never include "Spektrafilm".

Usage (from repo root, with stubs + upstream on PYTHONPATH):
  python scripts/bake_spektrafilm_print_luts.py --resolution 33
  python scripts/cube_to_smcube.py _tmp_spektra_bake/out --replace
"""
from __future__ import annotations

import argparse
import json
import shutil
import time
from pathlib import Path

# (film, print, studio_display_name, category, skip_reason_or_None)
# skip_reason set → listed as skipped, not baked.
LOOKS: list[tuple[str, str, str, str, str | None]] = [
    # Portrait — soft Endura / Crystal Archive print character
    ("kodak_portra_160", "kodak_portra_endura", "Portra 160 Soft Print", "Portrait", None),
    ("kodak_portra_400", "kodak_portra_endura", "Portra 400 Warm Print", "Portrait", None),
    ("kodak_portra_800", "kodak_portra_endura", "Portra 800 Dim Print", "Portrait", None),
    ("fujifilm_pro_400h", "fujifilm_crystal_archive_typeii", "Pro 400H Pastel Print", "Portrait", None),
    # Landscape / punchy papers
    ("kodak_ektar_100", "kodak_ultra_endura", "Ektar Ultra Print", "Landscape", None),
    ("kodak_ektar_100", "kodak_supra_endura", "Ektar Supra Print", "Landscape", None),
    ("fujifilm_xtra_400", "fujifilm_crystal_archive_typeii", "Superia Cool Print", "Landscape", None),
    # Cinema print film (negative → 2383/2393)
    ("kodak_vision3_250d", "kodak_2383", "Vision3 250D Cinema Print", "Cinematic", None),
    ("kodak_vision3_50d", "kodak_2393", "Vision3 50D Premier Print", "Cinematic", None),
    ("kodak_vision3_200t", "kodak_2383", "Vision3 200T Cinema Print", "Cinematic", None),
    ("kodak_vision3_500t", "kodak_2383", "Vision3 500T Cinema Print", "Cinematic", None),
    ("kodak_verita_200d", "kodak_2383", "Verita 200D Cinema Print", "Cinematic", None),
    # Consumer minilab
    ("kodak_gold_200", "kodak_ektacolor_edge", "Gold 200 Minilab Print", "Negative Color", None),
    ("kodak_ultramax_400", "kodak_ektacolor_edge", "UltraMax Minilab Print", "Negative Color", None),
    ("fujifilm_c200", "fujifilm_crystal_archive_typeii", "Fuji C200 Everyday Print", "Negative Color", None),
    ("kodak_portra_800_push1", "kodak_portra_endura", "Portra 800 Push1 Print", "Negative Color", None),
    ("kodak_portra_800_push2", "kodak_portra_endura", "Portra 800 Push2 Print", "Negative Color", None),
    # Slide / chrome — scanFilm path ≈ bare stock; StudioRoom already ships these
    ("fujifilm_velvia_100", "kodak_2383", "Velvia 100", "Color Slide", "duplicate Color Slide stock (scan path)"),
    ("fujifilm_provia_100f", "kodak_2383", "Provia 100F", "Color Slide", "duplicate Color Slide stock (scan path)"),
    ("kodak_ektachrome_100", "kodak_2383", "Ektachrome E100", "Color Slide", "duplicate Color Slide stock (scan path)"),
    ("kodak_kodachrome_64", "kodak_2383", "Kodachrome 64", "Color Slide", "duplicate Color Slide stock (scan path)"),
    # FX / creative overlays — grain/mist don't bake; we already ship equivalents
    ("kodak_portra_400", "kodak_portra_endura", "Dreamy Pro-Mist", "Cinematic", "FX overlay (mist/grain); not pointwise"),
    ("kodak_portra_400", "kodak_portra_endura", "Faded Matte", "Cinematic", "duplicate Matte Fade / creative FX"),
    ("kodak_ektar_100", "kodak_ultra_endura", "Bleach Bypass", "Cinematic", "duplicate existing Bleach Bypass"),
    ("kodak_portra_800", "kodak_portra_endura", "Wide-Gamut Glow", "Cinematic", "FX glow; not baked into cube"),
    ("kodak_portra_400", "kodak_portra_endura", "Neutral Clean Baseline", "Correction", "not a film look"),
]


def normalize(name: str) -> str:
    return "".join(c.lower() for c in name if c.isalnum())


def existing_normalized(luts_root: Path) -> set[str]:
    out: set[str] = set()
    for p in luts_root.rglob("*"):
        if p.suffix.lower() in {".cube", ".smcube"}:
            out.add(normalize(p.stem))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--resolution", type=int, default=33)
    ap.add_argument(
        "--out",
        type=Path,
        default=Path("_tmp_spektra_bake/out"),
        help="Staging dir for .cube before smcube convert / merge",
    )
    ap.add_argument(
        "--luts-root",
        type=Path,
        default=Path("feature/photo-editor/src/main/assets/luts"),
    )
    ap.add_argument("--limit", type=int, default=0, help="Bake at most N (debug)")
    args = ap.parse_args()

    from spektrafilm_lut_creator.builders import BundleBuilder
    from spektrafilm_lut_creator.bundles import BundleSpec

    args.out.mkdir(parents=True, exist_ok=True)
    have = existing_normalized(args.luts_root)
    report = {"baked": [], "skipped": [], "errors": []}
    baked_n = 0

    for film, paper, display, category, skip in LOOKS:
        target_norm = normalize(display)
        if skip:
            report["skipped"].append({"name": display, "reason": skip})
            continue
        # Also skip if an identically-named asset already exists
        if target_norm in have:
            report["skipped"].append(
                {"name": display, "reason": f"existing asset matches normalized '{display}'"}
            )
            continue
        # Soft dedupe: bare stock names without Print/Cinema suffix
        bare = normalize(display.replace(" Soft Print", "")
                         .replace(" Warm Print", "")
                         .replace(" Dim Print", "")
                         .replace(" Pastel Print", "")
                         .replace(" Ultra Print", "")
                         .replace(" Supra Print", "")
                         .replace(" Cool Print", "")
                         .replace(" Cinema Print", "")
                         .replace(" Premier Print", "")
                         .replace(" Minilab Print", "")
                         .replace(" Everyday Print", "")
                         .replace(" Push1 Print", "")
                         .replace(" Push2 Print", ""))
        # Only hard-skip exact display-name collisions; print-paired names are intentional variants.

        if args.limit and baked_n >= args.limit:
            break

        print(f"[bake] {display}  ({film} x {paper}) -> {category}/")
        t0 = time.time()
        try:
            spec = BundleSpec(
                film_profile=film,
                print_profiles=(paper,),
                input_color_space="sRGB",
                output_color_space="sRGB",
                topology="1lut",
                resolution=args.resolution,
                qa=False,
                ocio_config=False,
                name=f"studio_{normalize(display)}",
            )
            builder = BundleBuilder(spec)
            bundle = builder.build()
            # write into a per-look subdir then lift the .cube with our display name
            sub = args.out / "_bundle" / normalize(display)
            if sub.exists():
                shutil.rmtree(sub)
            sub.mkdir(parents=True)
            builder.write(bundle, out_dir=sub)
            cubes = list(sub.rglob("*.cube"))
            if not cubes:
                raise RuntimeError("no .cube emitted")
            dest_dir = args.out / category
            dest_dir.mkdir(parents=True, exist_ok=True)
            dest = dest_dir / f"{display}.cube"
            # Rewrite TITLE to StudioRoom display name; keep attribution comments
            text = cubes[0].read_text(encoding="utf-8", errors="replace")
            lines = text.splitlines()
            out_lines = []
            title_done = False
            for ln in lines:
                if ln.upper().startswith("TITLE") and not title_done:
                    out_lines.append(f'TITLE "{display}"')
                    title_done = True
                else:
                    out_lines.append(ln)
            if not title_done:
                out_lines.insert(0, f'TITLE "{display}"')
            # Ensure attribution comment present
            attr = (
                "# Derived from spektrafilm by Andrea Volpato\n"
                "# https://github.com/andreavolpato/spektrafilm\n"
                "# Licensed CC BY-SA 4.0 — see assets/luts/_licenses/spektrafilm/SPEKTRAFILM_LICENSE.txt\n"
                "# Modified by RAZStudio: renamed for StudioRoom stock library; grain/FX off.\n"
            )
            body = "\n".join(out_lines)
            if "spektrafilm by Andrea Volpato" not in body and "Derived from spektrafilm" not in body:
                # insert after TITLE
                parts = body.split("\n", 1)
                body = parts[0] + "\n" + attr + (parts[1] if len(parts) > 1 else "")
            dest.write_text(body + ("\n" if not body.endswith("\n") else ""), encoding="utf-8", newline="\n")
            dt = round(time.time() - t0, 1)
            print(f"  wrote {dest} ({dt}s)")
            report["baked"].append(
                {"name": display, "category": category, "film": film, "print": paper, "seconds": dt}
            )
            have.add(target_norm)
            baked_n += 1
        except Exception as e:
            print(f"  ERROR: {e}")
            report["errors"].append({"name": display, "error": str(e)})

    report_path = args.out / "bake_report.json"
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"\nBaked {len(report['baked'])}, skipped {len(report['skipped'])}, errors {len(report['errors'])}")
    print(f"Report: {report_path}")
    return 0 if not report["errors"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
