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
 * Decoded `GetDevicePropDesc (0x1014)` reply for one DPC. Everything the UI
 * needs to render a chip + picker for a property:
 *
 *  - the current camera-side value
 *  - whether the host is allowed to write it ([writable])
 *  - the legal value set, modelled as either an enumeration of discrete
 *    values ([allowedValues]) or a numeric range ([range]).
 *
 * Values are stored as `Long` regardless of underlying wire data-type
 * (INT8 / UINT8 / INT16 / UINT16 / INT32 / UINT32) so a single chip
 * picker can render any property. Lossy for nothing the EOS DPCs we
 * touch — none of them use INT64 or float.
 *
 * Strings and packed multi-byte payloads (e.g. WB shift A/B + M/G in a
 * single property) are NOT modelled here: they get specialised
 * descriptors in a later slice. For Slice A/C/E every interesting DPC
 * is a scalar enum or scalar range.
 */
data class EosPropertyDescriptor(
    val dpcCode: Int,
    val dataType: Int,
    val writable: Boolean,
    val currentValue: Long,
    val factoryDefault: Long,
    val form: Form,
) {
    sealed interface Form {
        data object None : Form
        data class Range(val min: Long, val max: Long, val step: Long) : Form
        data class Enum(val values: List<Long>) : Form
    }

    /** Convenience: legal values the chip should let the user pick, or empty for free range. */
    val allowedValues: List<Long>
        get() = when (form) {
            is Form.Enum -> form.values
            else -> emptyList()
        }

    val range: Form.Range?
        get() = form as? Form.Range
}
