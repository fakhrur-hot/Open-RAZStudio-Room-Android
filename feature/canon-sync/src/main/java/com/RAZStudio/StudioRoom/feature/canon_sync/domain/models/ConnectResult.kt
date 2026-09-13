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

package com.RAZStudio.StudioRoom.feature.canon_sync.domain.models

/**
 * Typed outcome of a [com.RAZStudio.StudioRoom.feature.canon_sync.net.CanonWifiClient]
 * connect attempt. We surface enough detail for the Connection Settings dialog's
 * Diagnostics section to give a precise reason on failure.
 */
sealed interface ConnectResult {

    data class Success(val camera: CameraDescriptor) : ConnectResult

    sealed interface Failure : ConnectResult {

        /** TCP connect to the camera AP failed (no route, refused, timeout). */
        data class SocketUnreachable(val cause: String) : Failure

        /** Camera sent INIT_FAIL — likely another host owns the session. */
        data class InitRejected(val reasonCode: Int, val reasonText: String) : Failure

        /** OpenSession (or EOS_SetRemoteMode / EOS_SetEventMode) returned non-OK. */
        data class PtpResponseError(val operationCode: Int, val responseCode: Int) : Failure

        /** Wire-level read failure (premature socket close, malformed packet). */
        data class ProtocolError(val message: String) : Failure
    }
}
