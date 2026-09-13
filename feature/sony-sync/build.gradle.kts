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

android.namespace = "com.RAZStudio.StudioRoom.feature.sony_sync"
// BuildConfig.DEBUG gates the verbose PTP diagnostic logging (hex/prop dumps,
// per-op traces) so release builds stay quiet.
android.buildFeatures.buildConfig = true

dependencies {
    // The feature convention plugin auto-injects core/ui, which api-exposes
    // core/domain (DispatchersHolder), core/resources (Icons) and
    // activity-compose — so BaseComponent, Screen and the resource Icons need no
    // explicit dep. core/ui does NOT expose core/settings, so declare it for
    // SettingsManager (the Default Output folder). documentfile is needed for
    // the SAF write of downloaded photos.
    implementation(project(":core:settings"))
    // Gallery DB (ProjectDao/PhotoDao + entities) so a captured photo can be
    // registered into an auto-created "Sony <Model> <Date>" workspace project.
    implementation(project(":core:database"))
    implementation(libs.androidx.documentfile)

    // EXIF read (LensSpecification/LensModel/FNumber/FocalLength/Make/Model) on
    // the incoming JPEG + write the brought-lens name back onto the uploaded
    // file. AndroidX ExifInterface carries TAG_LENS_SPECIFICATION and writes via
    // a seekable file, which the built-in android.media one doesn't reliably do.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // USB Mass Storage import (the "USB Mass Storage" connection type): read the
    // camera card's FAT volume directly over bulk-only transport, the same stack
    // feature:sd-card-browser uses. Pinned to match that module's version.
    implementation("me.jahnen.libaums:core:0.10.0")

    // Cloud live-upload Phase 2 reuses the RAW pipeline's preset + watermark on
    // each captured JPEG: RawPresetsStorage, RawV3ActionReplay.composeMacro,
    // MacroProcessor.apply, listWatermarkPresets/loadWatermarkPreset/
    // burnCombinedWatermarkOnto. Same dependency canon-sync takes for its
    // Download & Process pipeline (pulls the full photo-editor graph).
    implementation(project(":feature:photo-editor"))

    // FUTURE: the "Download & Process" pipeline will add
    // `implementation(project(":feature:photo-editor"))` to reuse
    // RawBatchProcessor, exactly as feature:canon-sync does. Deferred to keep
    // this module's classpath thin — the plain Camera Remote API pull needs
    // only java.net + org.json + XmlPullParser + SAF.
}
