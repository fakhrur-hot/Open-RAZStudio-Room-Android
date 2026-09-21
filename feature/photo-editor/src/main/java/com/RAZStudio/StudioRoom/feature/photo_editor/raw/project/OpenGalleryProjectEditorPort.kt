package com.RAZStudio.StudioRoom.feature.photo_editor.raw.project

import android.graphics.Bitmap
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class OpenGalleryProjectEditorPort @Inject constructor() : GalleryProjectEditorPort {
    override fun resolverFor(projectId: Long, photoId: Long, displayName: String): SidecarResolver =
        error("Gallery Workspace is unavailable in Open")
    override suspend fun hasEditRecord(projectId: Long, photoId: Long) = false
    override suspend fun createInitialSidecar(
        projectId: Long, photoId: Long, displayName: String, config: WorkspaceConfig,
    ) = Unit
    override suspend fun applyLensProfileToMatching(
        projectId: Long, sourcePhotoId: Long, config: WorkspaceConfig,
    ) = GalleryProjectEditorPort.MatchResult(0, 0)
    override suspend fun updateThumbnail(projectId: Long, photoId: Long, edited: Bitmap) = Unit
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class OpenGalleryProjectEditorModule {
    @Binds
    abstract fun bindOpenGalleryProjectEditorPort(
        impl: OpenGalleryProjectEditorPort,
    ): GalleryProjectEditorPort
}