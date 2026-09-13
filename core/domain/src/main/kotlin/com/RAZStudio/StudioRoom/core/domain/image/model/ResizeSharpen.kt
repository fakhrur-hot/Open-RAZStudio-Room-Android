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

package com.RAZStudio.StudioRoom.core.domain.image.model

/**
 * Post-resize sharpening level, mirroring libresize's sharpen parameter (0–100 int).
 *
 * Mapped float values feed the post-resize unsharp (PostResizeSharpen, ×2 in
 * the kernel). Levels were shifted down a notch — the old Low was already as
 * strong as a Medium should be, and the old High (1.0 → ×2 = 2.0 gain) was
 * excessive. New ladder:
 *   None   → 0     (disabled)
 *   Low    → 0.15  (gentle; new, half the old Low)
 *   Medium → 0.30  (was the old Low)
 *   High   → 0.60  (was the old Medium; old High 1.0 dropped)
 */
enum class ResizeSharpen(val strength: Float) {
    None(0f),
    Low(0.15f),
    Medium(0.30f),
    High(0.60f),
}
