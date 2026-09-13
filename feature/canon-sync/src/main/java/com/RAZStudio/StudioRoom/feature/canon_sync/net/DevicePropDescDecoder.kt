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

package com.RAZStudio.StudioRoom.feature.canon_sync.net

import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.EosPropertyDescriptor
import okio.Buffer
import okio.BufferedSource

/**
 * Wire-level decoder for the `GetDevicePropDesc (0x1014)` data-phase
 * payload. The standard-PTP descriptor format:
 *
 * ```
 * uint16 devicePropCode
 * uint16 dataType
 * uint8  getSet              (0 = read-only, 1 = read/write)
 * <DataType> factoryDefault
 * <DataType> currentValue
 * uint8  formFlag            (0 = none, 1 = range, 2 = enum)
 * [if formFlag == 1] <DataType> min, <DataType> max, <DataType> step
 * [if formFlag == 2] uint16 numValues; <DataType>[] values
 * ```
 *
 * Canon EOS bodies reuse this exact format for their vendor DPCs, so we
 * decode once and the result feeds every chip in the live shooting UI.
 */
internal object DevicePropDescDecoder {

    /**
     * Decode the next descriptor record at the head of [source]. Returns
     * null when the payload is malformed (short read, unrecognised data
     * type) — callers should treat null as a transient failure and retry
     * on the next event.
     */
    fun decode(source: BufferedSource): EosPropertyDescriptor? {
        if (!source.request(5)) return null
        val dpc = source.readShortLe().toInt() and 0xFFFF
        val dataType = source.readShortLe().toInt() and 0xFFFF
        val getSet = source.readByte().toInt() and 0xFF
        val factoryDefault = readValue(source, dataType) ?: return null
        val currentValue = readValue(source, dataType) ?: return null
        if (!source.request(1)) return null
        val formFlag = source.readByte().toInt() and 0xFF
        val form = when (formFlag) {
            FORM_FLAG_NONE -> EosPropertyDescriptor.Form.None
            FORM_FLAG_RANGE -> {
                val min = readValue(source, dataType) ?: return null
                val max = readValue(source, dataType) ?: return null
                val step = readValue(source, dataType) ?: return null
                EosPropertyDescriptor.Form.Range(min, max, step)
            }
            FORM_FLAG_ENUM -> {
                if (!source.request(2)) return null
                val count = source.readShortLe().toInt() and 0xFFFF
                val values = ArrayList<Long>(count)
                repeat(count) {
                    val v = readValue(source, dataType) ?: return null
                    values += v
                }
                EosPropertyDescriptor.Form.Enum(values)
            }
            else -> EosPropertyDescriptor.Form.None  // unknown flag, treat as opaque
        }
        return EosPropertyDescriptor(
            dpcCode = dpc,
            dataType = dataType,
            writable = getSet == 1,
            currentValue = currentValue,
            factoryDefault = factoryDefault,
            form = form,
        )
    }

    /**
     * Read a single value of [dataType] from [source] as a `Long`. Returns
     * null for short reads or unrecognised types. Negative wire values
     * stay signed; we don't widen UINT* to a positive-space Long because
     * the EOS DPCs we read (ISO, Tv, Av, EV…) are all small enough that
     * even sign-extended INT8 = -128 round-trips correctly through the
     * picker layer.
     */
    private fun readValue(source: BufferedSource, dataType: Int): Long? = when (dataType) {
        DATA_TYPE_INT8 -> if (source.request(1)) source.readByte().toLong() else null
        DATA_TYPE_UINT8 -> if (source.request(1)) (source.readByte().toLong() and 0xFFL) else null
        DATA_TYPE_INT16 -> if (source.request(2)) source.readShortLe().toLong() else null
        DATA_TYPE_UINT16 -> if (source.request(2)) (source.readShortLe().toLong() and 0xFFFFL) else null
        DATA_TYPE_INT32 -> if (source.request(4)) source.readIntLe().toLong() else null
        DATA_TYPE_UINT32 -> if (source.request(4)) (source.readIntLe().toLong() and 0xFFFFFFFFL) else null
        else -> null
    }

    /** Standalone helper: read just the current-value slot if you already know the type. */
    fun readScalar(source: BufferedSource, dataType: Int): Long? = readValue(source, dataType)

    /** Snapshot-on-demand convenience for tests + DI consumers. */
    fun decodeOrNull(payload: Buffer): EosPropertyDescriptor? = decode(payload)

    private const val DATA_TYPE_INT8 = 0x0001
    private const val DATA_TYPE_UINT8 = 0x0002
    private const val DATA_TYPE_INT16 = 0x0003
    private const val DATA_TYPE_UINT16 = 0x0004
    private const val DATA_TYPE_INT32 = 0x0005
    private const val DATA_TYPE_UINT32 = 0x0006

    private const val FORM_FLAG_NONE = 0
    private const val FORM_FLAG_RANGE = 1
    private const val FORM_FLAG_ENUM = 2
}
