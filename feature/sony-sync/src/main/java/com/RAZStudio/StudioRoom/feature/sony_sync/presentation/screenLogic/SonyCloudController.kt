/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.sony_sync.presentation.screenLogic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.RAZStudio.StudioRoom.feature.sony_sync.data.cloud.BroughtLens
import com.RAZStudio.StudioRoom.feature.sony_sync.data.cloud.CloudPrefs
import com.RAZStudio.StudioRoom.feature.sony_sync.data.cloud.CloudResult
import com.RAZStudio.StudioRoom.feature.sony_sync.data.cloud.GoogleDriveProvider
import com.RAZStudio.StudioRoom.feature.sony_sync.data.cloud.decodeLensKit
import com.RAZStudio.StudioRoom.feature.sony_sync.data.cloud.emptyLensKit
import com.RAZStudio.StudioRoom.feature.sony_sync.data.cloud.encodeLensKit
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.MacroProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawPresetsStorage
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.LensfunDatabase
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3ActionReplay
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.LensDatabase
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.burnCombinedWatermarkOnto
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.listWatermarkPresets
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.loadWatermarkPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sony Sync cloud live-upload orchestration. Owns the [CloudProvider] (Google
 * Drive first) and the persisted setup ([CloudPrefs]); exposes the settings
 * actions (connect / create folder / make public → link) and the live-upload
 * loop (Start/Stop). Each captured JPEG handed to [uploadCaptured] is downsized
 * to ~2 MP and pushed to the shared folder while [liveActive].
 */
class SonyCloudController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
) {
    private val prefs = CloudPrefs(context)
    private val provider = GoogleDriveProvider().apply { setAccessToken(prefs.accessToken) }

    private val _connected = MutableStateFlow(provider.isConnected())
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _account = MutableStateFlow("")
    val account: StateFlow<String> = _account.asStateFlow()

    private val _folderName = MutableStateFlow(prefs.folderName)
    val folderName: StateFlow<String> = _folderName.asStateFlow()

    private val _shareLink = MutableStateFlow(prefs.shareLink)
    val shareLink: StateFlow<String> = _shareLink.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _liveActive = MutableStateFlow(false)
    val liveActive: StateFlow<Boolean> = _liveActive.asStateFlow()

    private val _uploaded = MutableStateFlow(0)
    val uploaded: StateFlow<Int> = _uploaded.asStateFlow()

    // Selected RAZBatch options, applied to each upload.
    // Restored from CloudPrefs on construction; persisted immediately on every change.
    val presetName = MutableStateFlow<String?>(prefs.cloudPresetName.ifEmpty { null })
    val watermarkName = MutableStateFlow<String?>(prefs.cloudWatermarkName.ifEmpty { null })

    // "Lenses you brought" kit — 10 rows, persisted per cloud folder. Loaded
    // for the currently-registered folder; reloaded when the folder changes.
    private val _lensKit = MutableStateFlow(decodeLensKit(prefs.lensKit(prefs.folderId)))
    val lensKit: StateFlow<List<BroughtLens>> = _lensKit.asStateFlow()

    init {
        // Persist preset + watermark selections whenever the UI changes them.
        scope.launch(io) {
            presetName.collect { prefs.cloudPresetName = it ?: "" }
        }
        scope.launch(io) {
            watermarkName.collect { prefs.cloudWatermarkName = it ?: "" }
        }
    }

    /** Reload the kit for the active folder (call after the folder changes). */
    fun reloadLensKit() { _lensKit.value = decodeLensKit(prefs.lensKit(prefs.folderId)) }

    /** Update one row (enforcing at most one Manual) and persist to the folder. */
    fun updateLens(index: Int, row: BroughtLens) {
        val cur = _lensKit.value.toMutableList()
        if (index !in cur.indices) return
        if (row.manual) for (i in cur.indices) if (i != index) cur[i] = cur[i].copy(manual = false)
        cur[index] = row
        _lensKit.value = cur
        prefs.setLensKit(prefs.folderId, cur.encodeLensKit())
    }

    // Lensfun profile corpus for the profile-field autocomplete (loaded lazily,
    // off the main thread by the caller). Model strings already carry the brand.
    private val lensfunDir: String? by lazy {
        runCatching { LensfunDatabase.ensureMaterialized(context) }.getOrNull()
    }
    private val allLensProfiles: List<String> by lazy {
        val dir = lensfunDir ?: return@lazy emptyList()
        runCatching {
            RawV3Engine.lensfunLenses(dir)
                .sortedBy { LensfunDatabase.canonicalBrand(it.maker) + " " + it.model }
                .map { it.model }
                .distinct()
        }.getOrDefault(emptyList())
    }

    /** Lensfun profile suggestions for a typed query (e.g. "24-70" → all brands). */
    fun lensProfileSuggestions(query: String, limit: Int = 12): List<String> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return allLensProfiles.asSequence()
            .filter { it.contains(q, ignoreCase = true) }
            .take(limit).toList()
    }

    /** Display-name suggestions (watermark lens DB) for a typed query. */
    fun lensNameSuggestions(query: String, limit: Int = 12): List<String> =
        runCatching { LensDatabase.search(context, query, limit) }.getOrDefault(emptyList())

    /** Saved RAW preset names (for the selector). */
    fun presetNames(): List<String> =
        runCatching { RawPresetsStorage.loadIndex(context).map { it.name } }.getOrDefault(emptyList())

    /** Saved watermark preset names (for the selector). */
    fun watermarkNames(): List<String> =
        runCatching { listWatermarkPresets(context).map { it.name } }.getOrDefault(emptyList())

    val providerName: String get() = provider.displayName
    val hasFolder: Boolean get() = prefs.folderId.isNotBlank()
    val currentLink: String get() = prefs.shareLink

    fun connect(token: String) {
        scope.launch(io) {
            provider.setAccessToken(token); prefs.accessToken = token
            when (val r = provider.verify()) {
                is CloudResult.Ok -> { _connected.value = true; _account.value = r.value; _status.value = "Connected as ${r.value}" }
                is CloudResult.Err -> { _connected.value = false; _status.value = r.message }
            }
        }
    }

    fun createFolder(name: String) {
        scope.launch(io) {
            _status.value = "Creating folder…"
            when (val r = provider.createFolder(name.ifBlank { "RAZStudio Uploads" })) {
                is CloudResult.Ok -> {
                    prefs.folderId = r.value.id; prefs.folderName = r.value.name
                    _folderName.value = r.value.name; prefs.shareLink = ""; _shareLink.value = ""
                    reloadLensKit()
                    _status.value = "Folder '${r.value.name}' created. Tap 'Make public' to get a link."
                }
                is CloudResult.Err -> _status.value = r.message
            }
        }
    }

    fun makePublic() {
        val id = prefs.folderId
        if (id.isBlank()) { _status.value = "Create a folder first."; return }
        scope.launch(io) {
            _status.value = "Sharing folder…"
            when (val r = provider.makeFolderPublic(id)) {
                is CloudResult.Ok -> { prefs.shareLink = r.value; _shareLink.value = r.value; _status.value = "Public link ready." }
                is CloudResult.Err -> _status.value = r.message
            }
        }
    }

    /** Use a folder the user already created outside the app, given its share link. */
    fun useExistingFolder(link: String) {
        scope.launch(io) {
            val id = provider.parseFolderId(link)
            if (id == null) { _status.value = "That doesn't look like a Google Drive folder link."; return@launch }
            _status.value = "Checking link…"
            when (val r = provider.checkFolder(id)) {
                is CloudResult.Ok -> {
                    val info = r.value
                    when {
                        !info.isFolder -> _status.value = "That link points to a file, not a folder."
                        !info.canWrite -> _status.value =
                            "'${info.name}' is read-only for this account — no write permission."
                        else -> {
                            prefs.folderId = id
                            prefs.folderName = info.name.ifBlank { "Shared folder" }
                            prefs.shareLink = if (link.contains("/folders/")) link
                                else "https://drive.google.com/drive/folders/$id?usp=sharing"
                            _folderName.value = prefs.folderName; _shareLink.value = prefs.shareLink
                            reloadLensKit()
                            _status.value = "Using '${info.name}' — read + write OK."
                        }
                    }
                }
                is CloudResult.Err -> _status.value = folderErrHint(r.message)
            }
        }
    }

    fun stop() { _liveActive.value = false; _status.value = "Live upload stopped." }

    /**
     * Start requested from the UI: quick-verify the saved folder (exists, is a
     * folder, writable) BEFORE going live. On any problem, surface it instead of
     * silently starting.
     */
    fun requestStart() {
        val id = prefs.folderId
        if (id.isBlank()) { _status.value = "Set up a shared folder first (Cloud Settings)."; return }
        _status.value = "Checking folder before start…"
        scope.launch(io) {
            when (val r = provider.checkFolder(id)) {
                is CloudResult.Ok -> {
                    val info = r.value
                    when {
                        !info.isFolder -> _status.value = "The saved folder isn't a folder any more — re-pick it in Cloud Settings."
                        !info.canWrite -> _status.value = "No write permission on '${info.name}'. Fix it in Cloud Settings."
                        else -> { _uploaded.value = 0; _liveActive.value = true; _status.value = "Live upload started." }
                    }
                }
                is CloudResult.Err -> _status.value = "Can't start — ${folderErrHint(r.message)}"
            }
        }
    }

    private fun folderErrHint(msg: String): String =
        msg + if (msg.contains("404") || msg.contains("403"))
            " — this account can't access the folder. Paste a token with full Drive scope, " +
                "or share the folder with the signed-in Google account." else ""

    /**
     * Hand a freshly captured JPEG to the cloud. Applies the selected RAZBatch
     * preset (tonal macro) + watermark, then downsizes to ~2 MP before upload.
     * No-op unless live. Heavy work stays on [io].
     */
    fun uploadCaptured(name: String, jpeg: ByteArray) {
        if (!_liveActive.value || !hasFolder) return
        scope.launch(io) {
            val out = processJpeg(jpeg) ?: jpeg
            when (val r = provider.uploadJpeg(prefs.folderId, name, out)) {
                is CloudResult.Ok -> { _uploaded.update(); _status.value = "Uploaded $name (${_uploaded.value})" }
                is CloudResult.Err -> _status.value = "Upload failed: ${r.message}"
            }
        }
    }

    private fun MutableStateFlow<Int>.update() { value += 1 }

    /**
     * Read EXIF → match a brought lens → lensfun-correct → preset → watermark →
     * scale ~2 MP → JPEG q85, then overwrite the EXIF lens name on the output.
     */
    private suspend fun processJpeg(jpeg: ByteArray): ByteArray? = withContext(io) {
        runCatching {
            // 0) EXIF + brought-lens match (before any pixel op).
            val ex = readExif(jpeg)
            val lens = matchLens(_lensKit.value, ex)
            val lensName = lens?.name?.ifBlank { null }

            // Decode mutable so the native lensfun kernel can lock it in place.
            var bmp: Bitmap = BitmapFactory.decodeByteArray(
                jpeg, 0, jpeg.size,
                BitmapFactory.Options().apply { inMutable = true },
            ) ?: return@runCatching null

            // 1) Lens correction — geometry first, using the matched profile at the
            //    photo's capture focal length. No-op (returns false) if no match.
            if (lens != null && lens.profile.isNotBlank()) {
                runCatching {
                    val dir = lensfunDir
                    if (dir != null) {
                        if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
                        val ok = RawV3Engine.applyLensfunToBitmap(
                            bmp,
                            camMaker = ex.make, camModel = ex.model,
                            lensMaker = "", lensModel = lens.profile,
                            focalMm = if (ex.focalMm > 0f) ex.focalMm else ex.focalMin,
                            aperture = ex.aperture,
                            lensfunDbDir = dir,
                        )
                        Log.i(TAG, "lensfun '${lens.profile}' @${ex.focalMm}mm applied=$ok")
                    }
                }.onFailure { Log.w(TAG, "lensfun correction failed: ${it.message}") }
            }

            // 2) Preset — fold the saved actions to a UserMacro and apply.
            presetName.value?.let { pn ->
                runCatching {
                    val idx = RawPresetsStorage.loadIndex(context).indexOfFirst { it.name == pn }
                    if (idx >= 0) RawPresetsStorage.loadPreset(context, idx)?.let { actions ->
                        val macro = RawV3ActionReplay.composeMacro(actions)
                        bmp = MacroProcessor.apply(bmp, macro)
                    }
                }.onFailure { Log.w(TAG, "preset apply failed: ${it.message}") }
            }

            // 3) Watermark — burn the selected preset. When the preset carries an
            //    EXIF strip, feed it the incoming EXIF and override the lens name
            //    with the brought-lens name so the strip reads correctly (the
            //    decoded bitmap itself carries no EXIF).
            watermarkName.value?.let { wn ->
                runCatching {
                    loadWatermarkPreset(context, wn)?.let { base ->
                        val cfg = base.exif?.let { e ->
                            base.copy(exif = e.copy(
                                make = ex.make.ifBlank { e.make },
                                model = ex.model.ifBlank { e.model },
                                lensModel = lensName ?: ex.lensModel.ifBlank { e.lensModel },
                                iso = if (ex.iso > 0) ex.iso else e.iso,
                                shutterSpeed = if (ex.shutter > 0f) ex.shutter else e.shutterSpeed,
                                aperture = if (ex.aperture > 0f) ex.aperture else e.aperture,
                                focalMm = if (ex.focalMm > 0f) ex.focalMm else e.focalMm,
                                dateTimeOriginal = ex.dateTime.ifBlank { e.dateTimeOriginal },
                            ))
                        } ?: base
                        bmp = burnCombinedWatermarkOnto(bmp, cfg, context)
                    }
                }.onFailure { Log.w(TAG, "watermark apply failed: ${it.message}") }
            }

            // 4) Downscale to ~2 MP and re-encode.
            val targetMp = 2_000_000.0
            val mp = bmp.width.toLong() * bmp.height
            if (mp > targetMp) {
                val scale = sqrt(targetMp / mp)
                bmp = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1), true,
                )
            }
            val out = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }

            // 5) Overwrite the EXIF lens name on the output (naming only).
            if (lensName != null) writeLensExif(out, lensName) else out
        }.getOrNull()
    }

    // ── EXIF read + brought-lens matching ────────────────────────────────────

    private data class ExifLens(
        val make: String, val model: String, val lensModel: String,
        val focalMin: Float, val focalMax: Float,
        val focalMm: Float, val aperture: Float, val iso: Int,
        val shutter: Float, val dateTime: String, val hasLens: Boolean,
    )

    private fun readExif(jpeg: ByteArray): ExifLens = runCatching {
        val e = ExifInterface(ByteArrayInputStream(jpeg))
        val make = e.getAttribute(ExifInterface.TAG_MAKE).orEmpty().trim()
        val model = e.getAttribute(ExifInterface.TAG_MODEL).orEmpty().trim()
        val lensModel = e.getAttribute(ExifInterface.TAG_LENS_MODEL).orEmpty().trim()
        val aperture = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).toFloat()
        val focalMm = e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).toFloat()
        val iso = e.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
        val shutter = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).toFloat()
        val dt = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).orEmpty()
        // LensSpecification = "minFocal maxFocal minAp maxAp" as rationals.
        var fMin = 0f; var fMax = 0f
        e.getAttribute(ExifInterface.TAG_LENS_SPECIFICATION)?.let { spec ->
            val nums = spec.trim().split(Regex("\\s+")).mapNotNull { parseRational(it) }
            if (nums.size >= 2) { fMin = nums[0]; fMax = nums[1] }
        }
        if (fMin <= 0f && lensModel.isNotBlank()) {
            parseFocalRange(lensModel)?.let { (a, b) -> fMin = a; fMax = b }
        }
        // "No lens info" = fully manual adapted lens → no aperture, no model/spec.
        val hasLens = aperture > 0f || lensModel.isNotBlank() || fMin > 0f
        ExifLens(make, model, lensModel, fMin, fMax, focalMm, aperture, iso, shutter, dt, hasLens)
    }.getOrDefault(ExifLens("", "", "", 0f, 0f, 0f, 0f, 0, 0f, "", false))

    /**
     * Map an incoming photo to one enabled brought-lens row. No lens EXIF → the
     * single Manual row. Otherwise match the lens's focal-range designation
     * (LensSpecification, else parsed from LensModel) to a row's profile range.
     * No confident match → null (skip correction, keep the original).
     */
    private fun matchLens(kit: List<BroughtLens>, ex: ExifLens): BroughtLens? {
        val enabled = kit.filter { it.enabled }
        if (enabled.isEmpty()) return null
        if (!ex.hasLens) return enabled.firstOrNull { it.manual }
        val exMin = if (ex.focalMin > 0f) ex.focalMin else ex.focalMm
        val exMax = if (ex.focalMax > 0f) ex.focalMax else ex.focalMm
        if (exMin <= 0f) return null
        for (row in enabled) {
            if (row.manual) continue
            val rr = parseFocalRange(row.profile) ?: parseFocalRange(row.name) ?: continue
            if (approxMm(rr.first, exMin) && approxMm(rr.second, exMax)) return row
        }
        return null
    }

    private fun approxMm(a: Float, b: Float) = abs(a - b) <= 2f

    /** Parse a rational like "24/1" or a plain "24" → Float. */
    private fun parseRational(s: String): Float? {
        val t = s.trim()
        return if ('/' in t) {
            val p = t.split('/'); val n = p.getOrNull(0)?.toFloatOrNull(); val d = p.getOrNull(1)?.toFloatOrNull()
            if (n != null && d != null && d != 0f) n / d else null
        } else t.toFloatOrNull()
    }

    /** Extract a focal range from a lens name: "24-70mm" → (24,70); "50mm" → (50,50). */
    private fun parseFocalRange(s: String): Pair<Float, Float>? {
        val m = Regex("(\\d{1,4})\\s*-\\s*(\\d{1,4})\\s*mm", RegexOption.IGNORE_CASE).find(s)
        if (m != null) {
            val a = m.groupValues[1].toFloatOrNull(); val b = m.groupValues[2].toFloatOrNull()
            if (a != null && b != null) return a to b
        }
        val p = Regex("(\\d{1,4})\\s*mm", RegexOption.IGNORE_CASE).find(s)
        val v = p?.groupValues?.get(1)?.toFloatOrNull()
        return if (v != null) v to v else null
    }

    /** Rewrite the EXIF LensModel + Software on JPEG bytes (via a temp file). */
    private fun writeLensExif(jpeg: ByteArray, lensName: String): ByteArray = runCatching {
        val tmp = File(context.cacheDir, "cloud_exif_${System.nanoTime()}.jpg")
        tmp.writeBytes(jpeg)
        val e = ExifInterface(tmp.absolutePath)
        e.setAttribute(ExifInterface.TAG_LENS_MODEL, lensName)
        e.setAttribute(ExifInterface.TAG_SOFTWARE, "RAZStudio Room")
        e.saveAttributes()
        val result = tmp.readBytes()
        tmp.delete()
        result
    }.getOrDefault(jpeg)

    companion object { private const val TAG = "SonyCloud" }
}
