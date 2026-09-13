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

import java.util.UUID

/**
 * Persisted per-camera connection profile. Keyed by [cameraMac] (the
 * camera's Wi-Fi MAC, 12 uppercase hex digits, no separators —
 * extracted from `<UDN>` in `CameraDevDesc.xml`). The MAC is stable
 * across power-cycles, pairing-slot resets, AND across AP-hotspot IP
 * lease changes, which makes it the right cache key. We DO NOT use
 * the camera's last-known IP as part of the key — DHCP leases on
 * Android tether are short-lived and the camera frequently lands on
 * a different `.x` every time it rejoins the hotspot. The IP we store
 * here is a hint for the fast-path probe; if it's stale, discovery
 * runs and updates it.
 *
 * Display-name priority for the Quick-Connect tile UI:
 *   1. [alias]         — user-typed via the long-press rename dialog
 *   2. [friendlyName]  — from `<friendlyName>` in CameraDevDesc.xml
 *   3. [serialNumber]  — from `<serialNumber>` in CameraDevDesc.xml
 *                        (fallback for unnamed cameras)
 *   4. [model]         — always present, e.g. "Canon EOS 6D"
 *
 * Cap: at most `CanonSyncPreferences.MAX_CAMERA_PROFILES` profiles
 * are remembered. Storage is MRU-ordered; on overflow the
 * least-recently-used profile is evicted.
 */
data class CameraProfile(
    /** Camera's Wi-Fi MAC, 12 uppercase hex digits, no separators. Primary key. */
    val cameraMac: String,
    /**
     * Last-known IP the camera held on the phone's hotspot. Used only
     * as a fast-path probe target; if connect against this IP fails,
     * discovery resolves the new IP and the profile is updated.
     * NOT part of the identity.
     */
    val lastIp: String,
    /** Host GUID we successfully paired with — must reuse on reconnect. */
    val hostGuid: UUID,
    /** Model name from CameraDevDesc.xml (e.g. "Canon EOS 6D"). */
    val model: String,
    /** Friendly name from CameraDevDesc.xml (e.g. "Canon EOS 6D"). */
    val friendlyName: String,
    /**
     * Camera-body serial number from CameraDevDesc.xml's `<serialNumber>`.
     * Used as a display fallback when [friendlyName] is empty or matches
     * [model] verbatim (which means the user never set a nickname on
     * the body).
     */
    val serialNumber: String,
    /**
     * Optional user-typed alias, set via the Quick-Connect tile's
     * long-press rename dialog. Null when the user hasn't renamed.
     * Wins over [friendlyName] / [serialNumber] in the tile UI when
     * present.
     */
    val alias: String?,
    /** epoch-ms of the last successful PTP session with this camera. */
    val lastSuccessEpochMs: Long,
) {
    /**
     * Display name for the Quick-Connect tile UI. Follows the priority
     * cascade documented on this class — alias trumps everything.
     */
    val displayName: String get() = when {
        !alias.isNullOrBlank() -> alias
        friendlyName.isNotBlank() && friendlyName != model -> friendlyName
        serialNumber.isNotBlank() -> "$model · $serialNumber"
        else -> model
    }

    /**
     * Sanitized display name suitable for use as a folder name. Stripped
     * of separator characters and trimmed. Used to derive the
     * per-camera Working Directory default.
     */
    val folderSafeName: String get() = displayName
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .take(64)
        .ifBlank { cameraMac }
}
