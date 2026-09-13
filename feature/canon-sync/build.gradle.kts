/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

plugins {
    alias(libs.plugins.image.toolbox.library)
    alias(libs.plugins.image.toolbox.feature)
    alias(libs.plugins.image.toolbox.hilt)
    alias(libs.plugins.image.toolbox.compose)
}

android.namespace = "com.RAZStudio.StudioRoom.feature.canon_sync"

dependencies {
    // `androidx.activity.compose` (rememberLauncherForActivityResult,
    // BackHandler) is already exposed by `core/ui` via `api(libs.activityCompose)`.
    // The feature convention plugin auto-injects core/ui, so it's transitively
    // available here — no explicit declaration needed.

    // photo-editor: the Download & Process pipeline reuses the RAW batch
    // processor's per-file path (`RawBatchProcessor.processOneFile`) and
    // the shared settings panel composable (`RawBatchSettingsPanel`).
    //
    // FUTURE — module-restructure deferred:
    //
    // Today this `implementation(project(":feature:photo-editor"))` line
    // pulls the entire photo-editor graph (ONNX, libraw native, OpenCV,
    // and ~280 MB of dependent feature modules) into canon-sync's
    // compile classpath. The right end state is a thin
    // `core/raw-batch-api` module that exposes only:
    //   - `RawBatchProcessor.PerFileContext`
    //   - `RawBatchProcessor.prepareContext` / `processOneFile`
    //   - `RawBatchSettingsPanel` + `RawBatchSettings` state holder
    //   - `RawExportFormat`, `RawColorSpace`, `RawAction`, `UserMacro`,
    //     `DemosaicAlgorithm`, `BokehParams`, `NrLevel`, plus the
    //     `RawV3Coordinator.ExportOptions`/`ExportResult` value types.
    //
    // The blocker is that those v3-coordinator value types are tightly
    // bound to the v3 kernel that lives deep in feature/photo-editor;
    // splitting them into an `api` module without dragging the kernel
    // requires either:
    //   (a) reified split into `core/raw-pipeline-types` + leaving the
    //       coordinator in feature/photo-editor (the right answer but
    //       multi-day surgery), or
    //   (b) `api(...)` re-exports from a thin facade module that still
    //       depends on feature/photo-editor (no compile-time isolation
    //       win, only a naming win).
    //
    // Until that lands, [RawBatchPrefs] (which DOES live in
    // core/settings now) is the only piece that was forced into
    // feature/photo-editor purely for module-locality reasons.
    // Everything else here is a legitimate semantic dependency.
    implementation(project(":feature:photo-editor"))
}
