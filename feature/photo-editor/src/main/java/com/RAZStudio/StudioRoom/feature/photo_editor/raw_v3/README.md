# RAW Pipeline v3 — package overview

Skeleton only. Tracks `.kiro/specs/raw-pipeline-v3-rebuild/Plan.md`.

Until `BuildConfig.USE_RAW_V3 = true` (milestone M12), nothing in this package
is invoked at runtime. The existing `com.RAZStudio.StudioRoom.feature.photo_editor.raw`
package remains the production engine.

## Files

| File | Status | Milestone |
|---|---|---|
| `RawV3State.kt` | Skeleton | M1 |
| `RawV3WorkspaceOptions.kt` | Skeleton | M1 |
| `RawV3Cache.kt` | Skeleton | M1 |
| `RawV3Engine.kt` | JNI stubs (throw `NotImplementedError`) | M1 |
| `RawV3Coordinator.kt` | State machine skeleton | M1 |
| `RawV3ActionReplay.kt` | _missing_ | M6 |
| `RawV3GlSurfaceView.kt` | _missing_ | M3–M4 |
| `apply_macro.h/.cpp` (native) | _missing_ | M2 |
| `stage_a.cpp` (native) | _missing_ | M2 |
| `stage_b_downsample.cpp` (native) | _missing_ | M3 |
| `gles_renderer.cpp` (native) | _missing_ | M3–M4 |
| `stage_c_export.cpp` (native) | _missing_ | M8 |
| `v3_jni.cpp` (native) | _missing_ | M2+ |
| `shaders/uber.frag` | _missing_ | M4 |

## Toggling the flag

```bash
# Run a v3-enabled debug build (will crash on RAW open until M2 ships):
./gradlew :app:assembleFossDebug -PuseRawV3=true
```
