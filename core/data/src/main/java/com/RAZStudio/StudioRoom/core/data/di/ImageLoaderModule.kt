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

package com.RAZStudio.StudioRoom.core.data.di

import android.content.Context
import android.os.Build
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.allowHardware
import coil3.request.maxBitmapSize
import coil3.size.Size
import coil3.util.Logger
import com.awxkee.jxlcoder.coil.AnimatedJxlDecoder
import com.gemalto.jp2.coil.Jpeg2000Decoder
import com.github.awxkee.avifcoil.decoder.HeifDecoder
import com.hashsequence.coilresvg.ResvgDecoder
import com.t8rin.awebp.coil.AnimatedWebPDecoder
import com.t8rin.djvu_coder.coil.DjvuDecoder
import com.RAZStudio.StudioRoom.core.data.coil.Base64Fetcher
import com.RAZStudio.StudioRoom.core.data.coil.CoilLogger
import com.RAZStudio.StudioRoom.core.data.coil.NefDecoder
import com.RAZStudio.StudioRoom.core.data.coil.PdfDecoder
import com.RAZStudio.StudioRoom.core.data.coil.TiffDecoder
import com.RAZStudio.StudioRoom.core.data.coil.TimeMeasureInterceptor
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.resources.BuildConfig
import com.t8rin.psd.coil.PsdDecoder
import com.t8rin.qoi_coder.coil.QoiDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import oupson.apng.coil.AnimatedPngDecoder
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object ImageLoaderModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        logger: Logger?,
        componentRegistry: ComponentRegistry,
        dispatchersHolder: DispatchersHolder
    ): ImageLoader = context.imageLoader.newBuilder()
        .components(componentRegistry)
        .coroutineContext(dispatchersHolder.defaultDispatcher)
        .decoderCoroutineContext(dispatchersHolder.decodingDispatcher)
        .fetcherCoroutineContext(dispatchersHolder.ioDispatcher)
        .allowHardware(false)
        // Cap decode resolution. Was Size.ORIGINAL, which meant a 24 MP photo decoded
        // to a ~96 MB ARGB_8888 bitmap and lived in the LOS until evicted. The picker
        // pager (which keeps ~3 pages alive after the lookahead fix) could spike heap
        // by ~300 MB on a high-res library. 3072 px caps a typical photo at ~27 MB
        // without visible quality loss on any current phone screen.
        .maxBitmapSize(Size(3072, 3072))
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.cacheDir, "coil").apply(File::mkdirs))
                .maxSizePercent(0.2)
                .cleanupCoroutineContext(dispatchersHolder.ioDispatcher)
                .build()
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.3)
                .build()
        }
        .logger(logger)
        .build()
        .also(SingletonImageLoader::setUnsafe)

    @Provides
    @Singleton
    fun provideCoilLogger(): Logger = CoilLogger()

    @Provides
    @Singleton
    fun provideComponentRegistry(
        client: HttpClient
    ): ComponentRegistry = ComponentRegistry.Builder()
        .apply {
            add(
                KtorNetworkFetcherFactory(
                    httpClient = client,
                    concurrentRequestStrategy = DeDupeConcurrentRequestStrategy()
                )
            )
            add(AnimatedPngDecoder.Factory())
            if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory())
            else {
                add(GifDecoder.Factory())
                add(AnimatedWebPDecoder.Factory())
            }
            add(ResvgDecoder.Factory())
            add(HeifDecoder.Factory())
            add(AnimatedJxlDecoder.Factory())
            add(Jpeg2000Decoder.Factory())
            add(NefDecoder.Factory())
            add(TiffDecoder.Factory())
            add(QoiDecoder.Factory())
            add(PsdDecoder.Factory())
            add(DjvuDecoder.Factory())
            add(Base64Fetcher.Factory())
            add(PdfDecoder.Factory())
            if (BuildConfig.DEBUG) add(TimeMeasureInterceptor)
        }
        .build()

}