#!/usr/bin/env python3
"""
Generate curated "filmic fade / soft" look presets by ORCHESTRATING existing
RAW-editor controls — no new shader passes, no new ShaderParams slots. Each look
is one <action> card whose 177-field macro is cloned from a neutral base
(auto_expose_sharp's card, with its sharpness zeroed) and then has only the look
fields set. Values are CONSERVATIVE STARTING POINTS meant for on-device tuning.

Field mapping (corrected against the real serializer schema):
  fade / black lift  -> cgShadows{R,G,B}  (0-centered; raising lifts+tints shadows)
  highlight roll-off -> filmRolloff
  warm/cool tint     -> highlightTint (+warm), shadowTemperature (-cool)
  desaturate (fade)  -> saturation  (0-centered, negative = less saturated)
  soft blur          -> fxGaussBlur
  film texture       -> filmGrain / filmGrainSize / filmGrainWashOut
"""
import os
import re
import sys
import uuid

PRESETS_DIR = "feature/photo-editor/src/main/assets/presets"
BASE = os.path.join(PRESETS_DIR, "auto_expose_sharp.xml")

# name -> (filename, display, {field: value}) ; only these fields deviate from neutral
LOOKS = [
    ("Film Fade Warm", "film_fade_warm.xml", {
        "cgShadowsR": 0.05, "cgShadowsG": 0.03, "cgShadowsB": 0.015,  # warm shadow lift = fade
        "highlightTint": 0.05,        # warm highlights (tune sign on device)
        "filmRolloff": 0.20,          # soften highlights
        "saturation": -0.12,          # pull colour back for a faded look
        "filmGrain": 0.06, "filmGrainSize": 0.35,
    }),
    ("Film Fade Cool", "film_fade_cool.xml", {
        "cgShadowsR": 0.01, "cgShadowsG": 0.02, "cgShadowsB": 0.05,   # cool shadow lift
        "shadowTemperature": -0.05,   # cool shadows
        "filmRolloff": 0.18,
        "saturation": -0.15,
        "filmGrain": 0.05, "filmGrainSize": 0.35,
    }),
    ("Soft Portrait", "soft_portrait.xml", {
        "cgShadowsR": 0.03, "cgShadowsG": 0.02, "cgShadowsB": 0.01,   # gentle warm lift
        "filmRolloff": 0.15,
        "saturation": -0.06,
        "fxGaussBlur": 0.12,          # subtle overall softening (tune on device)
        "filmGrain": 0.04, "filmGrainSize": 0.45,
    }),
]

def base_action():
    xml = open(BASE, encoding="utf-8", errors="replace").read()
    block = re.search(r'<action[^>]*>.*?</action>', xml, re.S).group(0)
    # neutral base: this card's only real adjustment is sharpness — zero it.
    block = re.sub(r'<sharpness>[^<]*</sharpness>', '<sharpness>0.0</sharpness>', block)
    return block

def set_field(block, field, value):
    pat = re.compile(rf'<{field}>[^<]*</{field}>')
    repl = f'<{field}>{value}</{field}>'
    new, n = pat.subn(repl, block)
    if n == 0:
        raise SystemExit(f"field <{field}> not found in preset schema")
    return new

def make_preset(display, fields):
    block = base_action()
    # fresh identity + Color-tab grouping + descriptive label
    block = re.sub(r'id="[^"]*"', f'id="{uuid.uuid4()}"', block, count=1)
    block = re.sub(r'label="[^"]*"', f'label="{display}"', block, count=1)
    block = re.sub(r'tabIndex="[^"]*"', 'tabIndex="1"', block, count=1)
    for f, v in fields.items():
        block = set_field(block, f, v)
    return ("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"
            '<rawActions version="2">' + block + '</rawActions>\n')

if __name__ == "__main__":
    index_lines = []
    for display, fname, fields in LOOKS:
        out = os.path.join(PRESETS_DIR, fname)
        open(out, "w", encoding="utf-8", newline="\n").write(make_preset(display, fields))
        print(f"wrote {fname}  ({display})")
        index_lines.append(f"{display}|{fname}|BIT_16")
    # append to index.txt if not already present
    idx = os.path.join(PRESETS_DIR, "index.txt")
    existing = open(idx, encoding="utf-8").read() if os.path.exists(idx) else ""
    with open(idx, "a", encoding="utf-8", newline="\n") as f:
        for line in index_lines:
            name = line.split("|")[0]
            if name not in existing:
                f.write(line + "\n"); print(f"  index.txt += {line}")
