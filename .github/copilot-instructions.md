# StudioRoom Agent Rules

## Stack
- Android Jetpack Compose, Kotlin, Gradle, native C++/OpenGL ES.
- RAW V3 pipeline: Stage A decode, Stage B GL preview, Stage C export.

## Core Rules
- Make surgical edits and preserve existing APIs and local patterns.
- Root-cause bugs before patching; use the nearest test, log, or focused check.
- Preview must match export: pixel-affecting GL changes require the CPU mirror.
- ShaderParams slots are append-only; check Kotlin serialization, native parsing, preview upload, and export readers.
- Read `CLAUDE.md` and the relevant `docs/` guidance before pipeline or mask changes.
- Never revert unrelated user changes or commit without request.

## Validation
- Prefer focused Gradle tasks before full APK builds.
- For device changes, build `:app:assembleFossDebug`, verify APK timestamp, then install with adb.
- Use parity goldens when changing `shader_sources.cpp`, `apply_macro.cpp`, or shared uniforms.
- Keep logs filtered to the touched feature and report build/install failures explicitly.