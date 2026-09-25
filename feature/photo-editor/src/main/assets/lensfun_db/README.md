# Lensfun database (builder-supplied)

The Lensfun lens-correction database is **not bundled** in Open RAZStudio Room.
Lens correction (distortion, vignetting, TCA) is optional — the app builds and
runs offline without it. `LensfunDatabase.ensureMaterialized()` returns `null`
when this folder has no `.xml` files, and lens correction is simply disabled.

## To enable lens correction in your build

1. Get the Lensfun database XML files from the upstream project:
   - https://github.com/lensfun/lensfun  (see `data/db/*.xml`)
2. Copy those `*.xml` files directly into this folder:
   `feature/photo-editor/src/main/assets/lensfun_db/`
3. Rebuild. The files are materialised into the app's `filesDir` on first run.

Lensfun is distributed under its own licenses (LGPL-3.0 for the library; the
database under CC-BY-SA / public-domain per-file). Review and comply with the
upstream license before redistributing any database you add here.

> This README is a placeholder so the folder exists in the repo. Do not commit
> the actual database XMLs unless you have verified their license permits it.
