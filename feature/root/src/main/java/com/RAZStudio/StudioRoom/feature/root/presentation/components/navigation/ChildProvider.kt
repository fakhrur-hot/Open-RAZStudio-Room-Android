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

import com.arkivanov.decompose.ComponentContext
import com.RAZStudio.StudioRoom.collage_maker.presentation.screenLogic.CollageMakerComponent
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.feature.canon_sync.presentation.screenLogic.CanonBatchDownloadComponent
import com.RAZStudio.StudioRoom.feature.canon_sync.presentation.screenLogic.CanonRemoteShootComponent
import com.RAZStudio.StudioRoom.feature.canon_sync.presentation.screenLogic.CanonSyncComponent
import com.RAZStudio.StudioRoom.feature.sony_sync.presentation.screenLogic.SonySyncComponent
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.GalleryWorkspaceComponent
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.AddToProjectComponent
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.GalleryProjectComponent
import com.RAZStudio.StudioRoom.feature.compare.presentation.screenLogic.CompareComponent
import com.RAZStudio.StudioRoom.feature.crop.presentation.screenLogic.CropComponent
import com.RAZStudio.StudioRoom.feature.delete_exif.presentation.screenLogic.DeleteExifComponent
import com.RAZStudio.StudioRoom.feature.draw.presentation.screenLogic.DrawComponent
import com.RAZStudio.StudioRoom.feature.edit_exif.presentation.screenLogic.EditExifComponent
import com.RAZStudio.StudioRoom.feature.erase_background.presentation.screenLogic.EraseBackgroundComponent
import com.RAZStudio.StudioRoom.feature.filters.presentation.screenLogic.FiltersComponent
import com.RAZStudio.StudioRoom.feature.gradient_maker.presentation.screenLogic.GradientMakerComponent
import com.RAZStudio.StudioRoom.feature.image_preview.presentation.screenLogic.ImagePreviewComponent
import com.RAZStudio.StudioRoom.feature.image_stacking.presentation.screenLogic.ImageStackingComponent
import com.RAZStudio.StudioRoom.feature.image_stitch.presentation.screenLogic.ImageStitchingComponent
import com.RAZStudio.StudioRoom.feature.load_net_image.presentation.screenLogic.LoadNetImageComponent
import com.RAZStudio.StudioRoom.feature.main.presentation.screenLogic.MainComponent
import com.RAZStudio.StudioRoom.feature.mesh_gradients.presentation.screenLogic.MeshGradientsComponent
import com.RAZStudio.StudioRoom.feature.pick_color.presentation.screenLogic.PickColorFromImageComponent
import com.RAZStudio.StudioRoom.feature.resize_convert.presentation.screenLogic.ResizeAndConvertComponent
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.CanonBatchDownload
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.CanonRemoteShoot
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.CanonSync
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.SonySync
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.GalleryWorkspace
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.AddToProject
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.GalleryProject
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.CollageMaker
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.Compare
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.Crop
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.DeleteExif
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.Draw
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.EditExif
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.EraseBackground
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.Filter
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.GradientMaker
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.ImagePreview
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.ImageSplitting
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.ImageStacking
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.ImageStitching
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.LoadNetImage
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.Main
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.MeshGradients
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.NoiseGeneration
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.PickColorFromImage
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.ResizeAndConvert
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.Settings
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.PhotoEditor
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.RawDetailsEditor
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.RawEditor
import com.RAZStudio.StudioRoom.feature.root.presentation.screenLogic.RootComponent
import com.RAZStudio.StudioRoom.feature.settings.presentation.screenLogic.SettingsComponent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.screenLogic.PhotoEditorComponent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawDetailsEditorComponent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawEditorComponent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw_8bit.Raw8BitEditorComponent
import com.RAZStudio.StudioRoom.feature.root.presentation.components.navigation.NavigationChild.Raw8BitEditor
import com.RAZStudio.StudioRoom.image_splitting.presentation.screenLogic.ImageSplitterComponent
import com.RAZStudio.StudioRoom.noise_generation.presentation.screenLogic.NoiseGenerationComponent
import javax.inject.Inject

internal class ChildProvider @Inject constructor(
    private val collageMakerComponentFactory: CollageMakerComponent.Factory,
    private val compareComponentFactory: CompareComponent.Factory,
    private val cropComponentFactory: CropComponent.Factory,
    private val deleteExifComponentFactory: DeleteExifComponent.Factory,
    private val drawComponentFactory: DrawComponent.Factory,
    private val editExifComponentFactory: EditExifComponent.Factory,
    private val eraseBackgroundComponentFactory: EraseBackgroundComponent.Factory,
    private val filtersComponentFactory: FiltersComponent.Factory,
    private val gradientMakerComponentFactory: GradientMakerComponent.Factory,
    private val imagePreviewComponentFactory: ImagePreviewComponent.Factory,
    private val imageStackingComponentFactory: ImageStackingComponent.Factory,
    private val imageStitchingComponentFactory: ImageStitchingComponent.Factory,
    private val imageSplittingComponentFactory: ImageSplitterComponent.Factory,
    private val loadNetImageComponentFactory: LoadNetImageComponent.Factory,
    private val mainComponentFactory: MainComponent.Factory,
    private val meshGradientsComponentFactory: MeshGradientsComponent.Factory,
    private val noiseGenerationComponentFactory: NoiseGenerationComponent.Factory,
    private val pickColorFromImageComponentFactory: PickColorFromImageComponent.Factory,
    private val resizeAndConvertComponentFactory: ResizeAndConvertComponent.Factory,
    private val settingsComponentFactory: SettingsComponent.Factory,
    private val photoEditorComponentFactory: PhotoEditorComponent.Factory,
    private val rawEditorComponentFactory: RawEditorComponent.Factory,
    private val raw8BitEditorComponentFactory: Raw8BitEditorComponent.Factory,
    private val rawDetailsEditorComponentFactory: RawDetailsEditorComponent.Factory,
    private val canonSyncComponentFactory: CanonSyncComponent.Factory,
    private val sonySyncComponentFactory: SonySyncComponent.Factory,
    private val galleryWorkspaceComponentFactory: GalleryWorkspaceComponent.Factory,
    private val addToProjectComponentFactory: AddToProjectComponent.Factory,
    private val galleryProjectComponentFactory: GalleryProjectComponent.Factory,
    private val canonRemoteShootComponentFactory: CanonRemoteShootComponent.Factory,
    private val canonBatchDownloadComponentFactory: CanonBatchDownloadComponent.Factory,
) {
    fun RootComponent.createChild(
        config: Screen,
        componentContext: ComponentContext
    ): NavigationChild = when (config) {

        Screen.Main -> Main(
            mainComponentFactory(
                componentContext = componentContext,
                onTryGetUpdate = ::tryGetUpdate,
                onGetClipList = ::updateUris,
                onNavigate = ::navigateToNew,
                isUpdateAvailable = isUpdateAvailable
            )
        )

        is Screen.CollageMaker -> CollageMaker(
            collageMakerComponentFactory(
                componentContext = componentContext,
                initialUris = config.uris,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        is Screen.Compare -> Compare(
            compareComponentFactory(
                componentContext = componentContext,
                initialComparableUris = config.uris
                    ?.takeIf { it.size == 2 }
                    ?.let { it[0] to it[1] },
                onGoBack = ::navigateBack
            )
        )

        is Screen.Crop -> Crop(
            cropComponentFactory(
                componentContext = componentContext,
                initialUri = config.uri,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        is Screen.DeleteExif -> DeleteExif(
            deleteExifComponentFactory(
                componentContext = componentContext,
                initialUris = config.uris,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        is Screen.Draw -> Draw(
            drawComponentFactory(
                componentContext = componentContext,
                initialUri = config.uri,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        is Screen.EditExif -> EditExif(
            editExifComponentFactory(
                componentContext = componentContext,
                initialUri = config.uri,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        is Screen.EraseBackground -> EraseBackground(
            eraseBackgroundComponentFactory(
                componentContext = componentContext,
                initialUri = config.uri,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        is Screen.Filter -> Filter(
            filtersComponentFactory(
                componentContext = componentContext,
                initialType = config.type,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        is Screen.GradientMaker -> GradientMaker(
            gradientMakerComponentFactory(
                componentContext = componentContext,
                initialUris = config.uris,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        is Screen.ImagePreview -> ImagePreview(
            imagePreviewComponentFactory(
                componentContext = componentContext,
                initialUris = config.uris,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        is Screen.ImageSplitting -> ImageSplitting(
            imageSplittingComponentFactory(
                componentContext = componentContext,
                initialUris = config.uri,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        is Screen.ImageStacking -> ImageStacking(
            imageStackingComponentFactory(
                componentContext = componentContext,
                initialUris = config.uris,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        is Screen.ImageStitching -> ImageStitching(
            imageStitchingComponentFactory(
                componentContext = componentContext,
                initialUris = config.uris,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        is Screen.LoadNetImage -> LoadNetImage(
            loadNetImageComponentFactory(
                componentContext = componentContext,
                initialUrl = config.url,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        is Screen.MeshGradients -> MeshGradients(
            meshGradientsComponentFactory(
                componentContext = componentContext,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        Screen.NoiseGeneration -> NoiseGeneration(
            noiseGenerationComponentFactory(
                componentContext = componentContext,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo,
            )
        )

        is Screen.PickColorFromImage -> PickColorFromImage(
            pickColorFromImageComponentFactory(
                componentContext = componentContext,
                initialUri = config.uri,
                onGoBack = ::navigateBack
            )
        )

        is Screen.ResizeAndConvert -> ResizeAndConvert(
            resizeAndConvertComponentFactory(
                componentContext = componentContext,
                initialUris = config.uris,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo
            )
        )

        is Screen.Settings -> Settings(
            settingsComponentFactory(
                componentContext = componentContext,
                onTryGetUpdate = ::tryGetUpdate,
                onNavigate = ::navigateToNew,
                isUpdateAvailable = isUpdateAvailable,
                onGoBack = ::navigateBack,
                initialSearchQuery = config.searchQuery
            )
        )

        is Screen.PhotoEditor -> PhotoEditor(
            photoEditorComponentFactory(
                componentContext = componentContext,
                initialUri = config.uri,
                fromRawEditor = config.fromRawEditor,
                onNavigate = ::navigateTo,
                onGoBack = ::navigateBack
            )
        )

        is Screen.RawEditor -> RawEditor(
            rawEditorComponentFactory(
                componentContext = componentContext,
                initialUri = config.uri,
                onNavigate = ::navigateTo,
                onGoBack = ::navigateBack,
                projectContext = config.projectContext,
            )
        )

        is Screen.Raw8BitEditor -> Raw8BitEditor(
            raw8BitEditorComponentFactory(
                componentContext = componentContext,
                initialUri = config.uri,
                onNavigate = ::navigateTo,
                onGoBack = ::navigateBack,
            )
        )

        is Screen.RawDetailsEditor -> RawDetailsEditor(
            rawDetailsEditorComponentFactory(
                componentContext = componentContext,
                initialUri = config.uri,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo,
            )
        )

        Screen.CanonSync -> CanonSync(
            canonSyncComponentFactory(
                componentContext = componentContext,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo,
            )
        )

        Screen.SonySync -> SonySync(
            sonySyncComponentFactory(
                componentContext = componentContext,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo,
            )
        )

        Screen.GalleryWorkspace -> GalleryWorkspace(
            galleryWorkspaceComponentFactory(
                componentContext = componentContext,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo,
            )
        )

        is Screen.AddToProject -> AddToProject(
            addToProjectComponentFactory(
                componentContext = componentContext,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo,
                uris = config.uris.orEmpty(),
            )
        )

        is Screen.GalleryProject -> GalleryProject(
            galleryProjectComponentFactory(
                componentContext = componentContext,
                onGoBack = ::navigateBack,
                onNavigate = ::navigateTo,
                projectId = config.projectId,
                revealPhotoId = config.revealPhotoId,
            )
        )

        Screen.CanonRemoteShoot -> CanonRemoteShoot(
            canonRemoteShootComponentFactory(
                componentContext = componentContext,
                onGoBack = ::navigateBack,
            )
        )

        Screen.CanonBatchDownload -> CanonBatchDownload(
            canonBatchDownloadComponentFactory(
                componentContext = componentContext,
                onGoBack = ::navigateBack,
            )
        )

        else -> Main(
            mainComponentFactory(
                componentContext = componentContext,
                onTryGetUpdate = ::tryGetUpdate,
                onGetClipList = ::updateUris,
                onNavigate = ::navigateToNew,
                isUpdateAvailable = isUpdateAvailable
            )
        )
    }
}
