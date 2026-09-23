#!/usr/bin/env python3
"""Re-sync the bundled camera/lens profile database with lensfun master.

What it does (2026-09-07):
  1. Downloads github.com/lensfun/lensfun master and writes its data/db/*.xml
     verbatim over feature/photo-editor/src/main/assets/lensfun_db/ (CRLF, to
     match the repo). Files that are NOT in master (zz-*.xml) are never touched.
  2. Re-applies the in-place patches from still-OPEN upstream PRs listed in
     PATCHES below. A patch is skipped with a warning once master already has
     it (the PR landed) — then delete it from PATCHES.
  3. Reports what changed vs. the previous bundle and reminds you to bump
     LensfunDatabase.DB_VERSION.

Why a script: the previous "in-place patch" (Samyang 12mm AF vignetting,
PR #2004) was documented as applied but never was; anything not automated
drifts. Usage:  python scripts/lensfun_sync.py [--dry-run]
"""
import glob, io, os, re, sys, tarfile, urllib.request
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUND = os.path.join(ROOT, "feature", "photo-editor", "src", "main", "assets", "lensfun_db")
MASTER_TGZ = "https://github.com/lensfun/lensfun/archive/refs/heads/master.tar.gz"
PR_DIFF = "https://patch-diff.githubusercontent.com/raw/lensfun/lensfun/pull/{n}.diff"

# (file, first <model> of the lens, PR number, note). The PR diff's added
# <vignetting> rows are inserted before </calibration> of that lens.
PATCHES = [
    ("mil-samyang.xml", "Samyang AF 12mm f/2.0", 2004, "Samyang AF 12mm f/2.0 vignetting"),
]

DRY = "--dry-run" in sys.argv


def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


# In --dry-run nothing touches disk, so patches are evaluated against the text
# that WOULD have been written (otherwise the check sees the already-patched
# bundle and wrongly reports every PR as landed).
PENDING = {}


def write_crlf(path, text):
    text = text.replace("\r\n", "\n").replace("\n", "\r\n")
    PENDING[path] = text
    if DRY:
        return
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def read_current(path):
    text = PENDING.get(path)
    if text is None:
        text = open(path, encoding="utf-8", newline="").read()
    return text.replace("\r\n", "\n")


def counts(path):
    root = ET.parse(path).getroot()
    return len(root.findall("camera")), len(root.findall("lens"))


def main():
    before = {os.path.basename(f): counts(f) for f in glob.glob(os.path.join(BUND, "*.xml"))}

    print("downloading lensfun master …")
    tgz = fetch(MASTER_TGZ)
    synced = 0
    with tarfile.open(fileobj=io.BytesIO(tgz), mode="r:gz") as tf:
        for m in tf.getmembers():
            if "/data/db/" in m.name and m.name.endswith(".xml") and m.isfile():
                name = os.path.basename(m.name)
                text = tf.extractfile(m).read().decode("utf-8")
                ET.fromstring(text)  # refuse to write anything that does not parse
                write_crlf(os.path.join(BUND, name), text)
                synced += 1
    print(f"synced {synced} master files")

    for fname, model, pr, note in PATCHES:
        path = os.path.join(BUND, fname)
        s = read_current(path)
        i = s.find("<model>" + model + "</model>")
        if i < 0:
            print(f"WARN PR#{pr}: lens '{model}' not found in {fname} — renamed upstream?")
            continue
        cal_end = s.index("</calibration>", i)
        if "<vignetting" in s[i:cal_end]:
            print(f"skip PR#{pr}: {model} already has vignetting — the PR landed; remove it from PATCHES")
            continue
        diff = fetch(PR_DIFF.format(n=pr)).decode("utf-8")
        rows = [l[1:].strip() for l in diff.splitlines() if l.startswith("+") and "<vignetting" in l]
        if not rows:
            print(f"WARN PR#{pr}: no vignetting rows in diff — PR changed shape?")
            continue
        indent = re.search(r"\n([ \t]*)<distortion", s[i:cal_end]).group(1)
        ins = f"{indent}<!-- {note} — lensfun PR #{pr}, open upstream; re-applied by scripts/lensfun_sync.py -->\n"
        ins += "".join(indent + r + "\n" for r in rows)
        s = s[:cal_end].rstrip(" \t") + ins + indent[:-4] + "</calibration>" + s[cal_end + len("</calibration>"):]
        write_crlf(path, s)
        print(f"patched {fname}: {model} +{len(rows)} vignetting rows (PR #{pr})")

    after = {os.path.basename(f): counts(f) for f in glob.glob(os.path.join(BUND, "*.xml"))}
    for name in sorted(set(before) | set(after)):
        if before.get(name) != after.get(name):
            print(f"  {name}: {before.get(name)} -> {after.get(name)} (cameras, lenses)")
    tc = sum(c for c, _ in after.values()); tl = sum(l for _, l in after.values())
    print(f"bundle: {len(after)} files, {tc} cameras, {tl} lenses")
    print("REMINDER: bump DB_VERSION in raw_v3/LensfunDatabase.kt and update docs/FEATURES.md counts.")


if __name__ == "__main__":
    main()
