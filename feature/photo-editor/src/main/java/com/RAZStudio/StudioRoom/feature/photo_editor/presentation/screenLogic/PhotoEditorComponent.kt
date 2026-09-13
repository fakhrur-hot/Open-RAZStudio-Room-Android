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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.screenLogic

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.RAZStudio.cropper.model.AspectRatio
import com.RAZStudio.cropper.model.OutlineType
import com.RAZStudio.cropper.model.RectCropShape
import com.RAZStudio.cropper.settings.CropDefaults
import com.RAZStudio.cropper.settings.CropOutlineProperty
import com.RAZStudio.curves.ImageCurvesEditorState
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.domain.image.ImageCompressor
import com.RAZStudio.StudioRoom.core.domain.image.ImageGetter
import com.RAZStudio.StudioRoom.core.domain.image.ImagePreviewCreator
import com.RAZStudio.StudioRoom.core.domain.image.ImageScaler
import com.RAZStudio.StudioRoom.core.domain.image.ImageShareProvider
import com.RAZStudio.StudioRoom.core.domain.image.ImageTransformer
import com.RAZStudio.StudioRoom.core.domain.image.Metadata
import com.RAZStudio.StudioRoom.core.domain.image.clearAllAttributes
import com.RAZStudio.StudioRoom.core.domain.image.clearAttribute
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageData
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageFormat
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageInfo
import com.RAZStudio.StudioRoom.core.domain.image.model.ImageScaleMode
import com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag
import com.RAZStudio.StudioRoom.core.domain.image.model.Preset
import com.RAZStudio.StudioRoom.core.domain.image.model.Quality
import com.RAZStudio.StudioRoom.core.domain.image.model.ResizeSharpen
import com.RAZStudio.StudioRoom.core.domain.image.model.ResizeType
import com.RAZStudio.StudioRoom.core.domain.model.DomainAspectRatio
import com.RAZStudio.StudioRoom.core.domain.model.IntegerSize
import com.RAZStudio.StudioRoom.core.domain.saving.FileController
import com.RAZStudio.StudioRoom.core.domain.saving.model.ImageSaveTarget
import com.RAZStudio.StudioRoom.core.domain.transformation.Transformation
import com.RAZStudio.StudioRoom.core.domain.utils.smartJob
import com.RAZStudio.StudioRoom.core.domain.utils.update
import com.RAZStudio.StudioRoom.core.filters.domain.FilterProvider
import com.RAZStudio.StudioRoom.core.filters.domain.model.Filter
import com.RAZStudio.StudioRoom.core.filters.presentation.model.UiFilter
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.FilterTemplateCreationSheetComponent
import com.RAZStudio.StudioRoom.core.filters.presentation.widget.addFilters.AddFiltersSheetComponent
import com.RAZStudio.StudioRoom.core.settings.domain.SettingsProvider
import com.RAZStudio.StudioRoom.core.ui.utils.BaseComponent
import com.RAZStudio.StudioRoom.core.ui.utils.helper.AppToastHost
import com.RAZStudio.StudioRoom.core.ui.utils.helper.ImageUtils.safeAspectRatio
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.core.ui.utils.state.savable
import com.RAZStudio.StudioRoom.core.ui.utils.state.update
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.HelperGridParams
import com.RAZStudio.StudioRoom.feature.draw.domain.DrawLineStyle
import com.RAZStudio.StudioRoom.feature.draw.domain.DrawMode
import com.RAZStudio.StudioRoom.feature.draw.domain.DrawPathMode
import com.RAZStudio.StudioRoom.feature.draw.presentation.components.UiPathPaint
import com.RAZStudio.StudioRoom.feature.erase_background.domain.AutoBackgroundRemover
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.LibRawJniBridge
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBitmapConverter
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.toMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class PhotoEditorComponent @AssistedInject internal constructor(
    @Assisted componentContext: ComponentContext,
    @Assisted val initialUri: Uri?,
    @Assisted val fromRawEditor: Boolean,
    @Assisted val onGoBack: () -> Unit,
    @Assisted val onNavigate: (Screen) -> Unit,
    @ApplicationContext private val appContext: Context,
    private val fileController: FileController,
    private val imageTransformer: ImageTransformer<Bitmap>,
    private val imagePreviewCreator: ImagePreviewCreator<Bitmap>,
    private val imageCompressor: ImageCompressor<Bitmap>,
    private val imageGetter: ImageGetter<Bitmap>,
    private val imageScaler: ImageScaler<Bitmap>,
    private val autoBackgroundRemover: AutoBackgroundRemover<Bitmap>,
    private val shareProvider: ImageShareProvider<Bitmap>,
    private val filterProvider: FilterProvider<Bitmap>,
    private val settingsProvider: SettingsProvider,
    dispatchersHolder: DispatchersHolder,
    addFiltersSheetComponentFactory: AddFiltersSheetComponent.Factory,
    filterTemplateCreationSheetComponentFactory: FilterTemplateCreationSheetComponent.Factory,
) : BaseComponent(dispatchersHolder, componentContext) {

    init {
        debounce {
            initialUri?.let(::setUri)
        }

        doOnDestroy {
            autoBackgroundRemover.cleanup()
        }
    }

    val addFiltersSheetComponent: AddFiltersSheetComponent = addFiltersSheetComponentFactory(
        componentContext = componentContext.childContext(
            key = "addFiltersSingle"
        )
    )

    val filterTemplateCreationSheetComponent: FilterTemplateCreationSheetComponent =
        filterTemplateCreationSheetComponentFactory(
            componentContext = componentContext.childContext(
                key = "filterTemplateCreationSheetComponentSingle"
            )
        )

    private val _originalSize: MutableState<IntegerSize?> = mutableStateOf(null)
    val originalSize by _originalSize

    // Cached absolute path of the full-res export file rendered on first save from the RAW editor.
    // Reused on subsequent saves so the heavy MacroProcessor pass only runs once.
    @Volatile private var rawFullResPath: String? = null

    private val _erasePaths = mutableStateOf(listOf<UiPathPaint>())
    val erasePaths: List<UiPathPaint> by _erasePaths

    private val _eraseLastPaths = mutableStateOf(listOf<UiPathPaint>())
    val eraseLastPaths: List<UiPathPaint> by _eraseLastPaths

    private val _eraseUndonePaths = mutableStateOf(listOf<UiPathPaint>())
    val eraseUndonePaths: List<UiPathPaint> by _eraseUndonePaths

    private val _drawPaths = mutableStateOf(listOf<UiPathPaint>())
    val drawPaths: List<UiPathPaint> by _drawPaths

    private val _drawLastPaths = mutableStateOf(listOf<UiPathPaint>())
    val drawLastPaths: List<UiPathPaint> by _drawLastPaths

    private val _drawUndonePaths = mutableStateOf(listOf<UiPathPaint>())
    val drawUndonePaths: List<UiPathPaint> by _drawUndonePaths

    private val _filterList =
        mutableStateOf(listOf<UiFilter<*>>())
    val filterList by _filterList

    private val _selectedAspectRatio: MutableState<DomainAspectRatio> =
        mutableStateOf(DomainAspectRatio.Free)
    val selectedAspectRatio by _selectedAspectRatio

    private val _cropProperties = mutableStateOf(
        CropDefaults.properties(
            cropOutlineProperty = CropOutlineProperty(
                outlineType = OutlineType.Rect,
                cropOutline = RectCropShape(
                    id = 0,
                    title = OutlineType.Rect.name
                )
            ),
            fling = true
        )
    )
    val cropProperties by _cropProperties

    private val _imageCurvesEditorState: MutableState<ImageCurvesEditorState> =
        mutableStateOf(ImageCurvesEditorState.Default)
    val imageCurvesEditorState: ImageCurvesEditorState by _imageCurvesEditorState

    private val _exif: MutableState<Metadata?> = mutableStateOf(null)
    val exif by _exif

    private val _uri: MutableState<Uri> = mutableStateOf(Uri.EMPTY)
    val uri: Uri by _uri

    private val _internalBitmap: MutableState<Bitmap?> = mutableStateOf(null)
    val initialBitmap by _internalBitmap

    private val _bitmap: MutableState<Bitmap?> = mutableStateOf(null)
    val bitmap: Bitmap? by _bitmap

    private val _previewBitmap: MutableState<Bitmap?> = mutableStateOf(null)
    val previewBitmap: Bitmap? by _previewBitmap

    private val _imageInfo: MutableState<ImageInfo> = mutableStateOf(ImageInfo())
    val imageInfo: ImageInfo by _imageInfo

    private val _showWarning: MutableState<Boolean> = mutableStateOf(false)
    val showWarning: Boolean by _showWarning

    private val _shouldShowPreview: MutableState<Boolean> = mutableStateOf(true)
    val shouldShowPreview by _shouldShowPreview

    private val _presetSelected: MutableState<Preset> = mutableStateOf(Preset.None)
    val presetSelected by _presetSelected

    private val _isSaving: MutableState<Boolean> = mutableStateOf(false)
    val isSaving by _isSaving

    private val _drawMode: MutableState<DrawMode> = mutableStateOf(DrawMode.Pen)
    val drawMode: DrawMode by _drawMode

    private val _drawPathMode: MutableState<DrawPathMode> = mutableStateOf(DrawPathMode.Free)
    val drawPathMode: DrawPathMode by _drawPathMode

    private val _drawLineStyle: MutableState<DrawLineStyle> = mutableStateOf(DrawLineStyle.None)
    val drawLineStyle: DrawLineStyle by _drawLineStyle

    private val _helperGridParams = fileController.savable(
        scope = componentScope,
        initial = HelperGridParams()
    )
    val helperGridParams: HelperGridParams by _helperGridParams

    init {
        componentScope.launch {
            val settingsState = settingsProvider.getSettingsState()
            _drawPathMode.update { DrawPathMode.fromOrdinal(settingsState.defaultDrawPathMode) }
            _imageInfo.update {
                it.copy(resizeType = settingsState.defaultResizeType)
            }
        }
    }

    private var job: Job? by smartJob {
        _isImageLoading.update { false }
    }

    private suspend fun checkBitmapAndUpdate(resetPreset: Boolean = false) {
        if (resetPreset) {
            _presetSelected.update { Preset.None }
        }
        _bitmap.value?.let { bmp ->
            if (fromRawEditor) {
                // Preview is already the correctly-rendered RAW preview bitmap.
                // Skip re-processing through imagePreviewCreator so resolution/color changes
                // don't re-scale the preview canvas incorrectly.
                _shouldShowPreview.update { true }
                _previewBitmap.update { bmp }
                // Estimate compressed size from full-res dimensions so the header shows a
                // realistic file size instead of the small preview PNG size.
                val fullW = _imageInfo.value.width.takeIf { it > 0 } ?: bmp.width
                val fullH = _imageInfo.value.height.takeIf { it > 0 } ?: bmp.height
                _showWarning.update { fullW * fullH * 4L >= 10_000 * 10_000 * 3L }
                val quality = _imageInfo.value.quality
                val qualityFactor = when (val q = quality) {
                    is com.RAZStudio.StudioRoom.core.domain.image.model.Quality.Base -> q.qualityValue / 100f
                    else -> 0.85f
                }
                // JPEG-like estimate: 3 bytes/pixel × quality for lossy, 3×fullW×fullH for lossless
                val estimatedBytes = (fullW.toLong() * fullH * 3 * qualityFactor).toLong()
                    .coerceAtLeast(fullW.toLong() * fullH / 10)
                _imageInfo.update { it.copy(sizeInBytes = estimatedBytes.toInt()) }
            } else {
                val preview = updatePreview(bmp)
                _shouldShowPreview.update { imagePreviewCreator.canShow(preview) }
                if (shouldShowPreview) _previewBitmap.update { preview }
            }
        }
    }

    private var savingJob: Job? by smartJob {
        _isSaving.update { false }
    }

    fun saveBitmap(
        oneTimeSaveLocationUri: String?
    ) {
        savingJob = trackProgress {
            _isSaving.update { true }

            // When coming from the RAW editor the canvas shows preview-resolution,
            // but we save from the full-res render (deferred to this point).
            // On first save the render runs and the path is cached for re-saves.
            val bitmapToSave: Bitmap? = if (fromRawEditor) {
                val fullRes = rawFullResPath ?: run {
                    val renderFn = com.RAZStudio.StudioRoom.feature.photo_editor.raw.PendingRawExport.consume()
                    renderFn?.invoke()?.also { rawFullResPath = it }
                }
                if (fullRes != null) android.graphics.BitmapFactory.decodeFile(fullRes) ?: bitmap
                else bitmap
            } else {
                bitmap
            }

            bitmapToSave?.let { bmp ->
                parseSaveResult(
                    fileController.save(
                        saveTarget = ImageSaveTarget(
                            imageInfo = imageInfo,
                            metadata = exif,
                            originalUri = uri.toString(),
                            sequenceNumber = null,
                            data = imageCompressor.compressAndTransform(
                                image = bmp,
                                imageInfo = imageInfo.copy(originalUri = uri.toString())
                            ),
                            presetInfo = presetSelected
                        ),
                        keepOriginalMetadata = true,
                        oneTimeSaveLocationUri = oneTimeSaveLocationUri
                    ).onSuccess(::registerSave)
                )
            }
            _isSaving.update { false }
        }
    }

    private suspend fun updatePreview(
        bitmap: Bitmap,
    ): Bitmap? = withContext(defaultDispatcher) {
        return@withContext imageInfo.run {
            _showWarning.update {
                width * height * 4L >= 10_000 * 10_000 * 3L
            }
            imagePreviewCreator.createPreview(
                image = bitmap,
                imageInfo = this,
                onGetByteCount = { sizeInBytes ->
                    _imageInfo.update { it.copy(sizeInBytes = sizeInBytes) }
                }
            )
        }
    }

    private fun setBitmapInfo(newInfo: ImageInfo) {
        if (imageInfo != newInfo) {
            _imageInfo.update { newInfo }
            debouncedImageCalculation {
                checkBitmapAndUpdate()
            }
        }
    }

    fun resetValues(newBitmapComes: Boolean = false) {
        _imageInfo.update {
            ImageInfo(
                width = _originalSize.value?.width ?: 0,
                height = _originalSize.value?.height ?: 0,
                imageFormat = it.imageFormat,
                originalUri = uri.toString()
            )
        }
        if (newBitmapComes) {
            _bitmap.update {
                _internalBitmap.value
            }
        }
        debouncedImageCalculation {
            checkBitmapAndUpdate(
                resetPreset = true
            )
        }
    }

    fun updateBitmapAfterEditing(
        bitmap: Bitmap?,
        saveOriginalSize: Boolean = false,
    ) {
        componentScope.launch {
            if (!saveOriginalSize) {
                val size = bitmap?.let { it.width to it.height }
                _originalSize.update {
                    size?.run { IntegerSize(width = first, height = second) }
                }
            }
            _bitmap.update {
                imageScaler.scaleUntilCanShow(bitmap)
            }
            _imageInfo.update {
                // Preserve rotation when saveOriginalSize=true: the bitmap from filter/draw/erase
                // tools is in the same orientation as the stored _internalBitmap, so the existing
                // rotationDegrees still applies. Only reset when saveOriginalSize=false (e.g. crop),
                // which physically bakes any rotation into the new bitmap pixels.
                it.copy(rotationDegrees = if (saveOriginalSize) it.rotationDegrees else 0f)
            }
            if (!saveOriginalSize) {
                _imageInfo.update {
                    it.copy(
                        width = bitmap?.width ?: 0,
                        height = bitmap?.height ?: 0
                    )
                }
            }
            debouncedImageCalculation {
                checkBitmapAndUpdate(
                    resetPreset = true
                )
            }
            registerChanges()
        }
    }

    fun rotateBitmapLeft() {
        _imageInfo.update {
            it.copy(
                rotationDegrees = it.rotationDegrees - 90f,
                height = it.width,
                width = it.height
            )
        }
        debouncedImageCalculation {
            checkBitmapAndUpdate()
        }
        registerChanges()
    }

    fun rotateBitmapRight() {
        _imageInfo.update {
            it.copy(
                rotationDegrees = it.rotationDegrees + 90f,
                height = it.width,
                width = it.height
            )
        }
        debouncedImageCalculation {
            checkBitmapAndUpdate()
        }
        registerChanges()
    }

    fun flipImage() {
        _imageInfo.update {
            it.copy(isFlipped = !it.isFlipped)
        }
        debouncedImageCalculation {
            checkBitmapAndUpdate()
        }
        registerChanges()
    }

    fun updateWidth(width: Int) {
        if (imageInfo.width != width) {
            _imageInfo.update {
                it.copy(width = width)
            }
            debouncedImageCalculation {
                checkBitmapAndUpdate(
                    resetPreset = true
                )
            }
            registerChanges()
        }
    }

    fun updateHeight(height: Int) {
        if (imageInfo.height != height) {
            _imageInfo.update {
                it.copy(height = height)
            }
            debouncedImageCalculation {
                checkBitmapAndUpdate(
                    resetPreset = true
                )
            }
            registerChanges()
        }
    }

    fun setQuality(quality: Quality) {
        if (imageInfo.quality != quality) {
            _imageInfo.update {
                it.copy(quality = quality)
            }
            debouncedImageCalculation {
                checkBitmapAndUpdate()
            }
            registerChanges()
        }
    }

    fun setImageFormat(imageFormat: ImageFormat) {
        if (imageInfo.imageFormat != imageFormat) {
            _imageInfo.update {
                it.copy(imageFormat = imageFormat)
            }
            debouncedImageCalculation {
                checkBitmapAndUpdate(
                    resetPreset = _presetSelected.value == Preset.Telegram && imageFormat != ImageFormat.Png.Lossless
                )
            }
            registerChanges()
        }
    }

    fun setResizeType(type: ResizeType) {
        if (imageInfo.resizeType != type) {
            _imageInfo.update {
                it.copy(
                    resizeType = type.withOriginalSizeIfCrop(originalSize)
                )
            }
            debouncedImageCalculation {
                checkBitmapAndUpdate(
                    resetPreset = false
                )
            }
            registerChanges()
        }
    }

    fun setUri(uri: Uri) {
        _uri.update { uri }
        decodeBitmapByUri(uri)
    }

    private fun decodeBitmapByUri(uri: Uri) {
        _isImageLoading.update { true }
        _imageInfo.update { it.copy(originalUri = uri.toString()) }
        val ext = getUriExtension(uri)
        if (LibRawJniBridge.isAvailable && ext in LibRawJniBridge.supportedExtensions) {
            decodeRawByUri(uri)
        } else {
            decodeByCoil(uri)
        }
    }

    private fun decodeByCoil(uri: Uri) {
        imageGetter.getImageAsync(
            uri = uri.toString(),
            originalSize = true,
            onGetImage = ::setImageData,
            onFailure = {
                _isImageLoading.update { false }
                AppToastHost.showFailureToast(it)
            }
        )
    }

    private fun decodeRawByUri(uri: Uri) {
        componentScope.launch {
            var tempFile: java.io.File? = null
            try {
                val filePath = withContext(Dispatchers.IO) {
                    if (uri.scheme == "file") {
                        uri.path
                    } else {
                        val ext = getUriExtension(uri).ifEmpty { "raw" }
                        val tmp = java.io.File(appContext.cacheDir, "raw_decode_${uri.hashCode()}.$ext")
                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { out -> input.copyTo(out) }
                        }
                        tempFile = tmp
                        tmp.absolutePath
                    }
                }
                if (filePath == null) { decodeByCoil(uri); return@launch }

                // Use halfSize=true for the preview canvas — 4× faster decode, still full quality for display.
                // Full-res decode happens separately in FullResPipeline for save.
                val decoded = LibRawJniBridge.decodeToLinear(filePath, halfSize = true)
                if (decoded == null) { decodeByCoil(uri); return@launch }

                val bitmap = withContext(Dispatchers.Default) {
                    RawBitmapConverter.linearToBitmap(decoded)
                }
                setImageData(
                    ImageData(
                        image = bitmap,
                        imageInfo = ImageInfo(
                            width = bitmap.width,
                            height = bitmap.height,
                            imageFormat = ImageFormat.Png.Lossless,
                            originalUri = uri.toString(),
                        )
                    )
                )
            } catch (e: Exception) {
                _isImageLoading.update { false }
                AppToastHost.showFailureToast(e)
            } finally {
                withContext(Dispatchers.IO) {
                    tempFile?.delete()
                }
            }
        }
    }

    private fun getUriExtension(uri: Uri): String =
        runCatching {
            appContext.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).substringAfterLast('.').lowercase()
                else null
            }
        }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('.')?.lowercase()
            ?: ""



    private fun setImageData(imageData: ImageData<Bitmap>) {
        job = componentScope.launch {
            _isImageLoading.update { true }
            val rawMeta = if (fromRawEditor)
                com.RAZStudio.StudioRoom.feature.photo_editor.raw.PendingRawExport.rawMetadata
            else null
            _exif.update { rawMeta?.toMetadata() ?: imageData.metadata }
            val bitmap = imageData.image
            val size = bitmap.width to bitmap.height

            // When the image comes from the RAW editor the decoded file is preview-resolution.
            // Use the full-res dimensions stored in PendingRawExport so the save dialog and
            // resolution selector default to the actual RAW output size, not the small preview.
            val rawW = if (fromRawEditor) com.RAZStudio.StudioRoom.feature.photo_editor.raw.PendingRawExport.fullResWidth else 0
            val rawH = if (fromRawEditor) com.RAZStudio.StudioRoom.feature.photo_editor.raw.PendingRawExport.fullResHeight else 0
            val effectiveW = if (rawW > 0) rawW else size.first
            val effectiveH = if (rawH > 0) rawH else size.second

            _originalSize.update { IntegerSize(width = effectiveW, height = effectiveH) }
            _bitmap.update {
                _internalBitmap.update {
                    imageScaler.scaleUntilCanShow(bitmap)
                }
            }
            resetValues(true)
            _imageInfo.update {
                imageData.imageInfo.copy(
                    width = effectiveW,
                    height = effectiveH
                )
            }
            checkBitmapAndUpdate(
                resetPreset = _presetSelected.value == Preset.Telegram && imageData.imageInfo.imageFormat != ImageFormat.Png.Lossless
            )
            _isImageLoading.update { false }
        }
    }

    /**
     * Save the current preview-resolution bitmap directly, bypassing the full-res render.
     * Only meaningful when [fromRawEditor] is true — gives the user an instant "quick save"
     * at the rendered preview size without waiting for the full MacroProcessor pass.
     */
    fun savePreviewBitmap(oneTimeSaveLocationUri: String?) {
        savingJob = trackProgress {
            _isSaving.update { true }
            val bmp = bitmap ?: run { _isSaving.update { false }; return@trackProgress }
            parseSaveResult(
                fileController.save(
                    saveTarget = ImageSaveTarget(
                        imageInfo = imageInfo.copy(
                            width = bmp.width,
                            height = bmp.height,
                            originalUri = uri.toString()
                        ),
                        metadata = exif,
                        originalUri = uri.toString(),
                        sequenceNumber = null,
                        data = imageCompressor.compressAndTransform(
                            image = bmp,
                            imageInfo = imageInfo.copy(
                                width = bmp.width,
                                height = bmp.height,
                                originalUri = uri.toString()
                            )
                        ),
                        presetInfo = presetSelected
                    ),
                    keepOriginalMetadata = true,
                    oneTimeSaveLocationUri = oneTimeSaveLocationUri
                ).onSuccess(::registerSave)
            )
            _isSaving.update { false }
        }
    }

    fun shareBitmap() {
        savingJob = trackProgress {
            _isSaving.update { true }
            bitmap?.let { image ->
                shareProvider.shareImage(
                    image = image,
                    imageInfo = imageInfo.copy(originalUri = uri.toString()),
                    onComplete = AppToastHost::showConfetti
                )
            }
            _isSaving.update { false }
        }
    }

    fun cacheCurrentImage(onComplete: (Uri) -> Unit) {
        savingJob = trackProgress {
            _isSaving.update { true }
            bitmap?.let { image ->
                shareProvider.cacheImage(
                    image = image,
                    imageInfo = imageInfo.copy(originalUri = uri.toString())
                )?.let { uri ->
                    onComplete(uri.toUri())
                }
            }
            _isSaving.update { false }
        }
    }

    fun canShow(): Boolean = bitmap?.let { imagePreviewCreator.canShow(it) } == true

    fun setPreset(preset: Preset) {
        componentScope.launch {
            if (preset is Preset.AspectRatio && preset.ratio != 1f) {
                _imageInfo.update { it.copy(rotationDegrees = 0f) }
            }
            setBitmapInfo(
                imageTransformer.applyPresetBy(
                    image = bitmap,
                    preset = preset,
                    currentInfo = imageInfo.copy(
                        originalUri = uri.toString()
                    )
                )
            )
            _presetSelected.update { preset }
            registerChanges()
        }
    }

    fun clearExif() {
        updateExif(_exif.value?.clearAllAttributes())
    }

    private fun updateExif(metadata: Metadata?) {
        _exif.update { metadata }
        registerChanges()
    }

    fun removeExifTag(tag: MetadataTag) {
        updateExif(_exif.value?.clearAttribute(tag))
    }

    fun updateExifByTag(
        tag: MetadataTag,
        value: String,
    ) {
        updateExif(_exif.value?.setAttribute(tag, value))
    }

    fun setCropAspectRatio(
        domainAspectRatio: DomainAspectRatio,
        aspectRatio: AspectRatio,
    ) {
        _cropProperties.update { properties ->
            properties.copy(
                aspectRatio = aspectRatio.takeIf {
                    domainAspectRatio != DomainAspectRatio.Original
                } ?: _bitmap.value?.let {
                    AspectRatio(it.safeAspectRatio)
                } ?: aspectRatio,
                fixedAspectRatio = domainAspectRatio != DomainAspectRatio.Free
            )
        }
        _selectedAspectRatio.update { domainAspectRatio }
    }

    fun setCropMask(cropOutlineProperty: CropOutlineProperty) {
        _cropProperties.value =
            _cropProperties.value.copy(cropOutlineProperty = cropOutlineProperty)
    }

    suspend fun loadImage(uri: Uri): Bitmap? = imageGetter.getImage(data = uri)

    fun getBackgroundRemover(): AutoBackgroundRemover<Bitmap> = autoBackgroundRemover

    fun <T : Any> updateFilter(
        value: T,
        index: Int
    ) {
        val list = _filterList.value.toMutableList()
        runCatching {
            list[index] = list[index].copy(value)
            _filterList.update { list }
        }.onFailure {
            AppToastHost.showFailureToast(it)
            list[index] = list[index].newInstance()
            _filterList.update { list }
        }
    }

    fun updateOrder(value: List<UiFilter<*>>) {
        _filterList.update { value }
    }

    fun addFilter(filter: UiFilter<*>) {
        _filterList.update {
            it + filter
        }
    }

    fun removeFilterAtIndex(index: Int) {
        _filterList.update {
            it.toMutableList().apply {
                removeAt(index)
            }
        }
    }

    fun clearFilterList() {
        _filterList.update { listOf() }
    }

    fun clearDrawing(canUndo: Boolean = false) {
        componentScope.launch {
            delay(500L)
            _drawLastPaths.update { if (canUndo) drawPaths else listOf() }
            _drawPaths.update { listOf() }
            _drawUndonePaths.update { listOf() }
            _drawMode.update { DrawMode.Pen }
            _drawPathMode.update { DrawPathMode.Free }
        }
    }

    fun undoDraw() {
        if (drawPaths.isEmpty() && drawLastPaths.isNotEmpty()) {
            _drawPaths.update { drawLastPaths }
            _drawLastPaths.update { listOf() }
            return
        }
        if (drawPaths.isEmpty()) return

        val lastPath = drawPaths.last()

        _drawPaths.update { it - lastPath }
        _drawUndonePaths.update { it + lastPath }
    }

    fun redoDraw() {
        if (drawUndonePaths.isEmpty()) return

        val lastPath = drawUndonePaths.last()
        _drawPaths.update { it + lastPath }
        _drawUndonePaths.update { it - lastPath }
    }

    fun addPathToDrawList(pathPaint: UiPathPaint) {
        _drawPaths.update { it + pathPaint }
        _drawUndonePaths.update { listOf() }
    }

    fun clearErasing(canUndo: Boolean = false) {
        componentScope.launch {
            delay(250L)
            _eraseLastPaths.update { if (canUndo) erasePaths else listOf() }
            _erasePaths.update { listOf() }
            _eraseUndonePaths.update { listOf() }
            _drawPathMode.update { DrawPathMode.Free }
        }
    }

    fun undoErase() {
        if (erasePaths.isEmpty() && eraseLastPaths.isNotEmpty()) {
            _erasePaths.update { eraseLastPaths }
            _eraseLastPaths.update { listOf() }
            return
        }
        if (erasePaths.isEmpty()) return

        val lastPath = erasePaths.last()

        _erasePaths.update { it - lastPath }
        _eraseUndonePaths.update { it + lastPath }
    }

    fun redoErase() {
        if (eraseUndonePaths.isEmpty()) return

        val lastPath = eraseUndonePaths.last()
        _erasePaths.update { it + lastPath }
        _eraseUndonePaths.update { it - lastPath }
    }

    fun addPathToEraseList(pathPaint: UiPathPaint) {
        _erasePaths.update { it + pathPaint }
        _eraseUndonePaths.update { listOf() }
    }

    fun cancelSaving() {
        savingJob?.cancel()
        savingJob = null
        _isSaving.update { false }
    }

    fun setImageScaleMode(imageScaleMode: ImageScaleMode) {
        _imageInfo.update {
            it.copy(imageScaleMode = imageScaleMode)
        }
        debouncedImageCalculation {
            checkBitmapAndUpdate()
        }
        registerChanges()
    }

    fun setResizeSharpen(resizeSharpen: ResizeSharpen) {
        _imageInfo.update {
            it.copy(resizeSharpen = resizeSharpen)
        }
        debouncedImageCalculation {
            checkBitmapAndUpdate()
        }
        registerChanges()
    }

    suspend fun filter(
        bitmap: Bitmap,
        filters: List<Filter<*>>,
    ): Bitmap? = imageTransformer.transform(
        image = bitmap,
        transformations = mapFilters(filters)
    )

    fun mapFilters(
        filters: List<Filter<*>>,
    ): List<Transformation<Bitmap>> = filters.map { filterProvider.filterToTransformation(it) }

    suspend fun transformBitmap(
        bitmap: Bitmap,
        filter: com.RAZStudio.StudioRoom.core.filters.presentation.model.UiFilter<*>,
    ): Bitmap = imageTransformer.transform(
        image = bitmap,
        transformations = listOf(filterProvider.filterToTransformation(filter)),
    ) ?: bitmap

    fun updateDrawMode(drawMode: DrawMode) {
        _drawMode.update { drawMode }
    }

    fun updateDrawPathMode(drawPathMode: DrawPathMode) {
        _drawPathMode.update { drawPathMode }
    }

    fun getFormatForFilenameSelection(): ImageFormat = imageInfo.imageFormat

    fun resetImageCurvesEditorState() {
        _imageCurvesEditorState.update { ImageCurvesEditorState.Default }
    }

    fun updateDrawLineStyle(style: DrawLineStyle) {
        _drawLineStyle.update { style }
    }

    fun updateHelperGridParams(params: HelperGridParams) {
        _helperGridParams.update { params }
    }


    @AssistedFactory
    interface Factory {
        operator fun invoke(
            componentContext: ComponentContext,
            initialUri: Uri?,
            fromRawEditor: Boolean,
            onGoBack: () -> Unit,
            onNavigate: (Screen) -> Unit,
        ): PhotoEditorComponent
    }
}
