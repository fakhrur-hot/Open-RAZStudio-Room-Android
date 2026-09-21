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

package com.RAZStudio.StudioRoom.core.ui.utils.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.net.toUri
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.Album
import com.RAZStudio.StudioRoom.core.resources.icons.ApngBox
import com.RAZStudio.StudioRoom.core.resources.icons.ArtTrack
import com.RAZStudio.StudioRoom.core.resources.icons.Ascii
import com.RAZStudio.StudioRoom.core.resources.icons.AutoFixHigh
import com.RAZStudio.StudioRoom.core.resources.icons.Base64
import com.RAZStudio.StudioRoom.core.resources.icons.Bolt
import com.RAZStudio.StudioRoom.core.resources.icons.BubbleDelete
import com.RAZStudio.StudioRoom.core.resources.icons.Build
import com.RAZStudio.StudioRoom.core.resources.icons.Collage
import com.RAZStudio.StudioRoom.core.resources.icons.Compare
import com.RAZStudio.StudioRoom.core.resources.icons.Counter
import com.RAZStudio.StudioRoom.core.resources.icons.CropSmall
import com.RAZStudio.StudioRoom.core.resources.icons.DeleteSweep
import com.RAZStudio.StudioRoom.core.resources.icons.DocumentScanner
import com.RAZStudio.StudioRoom.core.resources.icons.Draw
import com.RAZStudio.StudioRoom.core.resources.icons.Encrypted
import com.RAZStudio.StudioRoom.core.resources.icons.Eraser
import com.RAZStudio.StudioRoom.core.resources.icons.Exif
import com.RAZStudio.StudioRoom.core.resources.icons.ExifEdit
import com.RAZStudio.StudioRoom.core.resources.icons.Eyedropper
import com.RAZStudio.StudioRoom.core.resources.icons.FileImage
import com.RAZStudio.StudioRoom.core.resources.icons.FilterBAndW
import com.RAZStudio.StudioRoom.core.resources.icons.FindInPage
import com.RAZStudio.StudioRoom.core.resources.icons.FolderZip
import com.RAZStudio.StudioRoom.core.resources.icons.FormatPaintVariant
import com.RAZStudio.StudioRoom.core.resources.icons.GifBox
import com.RAZStudio.StudioRoom.core.resources.icons.Gradient
import com.RAZStudio.StudioRoom.core.resources.icons.HashTag
import com.RAZStudio.StudioRoom.core.resources.icons.ImageCombine
import com.RAZStudio.StudioRoom.core.resources.icons.ImageConvert
import com.RAZStudio.StudioRoom.core.resources.icons.ImageDownload
import com.RAZStudio.StudioRoom.core.resources.icons.ImageEdit
import com.RAZStudio.StudioRoom.core.resources.icons.ImageOverlay
import com.RAZStudio.StudioRoom.core.resources.icons.ImageResize
import com.RAZStudio.StudioRoom.core.resources.icons.ImageSync
import com.RAZStudio.StudioRoom.core.resources.icons.ImageWeight
import com.RAZStudio.StudioRoom.core.resources.icons.Jxl
import com.RAZStudio.StudioRoom.core.resources.icons.KeyVariant
import com.RAZStudio.StudioRoom.core.resources.icons.Landscape
import com.RAZStudio.StudioRoom.core.resources.icons.MiniEditLarge
import com.RAZStudio.StudioRoom.core.resources.icons.MultipleImageEdit
import com.RAZStudio.StudioRoom.core.resources.icons.Neurology
import com.RAZStudio.StudioRoom.core.resources.icons.NoiseAlt
import com.RAZStudio.StudioRoom.core.resources.icons.Palette
import com.RAZStudio.StudioRoom.core.resources.icons.PaletteSwatch
import com.RAZStudio.StudioRoom.core.resources.icons.Panorama
import com.RAZStudio.StudioRoom.core.resources.icons.Pdf
import com.RAZStudio.StudioRoom.core.resources.icons.Preview
import com.RAZStudio.StudioRoom.core.resources.icons.Print
import com.RAZStudio.StudioRoom.core.resources.icons.QrCode
import com.RAZStudio.StudioRoom.core.resources.icons.Rotate90Cw
import com.RAZStudio.StudioRoom.core.resources.icons.Scanner
import com.RAZStudio.StudioRoom.core.resources.icons.ScissorsSmall
import com.RAZStudio.StudioRoom.core.resources.icons.ServiceToolbox
import com.RAZStudio.StudioRoom.core.resources.icons.ShieldLock
import com.RAZStudio.StudioRoom.core.resources.icons.SplitAlt
import com.RAZStudio.StudioRoom.core.resources.icons.Stacks
import com.RAZStudio.StudioRoom.core.resources.icons.Stylus
import com.RAZStudio.StudioRoom.core.resources.icons.SwapVerticalCircle
import com.RAZStudio.StudioRoom.core.resources.icons.TagText
import com.RAZStudio.StudioRoom.core.resources.icons.TextSearch
import com.RAZStudio.StudioRoom.core.resources.icons.Unarchive
import com.RAZStudio.StudioRoom.core.resources.icons.VectorPolyline
import com.RAZStudio.StudioRoom.core.resources.icons.WallpaperAlt
import com.RAZStudio.StudioRoom.core.resources.icons.WandShine
import com.RAZStudio.StudioRoom.core.resources.icons.Watermark
import com.RAZStudio.StudioRoom.core.resources.icons.WebpBox
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.AiTools
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.ApngTools
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.AppLogs
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.AsciiArt
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.AudioCoverExtractor
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.Base64Tools
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.CanonBatchDownload
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.CanonRemoteShoot
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.CanonSync
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.SonySync
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.GalleryWorkspace
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.AddToProject
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.GalleryProject
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.LutCreator
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.VideoEditor
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.ChecksumTools
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.Cipher
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.CollageMaker
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.ColorLibrary
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.ColorTools
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.Compare
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.Crop
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.DeleteExif
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.DocumentScanner
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.Draw
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.EasterEgg
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.EditExif
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.EraseBackground
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.Filter
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.FormatConversion
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.GifTools
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.GradientMaker
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.ImageCutter
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.ImagePreview
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.ImageSplitting
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.ImageStacking
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.ImageStitching
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.JxlTools
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.LibrariesInfo
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.LibraryDetails
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.LimitResize
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.LoadNetImage
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.Main
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.MarkupLayers
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.MeshGradients
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.NoiseGeneration
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.PaletteTools
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.PdfTools
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.PickColorFromImage
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.RecognizeText
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.ResizeAndConvert
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.ScanQrCode
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.Settings
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.PhotoEditor
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.RawDetailsEditor
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.RawEditor
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.RawExport
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.SvgMaker
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.WallpapersExport
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.Watermarking
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.WebpTools
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.WeightResize
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.Zip
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import android.net.Uri as AndroidUri

@Suppress("UnusedReceiverParameter")
internal fun Screen.isBetaFeature(): Boolean = false

internal fun Screen.simpleName(): String = when (this) {
    is ApngTools -> "APNG_Tools"
    is Cipher -> "Cipher"
    is Compare -> "Compare"
    is Crop -> "Crop"
    is DeleteExif -> "Delete_Exif"
    is Draw -> "Draw"
    is EasterEgg -> "Easter_Egg"
    is EraseBackground -> "Erase_Background"
    is Filter -> "Filter"
    is PaletteTools -> "Palette_Tools"
    is GifTools -> "GIF_Tools"
    is GradientMaker -> "Gradient_Maker"
    is ImagePreview -> "Image_Preview"
    is ImageStitching -> "Image_Stitching"
    is JxlTools -> "JXL_Tools"
    is LimitResize -> "Limit_Resize"
    is LoadNetImage -> "Load_Net_Image"
    is Main -> "Main"
    is PdfTools -> "PDF_Tools"
    is PickColorFromImage -> "Pick_Color_From_Image"
    is RecognizeText -> "Recognize_Text"
    is ResizeAndConvert -> "Resize_And_Convert"
    is WeightResize -> "Resize_By_Bytes"
    is Settings -> "Settings"
    is PhotoEditor -> "Single_Edit"
    is RawEditor -> "RAW_Editor"
    is Screen.Raw8BitEditor -> "RAW_8Bit_Editor"
    is Watermarking -> "Watermarking"
    is Zip -> "Zip"
    is SvgMaker -> "Svg"
    is FormatConversion -> "Convert"
    is DocumentScanner -> "Document_Scanner"
    is ScanQrCode -> "QR_Code"
    is ImageStacking -> "Image_Stacking"
    is ImageSplitting -> "Image_Splitting"
    is ColorTools -> "Color_Tools"
    is WebpTools -> "WEBP_Tools"
    is NoiseGeneration -> "Noise_Generation"
    is CollageMaker -> "Collage_Maker"
    is AppLogs -> "App_Logs"
    is LibrariesInfo -> "Libraries_Info"
    is MarkupLayers -> "Markup_Layers"
    is Base64Tools -> "Base64_Tools"
    is ChecksumTools -> "Checksum_Tools"
    is MeshGradients -> "Mesh_Gradients"
    is EditExif -> "Edit_EXIF"
    is ImageCutter -> "Image_Cutting"
    is AudioCoverExtractor -> "Audio_Cover_Extractor"
    is LibraryDetails -> "Library_Details"
    is WallpapersExport -> "Wallpapers_Export"
    is AsciiArt -> "Ascii_Art"
    is AiTools -> "Ai_Tools"
    is ColorLibrary -> "ColorLibrary"
    is PdfTools.Merge -> "PdfTools_Merge"
    is PdfTools.Split -> "PdfTools_Split"
    is PdfTools.Rotate -> "PdfTools_Rotate"
    is PdfTools.Rearrange -> "PdfTools_Rearrange"
    is PdfTools.PageNumbers -> "PdfTools_PageNumbers"
    is PdfTools.OCR -> "PdfTools_OCR"
    is PdfTools.Watermark -> "PdfTools_Watermark"
    is PdfTools.Signature -> "PdfTools_Signature"
    is PdfTools.Protect -> "PdfTools_Protect"
    is PdfTools.Unlock -> "PdfTools_Unlock"
    is PdfTools.Compress -> "PdfTools_Compress"
    is PdfTools.Grayscale -> "PdfTools_Grayscale"
    is PdfTools.Repair -> "PdfTools_Repair"
    is PdfTools.Metadata -> "PdfTools_Metadata"
    is PdfTools.RemovePages -> "PdfTools_RemovePages"
    is PdfTools.Crop -> "PdfTools_Crop"
    is PdfTools.Flatten -> "PdfTools_Flatten"
    is PdfTools.ExtractImages -> "PdfTools_ExtractImages"
    is PdfTools.ZipConvert -> "PdfTools_ZipConvert"
    is PdfTools.Print -> "PdfTools_Print"
    is PdfTools.Preview -> "PdfTools_Preview"
    is PdfTools.ImagesToPdf -> "PdfTools_ImagesToPdf"
    is PdfTools.ExtractPages -> "PdfTools_ExtractPages"
    is PdfTools.RemoveAnnotations -> "PdfTools_RemoveAnnotations"
    is RawExport -> "Raw_Export"
    is RawDetailsEditor -> "Raw_Details_Editor"
    is CanonSync -> "Canon_Sync"
    is CanonRemoteShoot -> "Canon_Remote_Shoot"
    is CanonBatchDownload -> "Canon_Batch_Download"
    is SonySync -> "Sony_Sync"
    is GalleryWorkspace -> "Gallery_Workspace"
    is AddToProject -> "Add_To_Project"
    is GalleryProject -> "Gallery_Project"
    is LutCreator -> "LUT_Creator"
    is VideoEditor -> "Video_Editor"
}

internal fun Screen.icon(): ImageVector? = when (this) {
    is EasterEgg,
    is Main,
    is Settings,
    is AppLogs,
    is LibrariesInfo,
    is MeshGradients,
    is LibraryDetails -> null

    is PhotoEditor -> Icons.Outlined.ImageEdit
    is RawEditor -> Icons.Outlined.ImageEdit
    is Screen.Raw8BitEditor -> Icons.Outlined.ImageEdit
    is RawExport -> Icons.Outlined.ImageEdit
    is RawDetailsEditor -> Icons.Outlined.ImageEdit
    is CanonSync -> Icons.Outlined.ImageSync
    is CanonRemoteShoot -> Icons.Outlined.ImageSync
    is CanonBatchDownload -> Icons.Outlined.ImageSync
    is SonySync -> Icons.Outlined.ImageSync
    is GalleryWorkspace -> Icons.Outlined.ImageEdit
    is AddToProject -> Icons.Outlined.ImageEdit
    is GalleryProject -> Icons.Outlined.ImageEdit
    is LutCreator -> Icons.Outlined.Gradient
    is VideoEditor -> Icons.Outlined.ArtTrack
    is ApngTools -> Icons.Outlined.ApngBox
    is Cipher -> Icons.Outlined.Encrypted
    is Compare -> Icons.Outlined.Compare
    is Crop -> Icons.Rounded.CropSmall
    is DeleteExif -> Icons.Outlined.Exif
    is Draw -> Icons.Outlined.Draw
    is EraseBackground -> Icons.Rounded.Eraser
    is Filter -> Icons.Outlined.AutoFixHigh
    is PaletteTools -> Icons.Outlined.PaletteSwatch
    is GifTools -> Icons.Outlined.GifBox
    is GradientMaker -> Icons.Outlined.Gradient
    is ImagePreview -> Icons.Outlined.Landscape
    is ImageStitching -> Icons.Rounded.ImageCombine
    is JxlTools -> Icons.Filled.Jxl
    is LimitResize -> Icons.Outlined.ImageResize
    is LoadNetImage -> Icons.Outlined.ImageDownload
    is PdfTools -> Icons.Outlined.Pdf
    is PickColorFromImage -> Icons.Outlined.Eyedropper
    is RecognizeText -> Icons.Outlined.TextSearch
    is ResizeAndConvert -> Icons.Outlined.MultipleImageEdit
    is WeightResize -> Icons.Outlined.ImageWeight
    is Watermarking -> Icons.Outlined.Watermark
    is Zip -> Icons.Outlined.FolderZip
    is SvgMaker -> Icons.Outlined.VectorPolyline
    is FormatConversion -> Icons.Outlined.ImageConvert
    is DocumentScanner -> Icons.Outlined.DocumentScanner
    is ScanQrCode -> Icons.Outlined.QrCode
    is ImageStacking -> Icons.Outlined.ImageOverlay
    is ImageSplitting -> Icons.Outlined.SplitAlt
    is ColorTools -> Icons.Outlined.Palette
    is WebpTools -> Icons.Outlined.WebpBox
    is NoiseGeneration -> Icons.Outlined.NoiseAlt
    is CollageMaker -> Icons.Outlined.Collage
    is MarkupLayers -> Icons.Outlined.Stacks
    is Base64Tools -> Icons.Outlined.Base64
    is ChecksumTools -> Icons.Rounded.HashTag
    is EditExif -> Icons.Outlined.ExifEdit
    is ImageCutter -> Icons.Outlined.ScissorsSmall
    is AudioCoverExtractor -> Icons.Outlined.Album
    is WallpapersExport -> Icons.Outlined.WallpaperAlt
    is AsciiArt -> Icons.Outlined.Ascii
    is AiTools -> Icons.Outlined.Neurology
    is ColorLibrary -> Icons.Outlined.FormatPaintVariant
    is PdfTools.Merge -> Icons.Rounded.ImageCombine
    is PdfTools.Split -> Icons.Outlined.SplitAlt
    is PdfTools.Rotate -> Icons.Outlined.Rotate90Cw
    is PdfTools.Rearrange -> Icons.Outlined.SwapVerticalCircle
    is PdfTools.PageNumbers -> Icons.Outlined.Counter
    is PdfTools.OCR -> Icons.Outlined.FindInPage
    is PdfTools.Watermark -> Icons.Outlined.Watermark
    is PdfTools.Signature -> Icons.Outlined.Stylus
    is PdfTools.Protect -> Icons.Outlined.ShieldLock
    is PdfTools.Unlock -> Icons.Outlined.KeyVariant
    is PdfTools.Compress -> Icons.Outlined.Bolt
    is PdfTools.Grayscale -> Icons.Rounded.FilterBAndW
    is PdfTools.Repair -> Icons.Outlined.Build
    is PdfTools.Metadata -> Icons.Outlined.TagText
    is PdfTools.RemovePages -> Icons.Outlined.DeleteSweep
    is PdfTools.Crop -> Icons.Rounded.CropSmall
    is PdfTools.Flatten -> Icons.Outlined.Panorama
    is PdfTools.ExtractImages -> Icons.Outlined.Unarchive
    is PdfTools.ZipConvert -> Icons.Outlined.FolderZip
    is PdfTools.Print -> Icons.Outlined.Print
    is PdfTools.Preview -> Icons.Outlined.Preview
    is PdfTools.ImagesToPdf -> Icons.Outlined.Scanner
    is PdfTools.ExtractPages -> Icons.Outlined.ArtTrack
    is PdfTools.RemoveAnnotations -> Icons.Outlined.BubbleDelete
}

internal fun Screen.twoToneIcon(): ImageVector? = when (this) {
    is EasterEgg,
    is Main,
    is Settings,
    is AppLogs,
    is LibrariesInfo,
    is MeshGradients,
    is LibraryDetails -> null

    is PhotoEditor -> Icons.TwoTone.ImageEdit
    is RawEditor -> Icons.TwoTone.ImageEdit
    is Screen.Raw8BitEditor -> Icons.TwoTone.ImageEdit
    is RawExport -> Icons.TwoTone.ImageEdit
    is RawDetailsEditor -> Icons.TwoTone.ImageEdit
    is CanonSync -> Icons.Outlined.ImageSync
    is CanonRemoteShoot -> Icons.Outlined.ImageSync
    is CanonBatchDownload -> Icons.Outlined.ImageSync
    is SonySync -> Icons.Outlined.ImageSync
    is GalleryWorkspace -> Icons.TwoTone.ImageEdit
    is AddToProject -> Icons.TwoTone.ImageEdit
    is GalleryProject -> Icons.TwoTone.ImageEdit
    is LutCreator -> Icons.Outlined.Gradient
    is VideoEditor -> Icons.Outlined.ArtTrack
    is ApngTools -> Icons.TwoTone.ApngBox
    is Cipher -> Icons.TwoTone.Encrypted
    is Compare -> Icons.TwoTone.Compare
    is Crop -> Icons.TwoTone.CropSmall
    is DeleteExif -> Icons.TwoTone.Exif
    is Draw -> Icons.TwoTone.Draw
    is EraseBackground -> Icons.TwoTone.Eraser
    is Filter -> Icons.Outlined.AutoFixHigh
    is PaletteTools -> Icons.TwoTone.PaletteSwatch
    is GifTools -> Icons.TwoTone.GifBox
    is GradientMaker -> Icons.Outlined.Gradient
    is ImagePreview -> Icons.TwoTone.Landscape
    is ImageStitching -> Icons.TwoTone.ImageCombine
    is JxlTools -> Icons.Filled.Jxl
    is LimitResize -> Icons.TwoTone.ImageResize
    is LoadNetImage -> Icons.TwoTone.ImageDownload
    is PdfTools -> Icons.TwoTone.Pdf
    is PickColorFromImage -> Icons.TwoTone.Eyedropper
    is RecognizeText -> Icons.Outlined.TextSearch
    is ResizeAndConvert -> Icons.TwoTone.MultipleImageEdit
    is WeightResize -> Icons.TwoTone.ImageWeight
    is Watermarking -> Icons.TwoTone.Watermark
    is Zip -> Icons.TwoTone.FolderZip
    is SvgMaker -> Icons.TwoTone.VectorPolyline
    is FormatConversion -> Icons.TwoTone.ImageConvert
    is DocumentScanner -> Icons.TwoTone.DocumentScanner
    is ScanQrCode -> Icons.TwoTone.QrCode
    is ImageStacking -> Icons.TwoTone.ImageOverlay
    is ImageSplitting -> Icons.TwoTone.SplitAlt
    is ColorTools -> Icons.TwoTone.Palette
    is WebpTools -> Icons.TwoTone.WebpBox
    is NoiseGeneration -> Icons.Outlined.NoiseAlt
    is CollageMaker -> Icons.TwoTone.Collage
    is MarkupLayers -> Icons.TwoTone.Stacks
    is Base64Tools -> Icons.TwoTone.Base64
    is ChecksumTools -> Icons.Rounded.HashTag
    is EditExif -> Icons.TwoTone.ExifEdit
    is ImageCutter -> Icons.TwoTone.ScissorsSmall
    is AudioCoverExtractor -> Icons.TwoTone.Album
    is WallpapersExport -> Icons.Outlined.WallpaperAlt
    is AsciiArt -> Icons.Outlined.Ascii
    is AiTools -> Icons.TwoTone.Neurology
    is ColorLibrary -> Icons.Outlined.FormatPaintVariant
    is PdfTools.Merge -> Icons.TwoTone.ImageCombine
    is PdfTools.Split -> Icons.TwoTone.SplitAlt
    is PdfTools.Rotate -> Icons.TwoTone.Rotate90Cw
    is PdfTools.Rearrange -> Icons.TwoTone.SwapVerticalCircle
    is PdfTools.PageNumbers -> Icons.TwoTone.Counter
    is PdfTools.OCR -> Icons.TwoTone.FindInPage
    is PdfTools.Watermark -> Icons.TwoTone.Watermark
    is PdfTools.Signature -> Icons.TwoTone.Stylus
    is PdfTools.Protect -> Icons.TwoTone.ShieldLock
    is PdfTools.Unlock -> Icons.TwoTone.KeyVariant
    is PdfTools.Compress -> Icons.TwoTone.Bolt
    is PdfTools.Grayscale -> Icons.TwoTone.FilterBAndW
    is PdfTools.Repair -> Icons.TwoTone.Build
    is PdfTools.Metadata -> Icons.TwoTone.TagText
    is PdfTools.RemovePages -> Icons.TwoTone.DeleteSweep
    is PdfTools.Crop -> Icons.TwoTone.CropSmall
    is PdfTools.Flatten -> Icons.TwoTone.Panorama
    is PdfTools.ExtractImages -> Icons.TwoTone.Unarchive
    is PdfTools.ZipConvert -> Icons.TwoTone.FolderZip
    is PdfTools.Print -> Icons.TwoTone.Print
    is PdfTools.Preview -> Icons.TwoTone.Preview
    is PdfTools.ImagesToPdf -> Icons.TwoTone.Scanner
    is PdfTools.ExtractPages -> Icons.TwoTone.ArtTrack
    is PdfTools.RemoveAnnotations -> Icons.TwoTone.BubbleDelete
}

internal object UriSerializer : KSerializer<AndroidUri> {
    override val descriptor = PrimitiveSerialDescriptor("Uri", PrimitiveKind.STRING)

    override fun deserialize(
        decoder: Decoder
    ): AndroidUri = decoder.decodeString().toUri()

    override fun serialize(
        encoder: Encoder,
        value: AndroidUri
    ) = encoder.encodeString(value.toString())
}

internal typealias Uri = @Serializable(UriSerializer::class) AndroidUri

internal interface ScreenConstants {
    val typedEntries: List<ScreenGroup>

    val entries: List<Screen>

    val FEATURES_COUNT: Int

    /** IDs of screens shown on the main page. All others are hidden. */
    val MAIN_PAGE_SCREEN_IDS: Set<Int>

    companion object : ScreenConstants by ScreenConstantsImpl
}

private object ScreenConstantsImpl : ScreenConstants {
    override val typedEntries by lazy {
        listOf(
            ScreenGroup(
                entries = listOf(
                    GalleryWorkspace,    // id 77 — first entry
                    PhotoEditor(),
                    RawEditor(),
                    CanonSync,
                    SonySync,
                    ResizeAndConvert(),
                    FormatConversion(),
                    Crop(),
                    ImageCutter(),
                    LimitResize(),
                    EditExif(),
                    DeleteExif(),
                ),
                title = R.string.edit,
                selectedIcon = Icons.Rounded.MiniEditLarge,
                baseIcon = Icons.Outlined.MiniEditLarge
            ),
            ScreenGroup(
                entries = listOf(
                    Filter(),
                    LutCreator,
                    Draw(),
                    MarkupLayers(),
                    AiTools(),
                    CollageMaker(),
                    ImageStitching(),
                    ImageStacking(),
                    ImageSplitting(),
                    Watermarking(),
                    GradientMaker(),
                    NoiseGeneration,
                    VideoEditor,
                ),
                title = R.string.create,
                selectedIcon = Icons.Rounded.WandShine,
                baseIcon = Icons.Outlined.WandShine
            ),
            ScreenGroup(
                entries = listOf(
                    PickColorFromImage(),
                    Compare(),
                    ImagePreview(),
                    PaletteTools(),
                    LoadNetImage(),
                ),
                title = R.string.image,
                selectedIcon = Icons.Rounded.FileImage,
                baseIcon = Icons.Outlined.FileImage
            ),
            ScreenGroup(
                entries = listOf(
                    ColorTools,
                    ColorLibrary,
                    GifTools(),
                    ChecksumTools(),
                    Zip(),
                    AsciiArt(),
                    WebpTools(),
                    AudioCoverExtractor()
                ),
                title = R.string.tools,
                selectedIcon = Icons.Rounded.ServiceToolbox,
                baseIcon = Icons.Outlined.ServiceToolbox
            )
        )
    }

    override val entries by lazy {
        // typedEntries already excludes removed-feature screens (WeightResize, Cipher,
        // EraseBackground, RecognizeText, ApngTools, JxlTools, SvgMaker, DocumentScanner,
        // ScanQrCode, Base64Tools, WallpapersExport, PdfTools + all sub-options).
        // We deliberately do NOT add PdfTools.options here anymore.
        typedEntries.flatMap { it.entries }
            .distinctBy { it.id }
            .sortedBy { it.id }
    }

    override val FEATURES_COUNT = entries.size

    // NOTE: this is an explicit allow-list, NOT derived from typedEntries.
    // Adding a screen to a ScreenGroup above is not enough to put it on the main
    // page — its id has to appear here too.
    override val MAIN_PAGE_SCREEN_IDS: Set<Int> = setOf(
        77, // GalleryWorkspace — first, above RawEditor (Req 1.2)
        68, // RawEditor
        70, // CanonSync
        76, // SonySync
        75, // LutCreator
        80, // VideoEditor
    )
}