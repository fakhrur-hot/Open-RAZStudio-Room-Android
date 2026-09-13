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
 * Decoupled luma + chroma planes that flow through the segmented-CLAHE
 * pipeline. For the 16U → 8U tonemap path the luma channel is CV_16UC1
 * (Y from YCrCb) and the chroma channel is a 2-channel CV_16UC2
 * (CrCb interleaved). Once the pipeline reaches its blend stage the luma
 * is converted to CV_8UC1.
 *
 * Ownership: the caller hands the Mats to the pipeline and is responsible
 * for releasing what it created. [release] is provided as a one-shot
 * cleanup for intermediate copies the pipeline owns.
 */
internal data class ImageChannels(
    val luma: Mat,
    val chroma: Mat,
) {
    fun release() {
        luma.release()
        chroma.release()
    }
}
