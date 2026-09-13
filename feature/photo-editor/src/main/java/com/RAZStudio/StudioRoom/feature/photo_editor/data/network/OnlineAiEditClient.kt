/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Network client for the Online AI Editing feature.
 *
 * Reuses the app's existing Ktor [HttpClient] singleton
 * (core/data/.../di/RemoteModule.kt) via Hilt injection. No second HTTP
 * stack is introduced (no Retrofit — none exists in libs.versions.toml today,
 * and introducing one for a single client would duplicate what Ktor already
 * does, per design.md Component 3).
 *
 * v1 ships [submitBeautify] only. A future [submitRestyle] can be added as an
 * additional method without changing [OnlineAiEditResult] or the sheet's
 * composable signatures (Requirement 10.3).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.RAZStudio.StudioRoom.core.utils.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Client that submits photos to the ai-photo-cloud Cloudflare Worker backend
 * for cloud-assisted AI operations.
 *
 * Injected via Hilt; depends on the app's existing [HttpClient] singleton
 * (provided by `RemoteModule.client()`, already configured with a 5-minute
 * [io.ktor.client.plugins.HttpTimeout]) and on [SupabaseAnonAuthProvider] for
 * anonymous-auth bearer tokens.
 *
 * Every public method returns a sealed [OnlineAiEditResult] — null is never
 * returned and exceptions are never swallowed silently (Property 1: No silent
 * failure, Requirements 6.3, 6.4).
 */
class OnlineAiEditClient @Inject constructor(
    private val http: HttpClient,
    private val authProvider: SupabaseAnonAuthProvider,
) {

    companion object {
        /**
         * The `ai-photo-cloud` companion Worker's edit endpoint, deployed to
         * Cloudflare account 54ef7e2002f77ddc0220381d06499952
         * (Fakhrurr34_nismo@yahoo.com's Account — separate from the
         * kopibulan88 account used by unrelated cafe projects).
         */
        private const val EDIT_ENDPOINT_URL = "https://ai-photo-cloud.fakhrurr34-nismo.workers.dev/v1/edit"

        private const val JOB_ID_HEADER = "X-Job-Id"

        /** JPEG quality used when encoding [Bitmap.compress] for upload. */
        private const val UPLOAD_JPEG_QUALITY = 90

        private const val TAG = "OnlineAiEditClient"
    }

    /**
     * Submits an AI Beautify request (skin-smoothing / blemish removal,
     * face-aware) to the Cloudflare Worker's `/v1/edit` endpoint.
     *
     * The [previewBitmap] should be the same 512-long-side preview bitmap
     * already decoded for on-device segmentation — not a separately
     * re-encoded copy (Requirement 3.5). The [faceMask] should be the
     * segmentation mask already produced by `RawV3Coordinator` (FaceSkin /
     * BodySkin class, or face-ellipse from `RawV3FaceDetector`) for the
     * current photo; passing it reduces server-side compute and keeps the
     * cloud-beautify boundary consistent with what the Mask tab shows
     * (Requirements 5.1, 5.2). Pass `null` when no mask is available yet —
     * the sheet is responsible for triggering `ensureSegmentation()` before
     * calling this method when a mask is required (Requirement 5.3).
     *
     * @param previewBitmap The 512-long-side preview image to beautify.
     * @param faceMask Optional face/skin segmentation mask reused from the
     *   on-device segmentation pipeline. Null when unavailable.
     * @param strength The desired beautify intensity (Low / Medium / High).
     * @return A sealed [OnlineAiEditResult]:
     *   - [OnlineAiEditResult.Success] on HTTP 200 with a valid result image.
     *   - [OnlineAiEditResult.QuotaExceeded] on HTTP 429 (Property 2:
     *     Quota distinguishability, Requirements 6.1, 6.2).
     *   - [OnlineAiEditResult.Failure] for any other non-2xx status, network
     *     timeout, connectivity loss, or unexpected exception.
     */
    suspend fun submitBeautify(
        previewBitmap: Bitmap,
        faceMask: Bitmap?,
        strength: BeautifyStrength,
    ): OnlineAiEditResult {
        val jwt = try {
            authProvider.ensureValidJwt()
        } catch (e: Exception) {
            AppLog.e(TAG, "submitBeautify: ensureValidJwt failed", e)
            return OnlineAiEditResult.Failure("Unable to establish cloud session: ${e.message}")
        }

        return try {
            val imageBytes = previewBitmap.toJpegBytes()
            val maskBytes = faceMask?.toPngBytes()

            val response = http.submitFormWithBinaryData(
                url = EDIT_ENDPOINT_URL,
                formData = formData {
                    append("operation", OnlineAiOperation.Beautify.name)
                    append("strength", strength.name)
                    append(
                        key = "image",
                        value = imageBytes,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, "image/jpeg")
                            // Ktor does not auto-inject `form-data; name="image"` when an
                            // explicit Content-Disposition header is supplied here — the
                            // full RFC 7578 form must be built manually, or the receiving
                            // server's formData() parser rejects the body as invalid
                            // (confirmed against the ai-photo-cloud Worker's strict parser).
                            append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"preview.jpg\"")
                        },
                    )
                    if (maskBytes != null) {
                        append(
                            key = "mask",
                            value = maskBytes,
                            headers = Headers.build {
                                append(HttpHeaders.ContentType, "image/png")
                                append(HttpHeaders.ContentDisposition, "form-data; name=\"mask\"; filename=\"mask.png\"")
                            },
                        )
                    }
                },
            ) {
                header(HttpHeaders.Authorization, "Bearer $jwt")
            }

            when (response.status) {
                HttpStatusCode.OK -> {
                    val resultBytes = response.bodyAsBytes()
                    val resultBitmap = BitmapFactory.decodeByteArray(resultBytes, 0, resultBytes.size)
                        ?: return OnlineAiEditResult.Failure("Worker returned an undecodable image")
                    val jobId = response.headers[JOB_ID_HEADER] ?: ""
                    OnlineAiEditResult.Success(resultBitmap, jobId)
                }

                HttpStatusCode.TooManyRequests -> {
                    val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.seconds
                    AppLog.i(TAG, "submitBeautify: quota exceeded, retryAfter=$retryAfter")
                    OnlineAiEditResult.QuotaExceeded(retryAfter)
                }

                else -> {
                    val body = response.bodyAsText().take(200)
                    AppLog.e(TAG, "submitBeautify: HTTP ${response.status.value}: $body")
                    OnlineAiEditResult.Failure("Worker returned HTTP ${response.status.value}: $body")
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "submitBeautify: request failed", e)
            OnlineAiEditResult.Failure(e.message ?: "Cloud AI request failed")
        }
    }

    /**
     * Encodes this bitmap as JPEG at [UPLOAD_JPEG_QUALITY] — the upload
     * payload is always the 512-long-side preview already decoded for
     * on-device segmentation (Requirement 3.5), not a full-resolution image.
     */
    private fun Bitmap.toJpegBytes(): ByteArray =
        ByteArrayOutputStream().use { stream ->
            compress(Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY, stream)
            stream.toByteArray()
        }

    /** Encodes this bitmap as lossless PNG — used for the mask part only. */
    private fun Bitmap.toPngBytes(): ByteArray =
        ByteArrayOutputStream().use { stream ->
            compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
}
