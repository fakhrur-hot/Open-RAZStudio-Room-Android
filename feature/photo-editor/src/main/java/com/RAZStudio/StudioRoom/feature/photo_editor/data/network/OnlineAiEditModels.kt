/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Data models for the Online AI Editing feature.
 *
 * Defines the request/result contracts used by [OnlineAiEditClient] when
 * submitting photos to the ai-photo-cloud Cloudflare Worker backend.
 *
 * v1 ships AI Beautify only. [OnlineAiOperation.Restyle] and
 * [OnlineAiEditRequest.styleParam] are present for contract extensibility —
 * a future [OnlineAiEditClient.submitRestyle] can be added without changing
 * [OnlineAiEditResult] or any sheet composable signatures.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.data.network

import android.graphics.Bitmap
import kotlin.time.Duration

// ── Operation and strength enums ─────────────────────────────────────────────

/**
 * Cloud AI operation type.
 *
 * [Beautify] is the only operation shipped in v1 (skin-smoothing / blemish
 * removal, face-aware). [Restyle] is reserved for a future GAN/diffusion
 * restyle operation and is intentionally unused in this plan — its presence
 * keeps the sealed [OnlineAiEditResult] contract stable when Restyle is added.
 */
enum class OnlineAiOperation { Beautify, Restyle }

/**
 * Discrete strength levels for the AI Beautify operation.
 *
 * Mapped to [OnlineAiEditRequest.strengthParam]. Three fixed presets are used
 * instead of a continuous slider to keep server-side caching and preset
 * handling simple, and because "skin smoothing" reads very differently across
 * subjects and skin tones — a fixed single strength would risk "too plastic"
 * or "did nothing" outcomes.
 */
enum class BeautifyStrength { Low, Medium, High }

// ── Request model ─────────────────────────────────────────────────────────────

/**
 * Payload sent by [OnlineAiEditClient] to the Cloudflare Worker's `/v1/edit`
 * endpoint (multipart/form-data).
 *
 * @property operation Which cloud AI operation to run. v1 callers always pass
 *   [OnlineAiOperation.Beautify].
 * @property imageBytes JPEG-encoded preview bitmap, already resized to
 *   512-long-side — the same buffer decoded for on-device segmentation, not a
 *   separately re-encoded copy (Requirement 3.5).
 * @property maskBytes Optional PNG-encoded segmentation mask reused from
 *   [RawV3Coordinator] (FaceSkin / BodySkin class, or face-ellipse from
 *   [RawV3FaceDetector]). Null when no mask is available. Passing it reduces
 *   server-side compute and keeps the cloud-beautify boundary consistent with
 *   what the Mask tab shows (Requirements 5.1, 5.2).
 * @property strengthParam Beautify strength level; null for operations that
 *   do not use a strength parameter (e.g. future Restyle).
 * @property styleParam Style identifier for future Restyle operations; null
 *   for Beautify. Not used in v1.
 */
data class OnlineAiEditRequest(
    val operation: OnlineAiOperation,
    val imageBytes: ByteArray,
    val maskBytes: ByteArray?,
    val strengthParam: BeautifyStrength? = null,
    val styleParam: String? = null,
) {
    // ByteArray requires explicit equals/hashCode — structural equality on the
    // byte contents rather than the default referential identity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OnlineAiEditRequest) return false
        return operation == other.operation &&
            imageBytes.contentEquals(other.imageBytes) &&
            (maskBytes == null && other.maskBytes == null ||
                maskBytes != null && other.maskBytes != null &&
                maskBytes.contentEquals(other.maskBytes)) &&
            strengthParam == other.strengthParam &&
            styleParam == other.styleParam
    }

    override fun hashCode(): Int {
        var result = operation.hashCode()
        result = 31 * result + imageBytes.contentHashCode()
        result = 31 * result + (maskBytes?.contentHashCode() ?: 0)
        result = 31 * result + (strengthParam?.hashCode() ?: 0)
        result = 31 * result + (styleParam?.hashCode() ?: 0)
        return result
    }
}

// ── Result model ──────────────────────────────────────────────────────────────

/**
 * Sealed result returned by every [OnlineAiEditClient] call.
 *
 * Every call path produces exactly one of these variants — null is never
 * returned and exceptions are never swallowed silently (Property 1: No silent
 * failure, Requirements 6.3, 6.4).
 *
 * The shape is intentionally stable: adding [OnlineAiOperation.Restyle] in a
 * future plan requires only a new [OnlineAiEditClient] method, not a change
 * to this sealed hierarchy (Requirement 10.3).
 */
sealed interface OnlineAiEditResult {

    /**
     * The cloud operation succeeded and produced a result bitmap.
     *
     * @property resultBitmap The processed image returned by the Worker,
     *   decoded to an ARGB_8888 [Bitmap] at the same dimensions as the
     *   submitted preview. Composited into the cosmetic preview bitmap chain
     *   by the sheet on Apply.
     * @property jobId The Worker-assigned job identifier, used for EXIF
     *   tagging (Cloud_Edit_Tag, Requirement 8.1).
     */
    data class Success(
        val resultBitmap: Bitmap,
        val jobId: String,
    ) : OnlineAiEditResult

    /**
     * The Worker's inference-provider quota was exhausted (HTTP 429).
     *
     * Distinct from [Failure] so the sheet can render a specific message
     * ("Cloud AI quota reached — try again later") and offer the on-device
     * [RawHealSheet] as a fallback action (Requirements 6.1, 6.2).
     *
     * @property retryAfter Optional cooldown duration parsed from the
     *   `Retry-After` response header; null when the header is absent.
     */
    data class QuotaExceeded(
        val retryAfter: Duration?,
    ) : OnlineAiEditResult

    /**
     * Any failure that is not a quota event: network timeout, connectivity
     * loss, non-2xx / non-429 HTTP status, or unexpected exception.
     *
     * The photo is left unchanged when this is returned — a partial or
     * corrupt result is never applied (Requirements 6.3, 6.4).
     *
     * @property reason Human-readable description of the failure, suitable
     *   for display in an inline error message in the sheet.
     */
    data class Failure(
        val reason: String,
    ) : OnlineAiEditResult
}
