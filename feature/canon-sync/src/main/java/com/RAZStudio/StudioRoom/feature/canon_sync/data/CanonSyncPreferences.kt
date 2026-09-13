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

package com.RAZStudio.StudioRoom.feature.canon_sync.data

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.FormatMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent storage for the Canon Sync session state that should survive
 * process death.
 *
 *  - [armedFolderUri]: the user's currently-armed capture folder. Persisted so
 *    reopening the app after a kill restores the green tick on the right tile.
 *  - [formatMode]: RAW / JPEG / RAW+JPEG selection from the Connection
 *    Settings dialog. Persisted because the user makes this choice once and
 *    expects it to stick.
 *  - [hostGuid]: per-install stable UUID used during PTP/IP pairing. Reused
 *    across sessions so the camera's "remembered hosts" list doesn't fill up
 *    with random entries every time we reconnect (some bodies cap that list
 *    at 5-10 slots).
 *
 * Backed by the existing app-global [DataStore] from
 * `com.RAZStudio.StudioRoom.core.data.di.LocalModule`. All keys are
 * `canon_sync.*`-prefixed so they don't collide with the rest of the app's
 * preferences.
 */
@Singleton
internal class CanonSyncPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /**
     * Cold flow of the armed folder Uri. Emits null when no folder is armed
     * or when the stored Uri is malformed.
     */
    val armedFolderUri: Flow<Uri?> = dataStore.data.map { prefs ->
        prefs[KEY_ARMED_FOLDER_URI]?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    /** Cold flow of the user's format selection. Defaults to RAW. */
    val formatMode: Flow<FormatMode> = dataStore.data.map { prefs ->
        prefs[KEY_FORMAT_MODE]?.let { stored ->
            runCatching { FormatMode.valueOf(stored) }.getOrNull()
        } ?: FormatMode.RAW
    }

    suspend fun setArmedFolderUri(uri: Uri?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(KEY_ARMED_FOLDER_URI)
            else prefs[KEY_ARMED_FOLDER_URI] = uri.toString()
        }
    }

    suspend fun setFormatMode(mode: FormatMode) {
        dataStore.edit { prefs ->
            prefs[KEY_FORMAT_MODE] = mode.name
        }
    }

    /**
     * Return the persisted host GUID, generating and storing one on first
     * call. The read-or-generate is performed inside a single transactional
     * [edit] block so concurrent callers can't race into producing two
     * different UUIDs.
     *
     * Format: `00000000-0000-0000-0001-{12_HEX}` — the Canon-namespaced
     * "host identifier" pattern. The trailing 12 hex digits are nominally
     * the host's Wi-Fi MAC (that's how EOS Utility on a PC builds it), but
     * Android 6+ randomises the MAC reported to apps, so we derive a
     * stable 12-hex-digit per-install identifier from a one-shot
     * UUID.randomUUID() and persist it. The 6D firmware doesn't validate
     * the MAC against anything; it just needs:
     *   1. The fixed `00000000-0000-0000-0001-` prefix (Canon namespace).
     *   2. A 12-hex tail that ISN'T the `FFFFFFFFFFFF` sentinel.
     * Without that prefix the camera treats the host as anonymous and
     * never fires its "EOS Utility found — Pair?" on-body prompt, so
     * the user is stuck on "Searching for EOS Utility" forever.
     */
    suspend fun hostGuid(): UUID {
        // First peek without a transaction so the fast path doesn't pay for
        // the edit() overhead on every call.
        val existing = dataStore.data.first()[KEY_HOST_GUID]
        if (existing != null) {
            runCatching {
                val parsed = UUID.fromString(existing)
                if (isCanonNamespacedHostGuid(parsed)) return parsed
                // Persisted a legacy non-Canon GUID from an earlier build —
                // fall through to regenerate in Canon format.
            }
        }
        // Slow path: generate-or-read atomically. We always (re)write a
        // Canon-namespaced GUID; if the stored value is in the old random
        // format we silently upgrade it.
        var resolved: UUID? = null
        dataStore.edit { prefs ->
            val current = prefs[KEY_HOST_GUID]
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?.takeIf { isCanonNamespacedHostGuid(it) }
            if (current != null) {
                resolved = current
            } else {
                val generated = buildCanonNamespacedHostGuid()
                prefs[KEY_HOST_GUID] = generated.toString()
                resolved = generated
            }
        }
        return resolved!!
    }

    private fun isCanonNamespacedHostGuid(guid: UUID): Boolean {
        // Canon namespace: msb = 0x0000000000000000, lsb = 0x0001_{12_HEX}
        // i.e. the most-significant 64 bits are zero AND the
        // first 16 bits of the least-significant half are 0x0001.
        return guid.mostSignificantBits == 0L &&
            (guid.leastSignificantBits ushr 48) == 0x0001L &&
            guid.leastSignificantBits.toULong() != 0x0001_FFFFFFFFFFFFuL.toLong().toULong()
    }

    private fun buildCanonNamespacedHostGuid(): UUID {
        // Derive 12 stable hex digits from a fresh random UUID — equivalent
        // to picking a random 48-bit "MAC". Avoid the all-ones sentinel
        // (FFFFFFFFFFFF) because the 6D firmware reserves it for "no host
        // paired yet" and won't accept it from an InitCommand.
        var tail = java.util.UUID.randomUUID().leastSignificantBits and 0x0000_FFFF_FFFF_FFFFL
        if (tail == 0x0000_FFFF_FFFF_FFFFL) tail = tail xor 1L
        val lsb = (0x0001L shl 48) or tail
        return UUID(0L, lsb)
    }

    // ─────────────────────────── Camera profiles ────────────────────────
    //
    // Per-camera persistent connection profile, keyed by the camera's
    // Wi-Fi MAC (12 uppercase hex digits, extracted from CameraDevDesc.xml's
    // <UDN>). The MAC is stable across power-cycles + pairing-slot resets,
    // so caching everything else by it lets reconnect skip SSDP/scan and
    // go straight to InitCommand.
    //
    // Storage encoding: one DataStore string key per camera, value is a
    // pipe-delimited tuple:
    //   "{ip}|{hostGuid}|{model}|{friendlyName}|{lastSuccessEpochMs}"
    //
    // We don't bother with JSON because every field is a primitive and
    // none can contain a literal '|'.

    /**
     * Cold flow of every persisted camera profile, sorted MRU-first
     * (most-recently-used at index 0). Drives the Quick-Connect UI;
     * capped at [MAX_CAMERA_PROFILES].
     */
    val cameraProfiles: Flow<List<CameraProfile>> = dataStore.data.map { prefs ->
        val macs = prefs[KEY_CAMERA_PROFILE_INDEX]?.split(',')
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        macs.mapNotNull { mac ->
            prefs[cameraProfileKey(mac)]?.let { parseCameraProfile(mac, it) }
        }
    }

    /**
     * Look up the persisted profile for [cameraMac], or null if we've
     * never successfully paired with this camera.
     */
    suspend fun cameraProfile(cameraMac: String): CameraProfile? {
        val key = cameraProfileKey(cameraMac.uppercase())
        val raw = dataStore.data.first()[key] ?: return null
        return parseCameraProfile(cameraMac.uppercase(), raw)
    }

    /**
     * Persist a successful connection's metadata. Called from
     * `CanonSyncRepository.connect` right after the PTP session goes live,
     * so a subsequent reconnect can skip discovery entirely.
     *
     * Profile cap: at most [MAX_CAMERA_PROFILES] cameras are remembered.
     * On overflow we LRU-evict — drop the least-recently-used MAC from
     * the index AND remove its payload key, so DataStore stays bounded.
     */
    suspend fun saveCameraProfile(profile: CameraProfile) {
        val mac = profile.cameraMac.uppercase()
        dataStore.edit { prefs ->
            prefs[cameraProfileKey(mac)] = encodeCameraProfile(profile)
            // Move-to-front in the index.
            val current = prefs[KEY_CAMERA_PROFILE_INDEX]
                ?.split(',')?.filter { it.isNotBlank() && it != mac } ?: emptyList()
            val reordered = (listOf(mac) + current).take(MAX_CAMERA_PROFILES)
            // Anything that fell off the tail gets its payload wiped too.
            val evicted = current.drop(MAX_CAMERA_PROFILES - 1)
            evicted.forEach { evictedMac -> prefs.remove(cameraProfileKey(evictedMac)) }
            prefs[KEY_CAMERA_PROFILE_INDEX] = reordered.joinToString(",")
        }
    }

    /**
     * Rename a camera profile's user-typed alias. Pass null to clear.
     * Idempotent — no-op if the profile doesn't exist. The profile's
     * MRU position stays as-is (we don't bump it on rename; only
     * connection success bumps).
     */
    suspend fun setCameraProfileAlias(cameraMac: String, alias: String?) {
        val current = cameraProfile(cameraMac) ?: return
        saveCameraProfile(current.copy(alias = alias?.trim()?.takeIf { it.isNotBlank() }))
    }

    /**
     * Wipe a single camera's profile — invoked from the Quick-Connect UI's
     * delete-with-confirmation flow, or after enough failed reconnects that
     * we're sure the cached IP is stale and re-pairing is needed.
     */
    suspend fun forgetCameraProfile(cameraMac: String) {
        val mac = cameraMac.uppercase()
        dataStore.edit { prefs ->
            prefs.remove(cameraProfileKey(mac))
            val current = prefs[KEY_CAMERA_PROFILE_INDEX]
                ?.split(',')?.filter { it.isNotBlank() && it != mac } ?: emptyList()
            if (current.isEmpty()) prefs.remove(KEY_CAMERA_PROFILE_INDEX)
            else prefs[KEY_CAMERA_PROFILE_INDEX] = current.joinToString(",")
        }
    }

    private fun cameraProfileKey(mac: String) =
        stringPreferencesKey("canon_sync.camera_profile.${mac.uppercase()}")

    /**
     * Persistence encoding. v1 was `ip|guid|model|friendlyName|ts` (5
     * fields, no alias / serial). v2 prepends `v2|` and adds the new
     * fields: `v2|ip|guid|model|friendlyName|serial|alias|ts` (8
     * pipe-separated values after the version tag). The leading `v2|`
     * lets us upgrade gracefully — older builds wrote v1 without a
     * marker, so anything that starts with `v2|` is the new format.
     *
     * We use `` to encode an empty alias (because Kotlin's
     * String.split silently drops trailing empties when limit isn't
     * provided; pipe-with-empty-tail is ambiguous).  doesn't
     * occur in any other field.
     */
    private fun encodeCameraProfile(p: CameraProfile): String = listOf(
        "v2",
        p.lastIp,
        p.hostGuid.toString(),
        p.model,
        p.friendlyName,
        p.serialNumber,
        p.alias ?: "",
        p.lastSuccessEpochMs.toString(),
    ).joinToString("|")

    private fun parseCameraProfile(mac: String, raw: String): CameraProfile? {
        val parts = raw.split('|')
        return when {
            parts.firstOrNull() == "v2" && parts.size == 8 -> parseV2(mac, parts)
            parts.size == 5 -> parseV1(mac, parts)
            else -> null
        }
    }

    private fun parseV2(mac: String, parts: List<String>): CameraProfile? {
        val guid = runCatching { UUID.fromString(parts[2]) }.getOrNull() ?: return null
        val ts = parts[7].toLongOrNull() ?: return null
        return CameraProfile(
            cameraMac = mac,
            lastIp = parts[1],
            hostGuid = guid,
            model = parts[3],
            friendlyName = parts[4],
            serialNumber = parts[5],
            alias = parts[6].takeIf { it != "" },
            lastSuccessEpochMs = ts,
        )
    }

    private fun parseV1(mac: String, parts: List<String>): CameraProfile? {
        val guid = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return null
        val ts = parts[4].toLongOrNull() ?: return null
        return CameraProfile(
            cameraMac = mac,
            lastIp = parts[0],
            hostGuid = guid,
            model = parts[2],
            friendlyName = parts[3],
            serialNumber = "",
            alias = null,
            lastSuccessEpochMs = ts,
        )
    }

    companion object {
        private val KEY_ARMED_FOLDER_URI = stringPreferencesKey("canon_sync.armed_folder_uri")
        private val KEY_FORMAT_MODE = stringPreferencesKey("canon_sync.format_mode")
        private val KEY_HOST_GUID = stringPreferencesKey("canon_sync.host_guid")
        /** Comma-separated MRU MAC list — sequencing index for [cameraProfiles]. */
        private val KEY_CAMERA_PROFILE_INDEX =
            stringPreferencesKey("canon_sync.camera_profile_index")
        /** Hard cap on remembered cameras. UI surfaces all of these as quick-connect tiles. */
        const val MAX_CAMERA_PROFILES = 5
    }
}
