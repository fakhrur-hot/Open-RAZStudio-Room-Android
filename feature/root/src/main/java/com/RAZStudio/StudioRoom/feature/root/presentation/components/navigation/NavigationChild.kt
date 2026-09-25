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

package com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation

import androidx.compose.runtime.Composable
import com.RAZStudio.StudioRoom.collage_maker.presentation.CollageMakerContent
import com.RAZStudio.StudioRoom.collage_maker.presentation.screenLogic.CollageMakerComponent
import com.RAZStudio.StudioRoom.feature.compare.presentation.CompareContent
import com.RAZStudio.StudioRoom.feature.compare.presentation.screenLogic.CompareComponent
import com.RAZStudio.StudioRoom.feature.crop.presentation.CropContent
import com.RAZStudio.StudioRoom.feature.crop.presentation.screenLogic.CropComponent
import com.RAZStudio.StudioRoom.feature.delete_exif.presentation.DeleteExifContent
import com.RAZStudio.StudioRoom.feature.delete_exif.presentation.screenLogic.DeleteExifComponent
import com.RAZStudio.StudioRoom.feature.draw.presentation.DrawContent
import com.RAZStudio.StudioRoom.feature.draw.presentation.screenLogic.DrawComponent
import com.RAZStudio.StudioRoom.feature.edit_exif.presentation.EditExifContent
import com.RAZStudio.StudioRoom.feature.edit_exif.presentation.screenLogic.EditExifComponent
import com.RAZStudio.StudioRoom.feature.erase_background.presentation.EraseBackgroundContent
import com.RAZStudio.StudioRoom.feature.erase_background.presentation.screenLogic.EraseBackgroundComponent
import com.RAZStudio.StudioRoom.feature.filters.presentation.FiltersContent
import com.RAZStudio.StudioRoom.feature.filters.presentation.screenLogic.FiltersComponent
import com.RAZStudio.StudioRoom.feature.gradient_maker.presentation.GradientMakerContent
import com.RAZStudio.StudioRoom.feature.gradient_maker.presentation.screenLogic.GradientMakerComponent
import com.RAZStudio.StudioRoom.feature.image_preview.presentation.ImagePreviewContent
import com.RAZStudio.StudioRoom.feature.image_preview.presentation.screenLogic.ImagePreviewComponent
import com.RAZStudio.StudioRoom.feature.image_stacking.presentation.ImageStackingContent
import com.RAZStudio.StudioRoom.feature.image_stacking.presentation.screenLogic.ImageStackingComponent
import com.RAZStudio.StudioRoom.feature.image_stitch.presentation.ImageStitchingContent
import com.RAZStudio.StudioRoom.feature.image_stitch.presentation.screenLogic.ImageStitchingComponent
import com.RAZStudio.StudioRoom.feature.load_net_image.presentation.LoadNetImageContent
import com.RAZStudio.StudioRoom.feature.load_net_image.presentation.screenLogic.LoadNetImageComponent
import com.RAZStudio.StudioRoom.feature.main.presentation.MainContent
import com.RAZStudio.StudioRoom.feature.main.presentation.screenLogic.MainComponent
import com.RAZStudio.StudioRoom.feature.mesh_gradients.presentation.MeshGradientsContent
import com.RAZStudio.StudioRoom.feature.mesh_gradients.presentation.screenLogic.MeshGradientsComponent
import com.RAZStudio.StudioRoom.feature.pick_color.presentation.PickColorFromImageContent
import com.RAZStudio.StudioRoom.feature.pick_color.presentation.screenLogic.PickColorFromImageComponent
import com.RAZStudio.StudioRoom.feature.resize_convert.presentation.ResizeAndConvertContent
import com.RAZStudio.StudioRoom.feature.resize_convert.presentation.screenLogic.ResizeAndConvertComponent
import com.RAZStudio.StudioRoom.feature.settings.presentation.SettingsContent
import com.RAZStudio.StudioRoom.feature.settings.presentation.screenLogic.SettingsComponent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.PhotoEditorContent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.screenLogic.PhotoEditorComponent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawDetailsEditorComponent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawDetailsEditorContent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawEditorContent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.share.ShareExportScreen
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawEditorComponent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw_8bit.Raw8BitEditorComponent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw_8bit.Raw8BitEditorContent
import com.RAZStudio.StudioRoom.image_splitting.presentation.ImageSplitterContent
import com.RAZStudio.StudioRoom.image_splitting.presentation.screenLogic.ImageSplitterComponent
import com.RAZStudio.StudioRoom.noise_generation.presentation.NoiseGenerationContent
import com.RAZStudio.StudioRoom.noise_generation.presentation.screenLogic.NoiseGenerationComponent


internal sealed interface NavigationChild {

    @Composable
    fun Content()

    class Main(private val component: MainComponent) : NavigationChild {
        @Composable
        override fun Content() = MainContent(component)
    }

    class CollageMaker(private val component: CollageMakerComponent) : NavigationChild {
        @Composable
        override fun Content() = CollageMakerContent(component)
    }

    class Compare(private val component: CompareComponent) : NavigationChild {
        @Composable
        override fun Content() = CompareContent(component)
    }

    class Crop(private val component: CropComponent) : NavigationChild {
        @Composable
        override fun Content() = CropContent(component)
    }

    class DeleteExif(private val component: DeleteExifComponent) : NavigationChild {
        @Composable
        override fun Content() = DeleteExifContent(component)
    }

    class Draw(private val component: DrawComponent) : NavigationChild {
        @Composable
        override fun Content() = DrawContent(component)
    }

    class EditExif(private val component: EditExifComponent) : NavigationChild {
        @Composable
        override fun Content() = EditExifContent(component)
    }

    class EraseBackground(private val component: EraseBackgroundComponent) : NavigationChild {
        @Composable
        override fun Content() = EraseBackgroundContent(component)
    }

    class Filter(private val component: FiltersComponent) : NavigationChild {
        @Composable
        override fun Content() = FiltersContent(component)
    }

    class GradientMaker(private val component: GradientMakerComponent) : NavigationChild {
        @Composable
        override fun Content() = GradientMakerContent(component)
    }

    class ImagePreview(private val component: ImagePreviewComponent) : NavigationChild {
        @Composable
        override fun Content() = ImagePreviewContent(component)
    }

    class ImageSplitting(private val component: ImageSplitterComponent) : NavigationChild {
        @Composable
        override fun Content() = ImageSplitterContent(component)
    }

    class ImageStacking(private val component: ImageStackingComponent) : NavigationChild {
        @Composable
        override fun Content() = ImageStackingContent(component)
    }

    class ImageStitching(private val component: ImageStitchingComponent) : NavigationChild {
        @Composable
        override fun Content() = ImageStitchingContent(component)
    }

    class LoadNetImage(private val component: LoadNetImageComponent) : NavigationChild {
        @Composable
        override fun Content() = LoadNetImageContent(component)
    }

    class MeshGradients(private val component: MeshGradientsComponent) : NavigationChild {
        @Composable
        override fun Content() = MeshGradientsContent(component)
    }

    class NoiseGeneration(private val component: NoiseGenerationComponent) : NavigationChild {
        @Composable
        override fun Content() = NoiseGenerationContent(component)
    }

    class PickColorFromImage(private val component: PickColorFromImageComponent) : NavigationChild {
        @Composable
        override fun Content() = PickColorFromImageContent(component)
    }

    class ResizeAndConvert(private val component: ResizeAndConvertComponent) : NavigationChild {
        @Composable
        override fun Content() = ResizeAndConvertContent(component)
    }

    class Settings(private val component: SettingsComponent) : NavigationChild {
        @Composable
        override fun Content() = SettingsContent(component)
    }

    class PhotoEditor(private val component: PhotoEditorComponent) : NavigationChild {
        @Composable
        override fun Content() = PhotoEditorContent(component)
    }

    class ShareExport(
        private val uris: List<android.net.Uri>,
        private val onGoBack: () -> Unit,
    ) : NavigationChild {
        @Composable
        override fun Content() = ShareExportScreen(uris = uris, onGoBack = onGoBack)
    }

    class RawEditor(private val component: RawEditorComponent) : NavigationChild {
        @Composable
        override fun Content() = RawEditorContent(component)
    }

    class Raw8BitEditor(private val component: Raw8BitEditorComponent) : NavigationChild {
        @Composable
        override fun Content() = Raw8BitEditorContent(component)
    }

    class RawDetailsEditor(private val component: RawDetailsEditorComponent) : NavigationChild {
        @Composable
        override fun Content() = RawDetailsEditorContent(component)
    }

    object Unavailable : NavigationChild {
        @Composable
        override fun Content() = Unit
    }

}
