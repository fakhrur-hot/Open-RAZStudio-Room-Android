---
name: studioroom-coding
description: StudioRoom/RazPortrait coding workflow. Use for Kotlin/Compose, RAW V3, masks, export, OpenGL, native C++, Gradle, adb, and device validation.
---

# StudioRoom Coding

## Discovery

Read only the closest relevant documentation:

- `CLAUDE.md`
- matching `docs/*`
- `.cursor/skills/studioroom-ai-math/SKILL.md` (AI/NPU/model work only)

Do not map the repository. Start from the nearest caller, implementation, or failing path. State one concrete hypothesis before editing.

## Ownership

- **Stage A** — RAW decode, lens profile, Rayxie, immutable cache. Do not mutate for preview features.
- **Stage B** — interactive preview, spatial operators, GPU rendering.
- **Stage C** — export.
- **MaskGraph** — source of truth for masks.
- **UserMacro** — source of truth for user-adjustable state.
- **ShaderParams** — renderer/export transport (append-only ABI).

## Core Architecture

### RAW V3

Preview and export are pixel-parity systems.

If changing `shader_sources.cpp`, GL shader, or uniform logic, verify matching CPU/export implementation exists (`apply_macro.cpp`, Stage C, native kernels).

### ShaderParams

Any slot change requires checking: Kotlin `toFloatArray()` / `fromFloatArray()`, `RawV3ActionReplay`, JNI/native parsing, uniform upload, export readers.

### Masks

Preserve alpha coverage, layer order, subject protection, and rect coordinates.

Do not rebuild masks from segmentation during replay/load.

### JPEG

JPEG uses lens profile correction, Rayxie defringe, and JPEG Refine (Dual Reconstruction spatial node: UserMacro → ShaderParams → Stage B/C).

Never call JPEG processing "demosaic". Use Dual Reconstruction / Multi-Path Reconstruction.

## Editing Rules

- Small reversible changes only
- Preserve surrounding style
- No unrelated refactors
- No new dependencies unless requested
- No credential changes
- No branch/commit operations unless the user asked

## Validation

After first change: compile smallest affected module.

Preferred order: `:lib:raw-native` (native), `:feature:photo-editor`, `:app`.

### Native Changes

Compile `:lib:raw-native:assembleFossDebug` first.

### APK Validation

`:app:assembleFossDebug` then verify APK timestamp, installed APK, and package name before claiming success.

APK exists ≠ APK installed ≠ APK running ≠ log belongs to that process.

### Process Verification

Before trusting logs: active PID, package name, installed APK timestamp, version code. Do not assume logs belong to the newest build.

### Live Runtime Observation

Runtime evidence must come from the **active execution**, not historical `adb logcat -d` dumps.

Order: optional `logcat -c` → start live logcat (filtered) → trigger the feature → read lines from this run (timestamps must match).

Historical messages are insufficient except for startup crashes before the app stays up.

### ADB Validation Standard

1. Start live logcat  
2. Trigger feature  
3. Verify execution path, parameter values, and expected branch  

Good: “JPEG Refine ran after this slider move.” Bad: “A similar line exists in old logs.”

### Visual Claims

Never claim a visual fix without fresh device observation, screenshot, or **this-session** log evidence.

## Shell (PowerShell)

- Separator is `;`, not `&&`. Quote `HEAD^{tree}` / `<` / `>` so PowerShell does not eat them.
- Prefer one focused `adb`/`git` invocation; do not dump unbounded `logcat -d`.
- Do not treat a wrapper/`git status` echo as the command result.

## Reporting

Always separate: Build Success, Install Success, Runtime Success, Visual Verification.

Unverified visual behavior must be reported as unverified.

## Context Budget Management

Treat context as two tiers.

### Hot Context

Keep only: active task, edited files, immediate callers/callees, current hypothesis, current build error.

### Cold Context

After a decision is verified: 3–10 bullet summary, file names, architecture decisions. Discard large code excerpts. Do not re-read resolved topics without new evidence.

### Build/Test Memory Release

Before Gradle, adb, parity, or logcat: keep edited files, expected outcome, validation criteria. Discard abandoned hypotheses and large excerpts.

### Architecture Cache

Reuse **Ownership**. Also: preview/export parity; Edge Refine != Denoise. Prefer these summaries over re-reading large files.
