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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.pipeline

import android.net.Uri

/**
 * v2 §A.3 — the four pipelines defined by `.kiro/specs/raw-pipeline-v2/requirements.md`.
 *
 * [Preview] half-res live editor canvas, both 8-bit and 16-bit.
 * [Idle]    full-res low-priority compare; cancellable on any user input.
 * [Save8]   full-res save path for the 8-bit workspace; full-speed; no half-res fallback.
 * [Save16]  full-res save path for the 16-bit workspace; same hard-fail contract.
 *
 * Batch reuses [Save8] per file — there is no [PipelineId.Batch] entry on purpose so that all
 * batch progress flows through the same event channel as interactive save.
 */
enum class PipelineId { Preview, Idle, Save8, Save16 }

/**
 * High-level stage the save pipeline is currently in. Drives the progress sheet's label.
 * Percent ranges are advisory (UI uses them to round-trip an "overall %" with the actual
 * [SavePipelineEvent.Progress.percent] value); the pipeline emits real progress within
 * each stage.
 */
enum class SaveStage(val displayName: String, val rangeStart: Int, val rangeEnd: Int) {
    Decoding("Decoding RAW…", 0, 30),
    Demosaicing("Demosaicing…", 30, 60),
    ApplyingMacro("Applying adjustments…", 60, 90),
    Encoding("Encoding…", 90, 100),
}

/**
 * Reason a save pipeline ended unsuccessfully. Maps 1:1 to a user-visible string resource in
 * `core/resources/strings.xml` (see v2-integration §8). Keeping this enum small and stable
 * lets the UI render consistent error wording across the save sheet and batch row.
 *
 * - [CancelledByUser]    — user tapped Cancel; UI dismisses silently with no toast.
 * - [VulkanUnavailable]  — GPU path was selected but Vulkan capability check failed; the
 *                          coordinator will fall back to CPU automatically. Surfaced for
 *                          telemetry only, never shown to the user.
 */
enum class FailureKind {
    DecodeTimeout,
    OutOfMemory,
    SilentZeroBuffer,
    InsufficientScratch,
    OutputWriteFailed,
    DcpParseFailed,
    SidecarCorrupt,
    VulkanUnavailable,
    CancelledByUser,
    Unknown,
}

/**
 * Events emitted by [com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawPipelineCoordinator]
 * for the editor save sheet, batch screen, and telemetry. See v2-integration design.md
 * §SavePipelineEvent Flow.
 *
 * The flow has `replay = 0`: events are point-in-time, late subscribers don't see history.
 * Progress is throttled to ~10 Hz per pipeline to keep the Compose recomposition cost
 * predictable on the supported-floor device.
 */
sealed interface SavePipelineEvent {
    val pipelineId: PipelineId

    data class Started(
        override val pipelineId: PipelineId,
        val sourceUri: Uri,
    ) : SavePipelineEvent

    data class Stage(
        override val pipelineId: PipelineId,
        val stage: SaveStage,
    ) : SavePipelineEvent

    data class Progress(
        override val pipelineId: PipelineId,
        /** [0, 100] inclusive. UI clamps; pipelines may briefly emit values outside on cancel. */
        val percent: Int,
    ) : SavePipelineEvent

    data class Completed(
        override val pipelineId: PipelineId,
        val outputUri: Uri,
        val durationMs: Long,
    ) : SavePipelineEvent

    data class Failed(
        override val pipelineId: PipelineId,
        val kind: FailureKind,
        val cause: Throwable? = null,
    ) : SavePipelineEvent

    data class Cancelled(
        override val pipelineId: PipelineId,
    ) : SavePipelineEvent
}
