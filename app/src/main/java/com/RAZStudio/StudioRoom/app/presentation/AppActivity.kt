/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2024 RAZStudio (Fakhrurraze)
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

package com.RAZStudio.StudioRoom.app.presentation

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.retainedComponent
import com.RAZStudio.StudioRoom.core.domain.pip.PipStateHolder
import com.RAZStudio.StudioRoom.core.ui.utils.ComposeActivity
import com.RAZStudio.StudioRoom.feature.root.presentation.RootContent
import com.RAZStudio.StudioRoom.feature.root.presentation.screenLogic.RootComponent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AppActivity : ComposeActivity() {

    @Inject
    lateinit var rootComponentFactory: RootComponent.Factory

    /**
     * App-scoped flag flipped by long-running pipelines (currently the
     * Canon Sync Download & Process coordinator). When true and the
     * user backgrounds the activity (Home key, Recents), we auto-enter
     * Picture-in-Picture so they can keep an eye on progress.
     */
    @Inject
    lateinit var pipState: PipStateHolder

    private val component: RootComponent by lazy {
        retainedComponent(factory = rootComponentFactory::invoke)
    }

    override fun handleIntent(intent: Intent) = component.handleDeeplinks(intent)

    @Composable
    override fun Content() = RootContent(component = component)

    /**
     * Auto-enter PiP on Home / Recents while a tracked pipeline is in
     * flight. Android 8+ requires <android:supportsPictureInPicture> on
     * the activity (set in AndroidManifest). The 16:9 aspect ratio is
     * the most universally accepted; some launchers reject extreme
     * ratios. Failure is non-fatal — we just stay on screen.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!pipState.state.value.active) return
        runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }
}