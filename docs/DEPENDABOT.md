# Dependabot policy (StudioRoom)

Intentional park/merge rules so dependency bumps are never "forgotten spam."

## Always after review + assemble
- App-layer minors/patches (examples: Hilt, Coil, BouncyCastle, Material3 alphas, Room/Ktor/JSON/UIAutomator when quiet): changelog skim → assemble → merge private first (`RAZStudio-Room-Android`), then open-safe mirror via foss sync when those deps exist in `_foss_export`.

## Park until smoke + parity
Do **not** auto-merge majors that touch language / vision / native:
| Dep | Why parked |
|-----|------------|
| OpenCV 4.x → 5.x | Breaking migration (C API gone, module renames). Photo stack stays on 4.x until smoke. |
| MediaPipe tasks-vision → 1.0 | Vision API surface; our subject/depth path is BiRefNet + Depth Anything ONNX, not MediaPipe. |
| Kotlin 2.3 → 2.4 | Compiler/toolchain risk; assemble + smoke before merge. |

Revisit after: Vintage Classic mistInt/texInt smoke on device, then `@parity smith` CoC / depth / mask goldens if vision/native deps change.

## Who owns what
- **Review / park decision:** StudioRoom RAZ (coder)
- **Private merge:** main sync → `fakhrur-hot/RAZStudio-Room-Android`
- **Open merge:** foss sync → `fakhrur-hot/Open-RAZStudio-Room-Android`
- **Goldens after vision/native land:** parity smith

Lensfun DB refreshes are a separate standing auto-publish lane — not dependabot.

## PR comments
Parked PRs should be closed or commented with this policy (not left silent). No dependabot auto-merge for majors.
