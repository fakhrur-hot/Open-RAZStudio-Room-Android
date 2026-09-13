# libdualiso

Modernized port of Magic Lantern's `cr2hdr.c` post-processing pipeline.

## What this is

A clean-room C++17 restructure of the proven cr2hdr blend algorithm. Same math, different packaging:

| Original cr2hdr (2013) | libdualiso |
|---|---|
| 3663 LOC monolithic `.c` file | 12 focused `.cpp`/`.hpp` files |
| `static` globals everywhere | `BlendContext` struct, no module state |
| Shells out to `dcraw` + `exiftool` + `Adobe DNG Converter` binaries | LibRaw direct + minimal DNG writer (no external processes) |
| `chdk-dng.c` (790 LOC) DNG writer | Lean DNG writer or "FP16 buffer only" mode |
| x86 SSE2-only (`helpersse2.h`) | Plain C++17 + NEON-amenable inner loops |
| Desktop-only build | Builds for arm64-v8a Android + native desktop |

## What this is NOT

- A **new** dual-ISO algorithm. The math (deinterlace → match exposures → interpolate → chroma smooth → optional alias map) is unchanged from cr2hdr. We trust a1ex / dmilligan's work; we're just repackaging it for modern use.
- A drop-in `cr2hdr.exe` replacement (yet). The CLI surface, file I/O, and metadata handling are reduced — see "in-pipeline blend" mode below.
- Validated bit-exact against desktop cr2hdr (yet). Validation harness lands when sample files + reference outputs are wired in.

## License

GPL-2 or later. Derived from cr2hdr.c (©2013 Magic Lantern Team, GPL-2+).

## Architecture

```
BlendContext     — replaces all the cr2hdr static globals.
                   Holds bayer buffer pointer, dims, black/white, ISO pair,
                   bayer pattern, mix curves, etc.
hdr_check        — detector (cr2hdr L1246–1284, faithful port).
identify_pattern — RGGB vs GBRG (cr2hdr L1285–1355, faithful port).
identify_fields  — which row pair is the bright ISO (cr2hdr L1357–1527).
match_exposures  — find the EV difference between dark/bright planes.
interpolate      — mean23 path now; amaze-edge path later.
chroma_smooth    — 2x2/3x3/5x5 chroma cleanup (cr2hdr L1842).
hot_pixel_fix    — known-issue cleanup.
stripe_fix       — horizontal banding correction.
```

The pipeline driver is `BlendKernel::run(BlendContext&)` — orchestrates the
above in cr2hdr's documented order, but with no shared mutable state outside
the supplied context.

## Build

CMake picks up `libdualiso/CMakeLists.txt`. The current `dualiso_blend.cpp`
delegates to libdualiso for the parts that have landed; remaining symbols
keep the existing stub bodies so the .so always links and Stage 1 detection
keeps working.
