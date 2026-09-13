/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML_6D Sidecar JSON Parser.
 *
 * Parses per-shot sidecar files ({filename}.ml6d) and per-session export
 * files (ml6d_export.json) from the ML_6D firmware. Uses org.json (JSONObject)
 * for parsing, consistent with Android patterns.
 *
 * Validation rules:
 *  - formatVersion must equal 2 (current schema)
 *  - filename field is required in sidecars and must match the CR2 filename
 *  - Optional blocks (dualIso, ettr, lens, pictureStyle) are parsed when present
 *  - Files exceeding 1 MB or failing JSON parse are rejected gracefully
 *  - Invalid/missing fields within optional blocks cause that block to be skipped
 *
 * All parsing methods return null on failure (never throw) to support graceful
 * degradation — the pipeline falls back to EXIF-only processing.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.RAZStudio.StudioRoom.core.utils.AppLog
import org.json.JSONException
import org.json.JSONObject
import java.io.File

private const val TAG = "MlSidecar"

/**
 * Parser for ML_6D firmware data export files.
 *
 * Provides methods to parse per-shot sidecar and per-session export JSON
 * into their respective data models. All methods are null-safe and return
 * null on any parse failure (graceful degradation).
 */
object MlSidecarParser {

    /** Maximum sidecar file size (1 MB). Files exceeding this are rejected. */
    const val MAX_SIDECAR_SIZE_BYTES = 1_048_576L

    /** Current supported format version. */
    const val CURRENT_FORMAT_VERSION = 2

    // ── Per-Shot Sidecar Parsing ─────────────────────────────────────────────

    /**
     * Parse a per-shot sidecar JSON string into [MlShotSidecar].
     *
     * @param json The raw JSON string content of the .ml6d file.
     * @param expectedFilename The CR2 filename to validate against (without path).
     *                         Pass null to skip filename validation.
     * @return Parsed sidecar data, or null if parsing/validation fails.
     */
    fun parseShotSidecar(json: String, expectedFilename: String? = null): MlShotSidecar? {
        if (json.isBlank()) return null
        return try {
            val root = JSONObject(json)

            // Validate formatVersion
            val formatVersion = root.optInt("formatVersion", -1)
            if (formatVersion != CURRENT_FORMAT_VERSION) {
                AppLog.w(TAG, "parseShotSidecar: rejected, formatVersion=$formatVersion (expected $CURRENT_FORMAT_VERSION)")
                return null
            }

            // Validate filename field is present
            val filename = root.optString("filename", "")
            if (filename.isEmpty()) {
                AppLog.w(TAG, "parseShotSidecar: rejected, missing filename field")
                return null
            }

            // Validate filename matches expected CR2 if provided
            if (expectedFilename != null && !filename.equals(expectedFilename, ignoreCase = true)) {
                AppLog.w(TAG, "parseShotSidecar: rejected, filename='$filename' does not match expected='$expectedFilename'")
                return null
            }

            // Parse optional blocks
            val dualIso = parseDualIsoBlock(root.optJSONObject("dualIso"))
            val ettr = parseEttrBlock(root.optJSONObject("ettr"))
            val lens = parseLensBlock(root.optJSONObject("lens"))
            val pictureStyle = root.optString("pictureStyle", "").ifEmpty { null }

            AppLog.i(
                TAG,
                "parseShotSidecar: accepted filename=$filename dualIso=${dualIso != null} " +
                    "ettr=${ettr != null} lens=${lens != null} pictureStyle=$pictureStyle",
            )
            MlShotSidecar(
                formatVersion = formatVersion,
                filename = filename,
                dualIso = dualIso,
                ettr = ettr,
                lens = lens,
                pictureStyle = pictureStyle,
            )
        } catch (e: JSONException) {
            AppLog.w(TAG, "parseShotSidecar: rejected, malformed JSON", e)
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "parseShotSidecar: rejected, unexpected error", e)
            null
        }
    }

    // ── Per-Session Export Parsing ────────────────────────────────────────────

    /**
     * Parse a per-session export JSON string into [MlExportSession].
     *
     * @param json The raw JSON string content of ml6d_export.json.
     * @return Parsed session export data, or null if parsing/validation fails.
     */
    fun parseExportSession(json: String): MlExportSession? {
        if (json.isBlank()) return null
        return try {
            val root = JSONObject(json)

            // Validate formatVersion
            val formatVersion = root.optInt("formatVersion", -1)
            if (formatVersion != CURRENT_FORMAT_VERSION) {
                AppLog.w(TAG, "parseExportSession: rejected, formatVersion=$formatVersion (expected $CURRENT_FORMAT_VERSION)")
                return null
            }

            val firmwareVersion = root.optString("firmwareVersion", "")
            if (firmwareVersion.isEmpty()) {
                AppLog.w(TAG, "parseExportSession: rejected, missing firmwareVersion field")
                return null
            }

            val body = root.optString("body", "")
            if (body.isEmpty()) {
                AppLog.w(TAG, "parseExportSession: rejected, missing body field")
                return null
            }

            // Parse sensor object
            val sensorObj = root.optJSONObject("sensor") ?: run {
                AppLog.w(TAG, "parseExportSession: rejected, missing sensor object")
                return null
            }
            val sensor = MlSensorInfo(
                pixelPitch = sensorObj.optDouble("pixelPitch", 0.0).toFloat(),
                cropFactor = sensorObj.optDouble("cropFactor", 1.0).toFloat(),
            )

            // Parse session object
            val sessionObj = root.optJSONObject("session") ?: run {
                AppLog.w(TAG, "parseExportSession: rejected, missing session object")
                return null
            }
            val session = MlSessionConfig(
                dualIsoEnabled = sessionObj.optBoolean("dualIsoEnabled", false),
                ettrEnabled = sessionObj.optBoolean("ettrEnabled", false),
                pictureStyle = sessionObj.optString("pictureStyle", "STANDARD"),
            )

            AppLog.i(TAG, "parseExportSession: accepted body=$body firmwareVersion=$firmwareVersion")
            MlExportSession(
                formatVersion = formatVersion,
                firmwareVersion = firmwareVersion,
                body = body,
                sensor = sensor,
                session = session,
            )
        } catch (e: JSONException) {
            AppLog.w(TAG, "parseExportSession: rejected, malformed JSON", e)
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "parseExportSession: rejected, unexpected error", e)
            null
        }
    }

    // ── Schema Validators (task 9.1) ─────────────────────────────────────────
    // Thin wrappers over the parsers above, which already perform full schema
    // validation (formatVersion, required fields, size cap) and return null
    // on any failure — these just name that check for unit-test readability.

    /** True if [json] is a well-formed, current-format per-shot sidecar. */
    fun isValidShotSidecar(json: String, expectedFilename: String? = null): Boolean =
        parseShotSidecar(json, expectedFilename) != null

    /** True if [json] is a well-formed, current-format per-session export. */
    fun isValidExportSession(json: String): Boolean =
        parseExportSession(json) != null

    // ── Sidecar Discovery ─────────────────────────────────────────────────────

    /**
     * Locate and parse the `.ml6d` sidecar for [cr2Uri], per Requirement 2.3:
     * first the same directory as the CR2 (`{filename}.ml6d`), then an
     * `ML/data/SHOTS/{filename}.ml6d` directory found by walking up from the
     * CR2's parent (the firmware writes `ML/data/` at the card root, while
     * CR2s typically live in a `DCIM/1xxCANON` subfolder). The `ML`/`data`/
     * `SHOTS` path segments are matched case-insensitively — the deployed
     * ML_6D firmware writes a lowercase `data` directory (confirmed against
     * a real card), which SAF `findFile()` would otherwise miss on an exact
     * case match.
     *
     * Returns null when no sidecar is found or it fails validation — callers
     * must fall back to EXIF-only processing (Requirement 8.4).
     */
    fun discover(context: Context, cr2Uri: Uri): MlShotSidecar? {
        val result = runCatching {
            when (cr2Uri.scheme) {
                "file" -> discoverFileScheme(cr2Uri)
                "content" -> discoverSafScheme(context, cr2Uri)
                else -> null
            }
        }.onFailure { e -> AppLog.e(TAG, "discover: threw for scheme=${cr2Uri.scheme}", e) }
            .getOrNull()
        AppLog.i(TAG, "discover: scheme=${cr2Uri.scheme} found=${result != null}")
        return result
    }

    /** Case-insensitive child lookup for [File] — mirrors [findChildCi] for the SAF path. */
    private fun File.findChildCi(name: String): File? =
        listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun discoverFileScheme(cr2Uri: Uri): MlShotSidecar? {
        val src = File(cr2Uri.path ?: return null)
        val cr2Name = src.name
        val basename = cr2Name.substringBeforeLast('.')
        val parent = src.parentFile ?: return null

        val sameDir = parent.findChildCi("$basename.ml6d")
        if (sameDir != null && sameDir.isFile) {
            if (sameDir.length() <= MAX_SIDECAR_SIZE_BYTES) {
                parseShotSidecar(sameDir.readText(), cr2Name)?.let {
                    AppLog.i(TAG, "discoverFileScheme: matched same-dir '${sameDir.path}'")
                    return it
                }
            } else {
                AppLog.w(TAG, "discoverFileScheme: '${sameDir.path}' exceeds $MAX_SIDECAR_SIZE_BYTES bytes, skipping")
            }
        }

        var dir: File? = parent
        repeat(4) {
            val shots = dir?.findChildCi("ML")?.findChildCi("data")?.findChildCi("SHOTS")?.findChildCi("$basename.ml6d")
            if (shots != null && shots.isFile) {
                if (shots.length() <= MAX_SIDECAR_SIZE_BYTES) {
                    parseShotSidecar(shots.readText(), cr2Name)?.let {
                        AppLog.i(TAG, "discoverFileScheme: matched '${shots.path}'")
                        return it
                    }
                } else {
                    AppLog.w(TAG, "discoverFileScheme: '${shots.path}' exceeds $MAX_SIDECAR_SIZE_BYTES bytes, skipping")
                }
            }
            dir = dir?.parentFile
        }
        AppLog.i(TAG, "discoverFileScheme: no sidecar found for '$cr2Name'")
        return null
    }

    /** Case-insensitive child lookup — the deployed ML_6D firmware writes a lowercase `data`
     *  directory, and SAF's [DocumentFile.findFile] only matches on exact name. */
    private fun DocumentFile.findChildCi(name: String): DocumentFile? =
        listFiles().firstOrNull { it.name?.equals(name, ignoreCase = true) == true }

    private fun discoverSafScheme(context: Context, cr2Uri: Uri): MlShotSidecar? {
        val sourceDoc = DocumentFile.fromSingleUri(context, cr2Uri) ?: return null
        val cr2Name = sourceDoc.name ?: return null
        val basename = cr2Name.substringBeforeLast('.')
        var parentDoc = sourceDoc.parentFile ?: return null

        parentDoc.findChildCi("$basename.ml6d")?.let { doc ->
            readSafSidecar(context, doc)?.let { text ->
                parseShotSidecar(text, cr2Name)?.let {
                    AppLog.i(TAG, "discoverSafScheme: matched same-dir sidecar for '$cr2Name'")
                    return it
                }
            }
        }

        var ancestor: DocumentFile? = parentDoc
        repeat(4) {
            val shotsDir = ancestor?.findChildCi("ML")?.findChildCi("data")?.findChildCi("SHOTS")
            val doc = shotsDir?.findChildCi("$basename.ml6d")
            if (doc != null) {
                readSafSidecar(context, doc)?.let { text ->
                    parseShotSidecar(text, cr2Name)?.let {
                        AppLog.i(TAG, "discoverSafScheme: matched ML/data/SHOTS sidecar for '$cr2Name'")
                        return it
                    }
                }
            }
            ancestor = ancestor?.parentFile
        }
        AppLog.i(TAG, "discoverSafScheme: no sidecar found for '$cr2Name'")
        return null
    }

    private fun readSafSidecar(context: Context, doc: DocumentFile): String? {
        if (doc.length() > MAX_SIDECAR_SIZE_BYTES) return null
        return runCatching {
            context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    // ── Block Parsers (internal) ─────────────────────────────────────────────

    /**
     * Parse the optional dualIso block from a sidecar JSONObject.
     * Returns null if the block is absent or has invalid/missing fields.
     */
    private fun parseDualIsoBlock(obj: JSONObject?): MlDualIsoBlock? {
        if (obj == null) return null
        return try {
            val enabled = obj.optBoolean("enabled", false)
            val isoBase = obj.optInt("isoBase", 0)
            val isoAlternate = obj.optInt("isoAlternate", 0)
            val interleavePeriod = obj.optInt("interleavePeriod", 2)

            // Basic validation: ISO values must be positive
            if (isoBase <= 0 || isoAlternate <= 0) {
                return null
            }

            MlDualIsoBlock(
                enabled = enabled,
                isoBase = isoBase,
                isoAlternate = isoAlternate,
                interleavePeriod = interleavePeriod,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse the optional ettr block from a sidecar JSONObject.
     * Returns null if the block is absent or has invalid/missing fields.
     */
    private fun parseEttrBlock(obj: JSONObject?): MlEttrBlock? {
        if (obj == null) return null
        return try {
            val lightLevel = obj.optInt("lightLevel", -1)
            val sceneDR = obj.optDouble("sceneDR", -1.0).toFloat()
            val highlightHeadroom = obj.optDouble("highlightHeadroom", -1.0).toFloat()

            // Parse channelClip array
            val clipArray = obj.optJSONArray("channelClip")
            if (clipArray == null || clipArray.length() != 3) {
                return null
            }
            val channelClip = FloatArray(3) { i ->
                clipArray.optDouble(i, 0.0).toFloat().coerceIn(0f, 1f)
            }

            // Basic validation
            if (lightLevel < 0 || lightLevel > 255) {
                return null
            }

            MlEttrBlock(
                lightLevel = lightLevel,
                sceneDR = sceneDR.coerceIn(4f, 14f),
                highlightHeadroom = highlightHeadroom.coerceIn(0f, 3f),
                channelClip = channelClip,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse the optional lens block from a sidecar JSONObject.
     * Returns null if the block is absent or has invalid/missing fields.
     */
    private fun parseLensBlock(obj: JSONObject?): MlLensBlock? {
        if (obj == null) return null
        return try {
            val id = obj.optInt("id", -1)
            if (id < 0) {
                return null
            }

            val caStrength = obj.optInt("caStrength", 0).coerceIn(0, 100)
            val fringeReduce = obj.optInt("fringeReduce", 0).coerceIn(0, 100)

            MlLensBlock(
                id = id,
                caStrength = caStrength,
                fringeReduce = fringeReduce,
            )
        } catch (e: Exception) {
            null
        }
    }
}
