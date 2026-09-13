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

android.namespace = "com.RAZStudio.StudioRoom.feature.sd_card_browser"

dependencies {
    testImplementation(libs.junit)

    implementation(project(":feature:photo-editor"))
    implementation(project(":lib:raw-native"))
    implementation(project(":lib:raw-native-dualiso"))
    implementation(libs.androidx.documentfile)

    // Raw USB Mass Storage (bulk-only transport + FAT) driver for OTG card readers
    // that Android doesn't auto-mount as a StorageVolume. Apache-2.0 licensed.
    implementation("me.jahnen.libaums:core:0.10.0")

    // Shared with Canon Sync's PTP/IP download path so both import pipelines
    // write to disk identically (SafCaptureTarget: staging + atomic rename +
    // buffered SAF sink) — the only difference is the source-side read (PTP
    // stream vs. USB mass-storage / SAF input stream). See FileOpener.kt.
    implementation(project(":feature:canon-sync"))
    implementation(project(":core:settings"))
    // okio is already on canon-sync's own classpath (transitively), but as
    // an `implementation` dep it isn't exposed to us as a consumer — declare
    // it directly since SafCaptureTarget's public API is typed in terms of
    // okio.BufferedSink.
    implementation("com.squareup.okio:okio:3.9.0")
}
