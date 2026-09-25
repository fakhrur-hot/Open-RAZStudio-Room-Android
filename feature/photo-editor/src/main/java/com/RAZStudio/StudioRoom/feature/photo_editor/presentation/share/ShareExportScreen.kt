package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.share

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.core.resources.icons.CameraAlt
import com.RAZStudio.StudioRoom.core.resources.icons.IosShare
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.JpegRefineEngine
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.LensfunDatabase
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ShareFormat(val label: String, val ext: String, val mime: String) {
    Jpg("JPG", "jpg", "image/jpeg"),
    Png("PNG", "png", "image/png"),
    Webp("WEBP", "webp", "image/webp"),
}

private data class LensCal(
    val camMaker: String,
    val camModel: String,
    val lensModel: String,
    val focalMm: Float,
    val aperture: Float,
)

private data class ReadExif(
    val lines: List<String>,
    val orientation: Int,
    val tags: Map<String, String>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareExportScreen(
    uris: List<Uri>,
    onGoBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(ShareFormat.Jpg) }
    var strength by remember { mutableFloatStateOf(50f) }
    var clean by remember { mutableFloatStateOf(50f) }
    var detail by remember { mutableFloatStateOf(50f) }
    var flareBright by remember { mutableFloatStateOf(0f) }
    var flareDistance by remember { mutableFloatStateOf(100f) }
    var flareHood by remember { mutableFloatStateOf(0f) }
    var opticalAmount by remember { mutableFloatStateOf(0f) }
    var opticalHalation by remember { mutableFloatStateOf(0f) }
    var opticalDirection by remember { mutableFloatStateOf(0f) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var shot by remember { mutableStateOf<ReadExif?>(null) }
    var lens by remember { mutableStateOf<LensCal?>(null) }
    var showLensSheet by remember { mutableStateOf(false) }
    var exifLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val jpegSource = remember(uris) {
        uris.any { uri ->
            val mime = context.contentResolver.getType(uri).orEmpty()
            mime.contains("jpeg", true) || uri.toString().endsWith(".jpg", true) ||
                uri.toString().endsWith(".jpeg", true)
        }
    }

    LaunchedEffect(uris) {
        val first = uris.firstOrNull() ?: return@LaunchedEffect
        val read = withContext(Dispatchers.IO) { readExif(context, first) }
        exifLines = read.lines
        shot = read
        source = withContext(Dispatchers.IO) {
            decodeOriented(context, first, read.orientation, maxSide = 1600)
        }
    }

    LaunchedEffect(source, jpegSource, strength, clean, detail, lens, opticalAmount, opticalHalation, opticalDirection, flareBright, flareDistance, flareHood) {
        val base = source ?: return@LaunchedEffect
        val applied = lens
        preview = withContext(Dispatchers.Default) {
            val canvas = base.copy(Bitmap.Config.ARGB_8888, true) ?: return@withContext null
            if (applied != null) {
                val db = LensfunDatabase.ensureMaterialized(context)
                if (db != null) {
                    RawV3Engine.applyLensfunToBitmap(
                        bitmap = canvas,
                        camMaker = applied.camMaker,
                        camModel = applied.camModel,
                        lensMaker = "",
                        lensModel = applied.lensModel,
                        focalMm = applied.focalMm,
                        aperture = applied.aperture,
                        lensfunDbDir = db,
                    )
                }
            }
            if (jpegSource && strength > 0.5f) {
                RawV3Engine.applyJpegDualRecon(
                    canvas,
                    strength / 100f,
                    clean / 100f,
                    detail / 100f,
                )
            }
            if (flareBright > 0.5f) {
                RawV3Engine.applyLensFlare(
                    canvas, -0.5f, -0.5f,
                    flareBright / 100f, 1f, 1f, 0f,
                    flareDistance / 100f, flareHood / 100f,
                )
            }
            if (opticalAmount > 0.5f || opticalHalation > 0.5f) {
                RawV3Engine.applyOpticalSpread(
                    canvas,
                    opticalAmount / 100f,
                    opticalHalation / 100f,
                    opticalDirection,
                )
            }
            canvas
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.share)) },
                navigationIcon = {
                    IconButton(onClick = onGoBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    if (busy || uris.isEmpty()) return@Button
                    busy = true
                    scope.launch {
                        val files = withContext(Dispatchers.IO) {
                            uris.mapIndexedNotNull { index, uri ->
                                prepareShareFile(
                                    context = context,
                                    uri = uri,
                                    index = index,
                                    format = format,
                                    jpegRefine = jpegSource,
                                    lens = lens,
                                    strength = strength / 100f,
                                    clean = clean / 100f,
                                    detail = detail / 100f,
                                    opticalAmount = opticalAmount / 100f,
                                    opticalHalation = opticalHalation / 100f,
                                    opticalDirection = opticalDirection,
                                    flareBright = flareBright / 100f,
                                    flareDistance = flareDistance / 100f,
                                    flareHood = flareHood / 100f,
                                )
                            }
                        }
                        busy = false
                        if (files.isEmpty()) return@launch
                        val authority = context.getString(R.string.file_provider)
                        val grant = files.map {
                            FileProvider.getUriForFile(context, authority, it)
                        }
                        val send = if (grant.size == 1) {
                            Intent(Intent.ACTION_SEND).apply {
                                type = format.mime
                                putExtra(Intent.EXTRA_STREAM, grant.first())
                            }
                        } else {
                            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = format.mime
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(grant))
                            }
                        }
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, null))
                    }
                },
                enabled = uris.isNotEmpty() && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Icon(Icons.Rounded.IosShare, contentDescription = null)
                Text(
                    text = stringResource(R.string.share),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            preview?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            }
            IconButton(
                onClick = { showLensSheet = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Rounded.CameraAlt,
                    contentDescription = stringResource(R.string.gallery_lens_sheet_title),
                    modifier = Modifier.size(18.dp),
                    tint = if (lens != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (jpegSource) {
                Text(
                    stringResource(R.string.raw_section_jpeg_refine),
                    style = MaterialTheme.typography.titleSmall,
                )
                RefineSlider(stringResource(R.string.raw_jpeg_refine_strength), strength) { strength = it }
                RefineSlider(stringResource(R.string.raw_jpeg_refine_clean), clean) { clean = it }
                RefineSlider(stringResource(R.string.raw_jpeg_refine_detail), detail) { detail = it }
            }
            Text("Lens Flare", style = MaterialTheme.typography.titleSmall)
            RefineSlider("Intensity", flareBright) { flareBright = it }
            RefineSlider("Distance", flareDistance) { flareDistance = it }
            RefineSlider("Lens Hood", flareHood) { flareHood = it }
            Text(
                stringResource(R.string.raw_optical_spread),
                style = MaterialTheme.typography.titleSmall,
            )
            RefineSlider(stringResource(R.string.raw_optical_amount), opticalAmount) { opticalAmount = it }
            RefineSlider(stringResource(R.string.raw_optical_halation), opticalHalation) { opticalHalation = it }
            Text(
                "${stringResource(R.string.raw_optical_direction)} " + when (opticalDirection.toInt()) {
                    1 -> "Horizontal"
                    2 -> "Radial"
                    else -> "Off"
                },
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = opticalDirection,
                onValueChange = { opticalDirection = it },
                valueRange = 0f..2f,
                steps = 1,
            )
            if (exifLines.isNotEmpty()) {
                Text("EXIF", style = MaterialTheme.typography.titleSmall)
                exifLines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (showLensSheet) {
        ShareLensSheet(
            shot = shot,
            onDismiss = { showLensSheet = false },
            onApply = { chosen ->
                lens = chosen
                showLensSheet = false
            },
        )
    }
}

@Composable
private fun RefineSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Text("$label ${value.toInt()}", style = MaterialTheme.typography.labelMedium)
    Slider(value = value, onValueChange = onChange, valueRange = 0f..100f)
}

private val copiedTags = listOf(
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    "LensModel",
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_ISO_SPEED_RATINGS,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_ARTIST,
    ExifInterface.TAG_COPYRIGHT,
)

private fun readExif(context: android.content.Context, uri: Uri): ReadExif {
    val tags = mutableMapOf<String, String>()
    val orientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            copiedTags.forEach { key ->
                exif.getAttribute(key)?.let { tags[key] = it }
            }
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    val lines = buildList {
        tags[ExifInterface.TAG_MAKE]?.let { add(it) }
        tags[ExifInterface.TAG_MODEL]?.let { add(it) }
        tags["LensModel"]?.let { add(it) }
        tags[ExifInterface.TAG_DATETIME_ORIGINAL]?.let { add(it) }
        tags[ExifInterface.TAG_ISO_SPEED_RATINGS]?.let { add("ISO $it") }
        tags[ExifInterface.TAG_F_NUMBER]?.let { add("f/$it") }
        tags[ExifInterface.TAG_EXPOSURE_TIME]?.let { add("${it}s") }
        tags[ExifInterface.TAG_FOCAL_LENGTH]?.let { add("${it}mm") }
    }
    return ReadExif(lines, orientation, tags)
}

private fun decodeOriented(
    context: android.content.Context,
    uri: Uri,
    orientation: Int,
    maxSide: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: return null
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return decoded
    val rotated = Bitmap.createBitmap(
        decoded, 0, 0, decoded.width, decoded.height,
        Matrix().apply { postRotate(degrees) }, true,
    )
    if (rotated !== decoded) decoded.recycle()
    return rotated
}

private fun prepareShareFile(
    context: android.content.Context,
    uri: Uri,
    index: Int,
    format: ShareFormat,
    jpegRefine: Boolean,
    lens: LensCal?,
    strength: Float,
    clean: Float,
    detail: Float,
    opticalAmount: Float,
    opticalHalation: Float,
    opticalDirection: Float,
    flareBright: Float,
    flareDistance: Float,
    flareHood: Float,
): File? {
    val exif = readExif(context, uri)
    val bitmap = decodeOriented(context, uri, exif.orientation, maxSide = 8192) ?: return null
    val mutable = if (bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) bitmap
    else bitmap.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== bitmap) bitmap.recycle() }
    if (lens != null) {
        val db = LensfunDatabase.ensureMaterialized(context)
        if (db != null) {
            RawV3Engine.applyLensfunToBitmap(
                bitmap = mutable,
                camMaker = lens.camMaker,
                camModel = lens.camModel,
                lensMaker = "",
                lensModel = lens.lensModel,
                focalMm = lens.focalMm,
                aperture = lens.aperture,
                lensfunDbDir = db,
            )
        }
    }
    if (jpegRefine && strength > 0.001f) {
        JpegRefineEngine(context).refineExport(mutable, strength, uri.toString())
        RawV3Engine.applyJpegDualRecon(mutable, strength, clean, detail)
    }
    if (flareBright > 0.001f) {
        RawV3Engine.applyLensFlare(
            mutable, -0.5f, -0.5f, flareBright, 1f, 1f, 0f, flareDistance, flareHood,
        )
    }
    if (opticalAmount > 0.001f || opticalHalation > 0.001f) {
        RawV3Engine.applyOpticalSpread(mutable, opticalAmount, opticalHalation, opticalDirection)
    }
    val dir = File(context.cacheDir, "share_export").apply { mkdirs() }
    val file = File(dir, "share_${index}_${System.currentTimeMillis()}.${format.ext}")
    file.outputStream().use { out ->
        val compress = when (format) {
            ShareFormat.Jpg -> Bitmap.CompressFormat.JPEG
            ShareFormat.Png -> Bitmap.CompressFormat.PNG
            ShareFormat.Webp -> Bitmap.CompressFormat.WEBP
        }
        val quality = if (format == ShareFormat.Png) 100 else 95
        mutable.compress(compress, quality, out)
    }
    mutable.recycle()
    if (format == ShareFormat.Jpg && exif.tags.isNotEmpty()) {
        runCatching {
            val outExif = ExifInterface(file.absolutePath)
            exif.tags.forEach { (key, value) -> outExif.setAttribute(key, value) }
            outExif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString(),
            )
            outExif.saveAttributes()
        }
    }
    return file
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareLensSheet(
    shot: ReadExif?,
    onDismiss: () -> Unit,
    onApply: (LensCal) -> Unit,
) {
    val context = LocalContext.current
    val tags = shot?.tags.orEmpty()
    val maker = tags[ExifInterface.TAG_MAKE].orEmpty()
    val aperture = rationalText(tags[ExifInterface.TAG_F_NUMBER]).toFloatOrNull() ?: 0f
    var camera by remember { mutableStateOf(tags[ExifInterface.TAG_MODEL].orEmpty()) }
    var lensModel by remember { mutableStateOf(tags["LensModel"].orEmpty()) }
    var cameras by remember { mutableStateOf<List<String>>(emptyList()) }
    var lenses by remember { mutableStateOf<List<RawV3Engine.LensfunLens>>(emptyList()) }
    var zoomMm by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dir = LensfunDatabase.ensureMaterialized(context) ?: return@withContext
            cameras = RawV3Engine.lensfunCameras(dir).map { it.model }.distinct()
            lenses = RawV3Engine.lensfunLenses(dir)
        }
    }
    val picked = lenses.firstOrNull { it.model == lensModel }
    val zoom = picked != null && picked.maxFocal > picked.minFocal + 0.5f
    LaunchedEffect(picked?.model) {
        if (picked != null && zoomMm !in picked.minFocal..picked.maxFocal) {
            zoomMm = picked.minFocal
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.gallery_lens_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            ProfileSuggestField(
                label = stringResource(R.string.gallery_lens_camera),
                value = camera,
                options = cameras,
                onValueChange = { camera = it },
            )
            ProfileSuggestField(
                label = stringResource(R.string.gallery_lens_lens),
                value = lensModel,
                options = lenses.map { it.model }.distinct(),
                onValueChange = { lensModel = it },
            )
            if (zoom && picked != null) {
                Text(
                    "${zoomMm.toInt()} mm",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = zoomMm.coerceIn(picked.minFocal, picked.maxFocal),
                    onValueChange = { zoomMm = it },
                    valueRange = picked.minFocal..picked.maxFocal,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.raw_cancel)) }
                Button(
                    onClick = {
                        val focalMm = when {
                            zoom -> zoomMm
                            picked != null && picked.minFocal > 0f -> picked.minFocal
                            else -> rationalText(tags[ExifInterface.TAG_FOCAL_LENGTH]).toFloatOrNull() ?: 0f
                        }
                        onApply(
                            LensCal(
                                camMaker = maker,
                                camModel = camera,
                                lensModel = lensModel,
                                focalMm = focalMm,
                                aperture = aperture,
                            )
                        )
                    },
                    enabled = camera.isNotBlank() && lensModel.isNotBlank(),
                ) {
                    Text(stringResource(R.string.gallery_lens_apply))
                }
            }
        }
    }
}

@Composable
private fun ProfileSuggestField(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val q = value.lowercase().filter { it.isLetterOrDigit() }
    if (q.length >= 3 && options.none { it == value }) {
        options.asSequence()
            .filter { it.lowercase().filter { c -> c.isLetterOrDigit() }.contains(q) }
            .distinct()
            .take(8)
            .forEach { hit ->
                TextButton(onClick = { onValueChange(hit) }) {
                    Text(hit, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
    }
}

private fun rationalText(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    if (!raw.contains("/")) return raw
    val parts = raw.split("/")
    val num = parts.getOrNull(0)?.toFloatOrNull() ?: return ""
    val den = parts.getOrNull(1)?.toFloatOrNull() ?: return num.toString()
    if (den == 0f) return num.toString()
    return (num / den).toString()
}
