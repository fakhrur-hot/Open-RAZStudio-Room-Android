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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.domain

import org.opencv.core.Mat

/**
 * Wraps the U2-Net soft-alpha mask used to separate subject from
 * background. The matrix is single-channel float32 (CV_32FC1) with
 * values in `[0.0, 1.0]`: 1 = full foreground, 0 = full background.
 *
 * The pipeline never mutates the wrapped Mat in place — operations
 * either copy or produce new Mats. Callers that build a mask from a
 * non-float source can hand a CV_8UC1 / CV_16UC1 Mat in; the
 * [com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit.operations.MaskPreprocessor]
 * normalises to CV_32FC1 on first touch.
 */
internal data class SegmentationMask(val matrix: Mat) {
    fun release() = matrix.release()
}
