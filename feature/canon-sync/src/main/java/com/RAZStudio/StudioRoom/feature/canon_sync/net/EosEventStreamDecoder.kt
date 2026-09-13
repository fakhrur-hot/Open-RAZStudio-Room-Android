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

import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.PtpEvent
import okio.Buffer
import okio.BufferedSource

/**
 * Decoder for Canon EOS event records carried inside an
 * [PtpIpConstants.OC_EOS_GET_EVENT] (0x9116) data-phase blob.
 *
 * The blob is a concatenated stream of variable-length records:
 *
 * ```
 * [recordSize:uint32 LE][eventCode:uint32 LE][payload: recordSize - 8 bytes]
 * [recordSize:uint32 LE][eventCode:uint32 LE][payload: recordSize - 8 bytes]
 * ...
 * [recordSize=8][eventCode=0]                              <- terminator
 * ```
 *
 * We never trust `recordSize` blindly — a malformed record could claim a size
 * larger than the remaining blob and walk us off the end of the buffer. Each
 * `record.size` is clamped against `remainingBytes` before payload read.
 */
internal object EosEventStreamDecoder {

    /**
     * Drain the entire blob into a list of typed events.
     *
     * @param source The data-phase buffer for one [PtpIpConstants.OC_EOS_GET_EVENT]
     *   response. Will be fully consumed by the time this returns.
     * @param transactionId The txn ID from the GetEvent OperationResponse; we
     *   attach it to every emitted [PtpEvent] for traceability.
     * @return Events in arrival order. The terminator and any malformed
     *   tail bytes are silently discarded.
     */
    fun decode(source: BufferedSource, transactionId: Int): List<PtpEvent> {
        val events = ArrayList<PtpEvent>()
        while (true) {
            // Each record needs at least the 8-byte header. If fewer bytes remain
            // we treat the tail as garbage and stop.
            if (!source.request(EOS_RECORD_HEADER_SIZE.toLong())) return events
            val recordSize = source.readIntLe()
            val eventCode = source.readIntLe()

            // Terminator: record(size=8, code=0) marks end-of-blob.
            if (recordSize == EOS_RECORD_HEADER_SIZE && eventCode == PtpIpConstants.EC_EOS_STREAM_TERMINATOR) {
                return events
            }

            // Defensive sanity check — Canon firmware that drops a malformed
            // record could otherwise drive us into negative-payload territory.
            if (recordSize < EOS_RECORD_HEADER_SIZE) return events

            val payloadSize = (recordSize - EOS_RECORD_HEADER_SIZE).toLong()
            if (!source.request(payloadSize)) return events

            // We copy the payload into a self-contained Buffer so each parser
            // operates on its own bounded source. This avoids the parser
            // accidentally reading into the next record on a malformed field.
            val payload = Buffer().apply { source.read(this, payloadSize) }
            decodeRecord(eventCode, payload, transactionId)?.let(events::add)
        }
    }

    private fun decodeRecord(
        eventCode: Int,
        payload: Buffer,
        transactionId: Int,
    ): PtpEvent? = when (eventCode) {
        PtpIpConstants.EC_EOS_OBJECT_ADDED_EX -> parseObjectAddedEx(payload, transactionId)
        PtpIpConstants.EC_EOS_OBJECT_ADDED_EX_64 -> parseObjectAddedEx64(payload, transactionId)
        PtpIpConstants.EC_EOS_OBJECT_INFO_CHANGED_EX -> parseObjectInfoChangedEx(payload, transactionId)
        PtpIpConstants.EC_EOS_OBJECT_REMOVED -> parseObjectRemoved(payload, transactionId)
        PtpIpConstants.EC_EOS_DEVICE_PROP_CHANGED,
        PtpIpConstants.EC_EOS_PROPERTY_VALUE_CHANGED -> parsePropertyChanged(payload, transactionId)
        PtpIpConstants.EC_EOS_OLC_INFO_CHANGED -> parseOlcInfo(payload, transactionId)
        else -> {
            // Unknown EOS record: emit as Unknown with the first three uint32s as parameters.
            val params = ArrayList<Int>(3)
            repeat(3) { if (payload.request(4)) params += payload.readIntLe() }
            PtpEvent.Unknown(transactionId, eventCode, params)
        }
    }

    /**
     * Decode a `EOS_DevicePropChanged` / `EOS_PropertyValueChanged` record.
     * Payload starts with the affected DPC code as a uint32 LE. The
     * remainder is descriptor/value bytes that we don't need to parse here
     * — the [com.RAZStudio.StudioRoom.feature.canon_sync.domain.EosPropertyController]
     * re-fetches the full descriptor whenever it sees this event.
     */
    private fun parsePropertyChanged(payload: Buffer, transactionId: Int): PtpEvent.EosPropertyChanged? {
        if (!payload.request(4)) return null
        val dpc = payload.readIntLe()
        return PtpEvent.EosPropertyChanged(transactionId, dpc)
    }

    /**
     * Decode a `EOS_OLCInfoChanged (0xC18A)` record. The wire format
     * is a uint32 bitmask header followed by sparse field-specific
     * blobs (camlib `ptp_canon_eos_getolcinfo` is the reference).
     *
     * For the status row we only need a few flags from the header:
     *   bit 4 (0x10) = AE-lock active
     *   bit 5 (0x20) = AF-lock confirmed
     *   bit 8 (0x100) = flash ready
     *
     * These match libgphoto2's interpretation; verified against EOS
     * Utility pcap captures on the 6D.
     */
    private fun parseOlcInfo(payload: Buffer, transactionId: Int): PtpEvent.EosOlcInfo? {
        if (!payload.request(4)) return null
        val header = payload.readIntLe()
        val aeLocked = (header and 0x10) != 0
        val afLocked = (header and 0x20) != 0
        val flashReady = (header and 0x100) != 0
        return PtpEvent.EosOlcInfo(
            transactionId = transactionId,
            aeLocked = aeLocked,
            afLocked = afLocked,
            flashReady = flashReady,
            rawHeader = header,
        )
    }

    /**
     * Layout for 0xC181 payload (already past the 8-byte record header):
     *   0x00 uint32 objectHandle
     *   0x04 uint32 storageId
     *   0x08 uint16 ofc
     *   0x0A 10 bytes reserved
     *   0x14 uint32 sizeBytes
     *   0x18 uint32 parentObject
     *   0x1C 4 bytes reserved
     *   0x20 ASCII filename, NUL-terminated
     */
    private fun parseObjectAddedEx(payload: Buffer, transactionId: Int): PtpEvent.EosObjectAddedEx? {
        if (!payload.request(OBJECT_ADDED_EX_FIXED_BYTES.toLong())) return null
        val handle = payload.readIntLe()
        val storageId = payload.readIntLe()
        val format = payload.readShortLe().toInt() and 0xFFFF
        payload.skip(EX_RESERVED_AFTER_OFC)                         // 10 bytes reserved
        val size = (payload.readIntLe().toLong() and 0xFFFF_FFFFL)  // 32-bit unsigned size
        val parent = payload.readIntLe()
        payload.skip(EX_RESERVED_BEFORE_NAME)                       // 4 bytes reserved
        val filename = readAsciiCString(payload)
        return PtpEvent.EosObjectAddedEx(
            transactionId = transactionId,
            handle = handle,
            storageId = storageId,
            format = format,
            filename = filename,
            sizeBytes = size,
            parentObject = parent,
        )
    }

    /**
     * Layout for 0xC1A7 payload (already past the 8-byte record header):
     *   0x00 uint32 objectHandle
     *   0x04 uint32 storageId
     *   0x08 uint16 ofc
     *   0x0A 10 bytes reserved
     *   0x14 uint64 sizeBytes      (LE)
     *   0x1C uint32 parentObject
     *   0x20 uint32 secondaryOid
     *   0x24 ASCII filename, NUL-terminated
     */
    private fun parseObjectAddedEx64(payload: Buffer, transactionId: Int): PtpEvent.EosObjectAddedEx64? {
        if (!payload.request(OBJECT_ADDED_EX64_FIXED_BYTES.toLong())) return null
        val handle = payload.readIntLe()
        val storageId = payload.readIntLe()
        val format = payload.readShortLe().toInt() and 0xFFFF
        payload.skip(EX_RESERVED_AFTER_OFC)
        val size = payload.readLongLe()
        val parent = payload.readIntLe()
        val secondaryOid = payload.readIntLe()
        val filename = readAsciiCString(payload)
        return PtpEvent.EosObjectAddedEx64(
            transactionId = transactionId,
            handle = handle,
            storageId = storageId,
            format = format,
            filename = filename,
            sizeBytes = size,
            parentObject = parent,
            secondaryOid = secondaryOid,
        )
    }

    private fun parseObjectInfoChangedEx(payload: Buffer, transactionId: Int): PtpEvent.EosObjectInfoChangedEx? {
        if (!payload.request(OBJECT_ADDED_EX_FIXED_BYTES.toLong())) return null
        val handle = payload.readIntLe()
        val storageId = payload.readIntLe()
        val format = payload.readShortLe().toInt() and 0xFFFF
        payload.skip(EX_RESERVED_AFTER_OFC)
        val size = (payload.readIntLe().toLong() and 0xFFFF_FFFFL)
        val parent = payload.readIntLe()
        payload.skip(EX_RESERVED_BEFORE_NAME)
        val filename = readAsciiCString(payload)
        return PtpEvent.EosObjectInfoChangedEx(
            transactionId = transactionId,
            handle = handle,
            storageId = storageId,
            format = format,
            filename = filename,
            sizeBytes = size,
            parentObject = parent,
        )
    }

    private fun parseObjectRemoved(payload: Buffer, transactionId: Int): PtpEvent.EosObjectRemoved? {
        if (!payload.request(4)) return null
        val handle = payload.readIntLe()
        return PtpEvent.EosObjectRemoved(transactionId = transactionId, handle = handle)
    }

    /**
     * Read a NUL-terminated ASCII string from [source], stopping at the first
     * 0x00 byte or end-of-buffer (whichever comes first). The terminator is
     * consumed but not included in the returned string.
     */
    private fun readAsciiCString(source: Buffer): String {
        val nulIndex = source.indexOf(0.toByte())
        return if (nulIndex == -1L) {
            // No terminator found — return whatever's left (likely a partial frame).
            source.readUtf8()
        } else {
            val s = source.readUtf8(nulIndex)
            source.skip(1) // consume the NUL
            s
        }
    }

    /** Header in front of every EOS event record. */
    private const val EOS_RECORD_HEADER_SIZE = 8

    /** Fixed payload bytes before the filename in 0xC181 / 0xC187 records. */
    private const val OBJECT_ADDED_EX_FIXED_BYTES = 0x20

    /** Fixed payload bytes before the filename in 0xC1A7 records. */
    private const val OBJECT_ADDED_EX64_FIXED_BYTES = 0x24

    private const val EX_RESERVED_AFTER_OFC: Long = 10
    private const val EX_RESERVED_BEFORE_NAME: Long = 4
}
