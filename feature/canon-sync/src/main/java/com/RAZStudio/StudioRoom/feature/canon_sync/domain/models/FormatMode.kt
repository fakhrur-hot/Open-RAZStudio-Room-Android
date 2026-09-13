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

package com.RAZStudio.StudioRoom.feature.canon_sync.domain.models

/**
 * Which formats the user wants Canon Sync to pull off the camera.
 *
 * v1 design: this is an **app-side filter only**. We never push image-quality
 * settings back to the camera over PTP (claude advanced plan.md §Q3). If the
 * camera is shooting RAW+JPEG and the user picks RAW only, the JPEG handle
 * still appears on the card — we just don't download it. Camera card stays
 * the source of truth; we're a one-way mirror.
 */
enum class FormatMode {
    /** Pull only RAW frames (CR2 / CR3). */
    RAW,

    /** Pull only JPEG frames. */
    JPEG,

    /** Pull every shot in whatever format the camera writes. */
    RAW_AND_JPEG,
}
