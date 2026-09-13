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

package com.RAZStudio.StudioRoom.feature.gallery_workspace.di

import com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar.GalleryProjectEditorPortImpl
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.project.GalleryProjectEditorPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds [GalleryProjectEditorPort] (declared in feature/photo-editor, which this module
 * cannot depend back on) to this module's implementation. Hilt's graph is assembled at the
 * app level regardless of which Gradle module declares a binding, so `RawEditorComponent`
 * (photo-editor) resolves this the same way it would any other injected dependency — see the
 * port interface's doc for the full reasoning.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GalleryProjectEditorModule {

    @Binds
    abstract fun bindGalleryProjectEditorPort(
        impl: GalleryProjectEditorPortImpl,
    ): GalleryProjectEditorPort
}
