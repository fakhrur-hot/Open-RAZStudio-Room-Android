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

import okio.Buffer
import okio.BufferedSource
import okio.ByteString

/**
 * One frame on the PTP/IP wire.
 *
 * Header layout (8 bytes, little-endian):
 *
 *   offset 0  uint32  totalLength    (includes the header itself)
 *   offset 4  uint32  packetType     (one of [PtpIpConstants])
 *   offset 8  variable               (type-specific body)
 *
 * For DATA_PACKET / END_DATA_PACKET on a GetObject data phase we never want
 * to materialise the body into a [ByteString] — that defeats Okio's
 * zero-copy advantage. The reader exposes [readFraming] for that case so the
 * caller can pipe the payload straight to a file sink.
 */
internal sealed interface PtpIpPacket {

    /** Total wire length including the 8-byte header. */
    val length: Int

    /** PTP/IP packet type code from [PtpIpConstants]. */
    val type: Int

    // ---------- Handshake ----------

    /**
     * Response to INIT_COMMAND_REQUEST. Tells us the camera-assigned
     * connection ID, plus the camera's own GUID and friendly name.
     */
    data class InitCommandAck(
        override val length: Int,
        val connectionId: Int,
        val cameraGuid: ByteString,
        val cameraName: String,
        val protocolVersion: Int,
    ) : PtpIpPacket {
        override val type: Int = PtpIpConstants.INIT_COMMAND_ACK
    }

    /** Response to INIT_EVENT_REQUEST. */
    data class InitEventAck(
        override val length: Int,
    ) : PtpIpPacket {
        override val type: Int = PtpIpConstants.INIT_EVENT_ACK
    }

    /**
     * Camera rejected the handshake. Reason codes are vendor-specific but
     * 0x00000001 ("device busy" / "host pool full") is the common one when
     * EOS Utility / Camera Connect already owns the session.
     */
    data class InitFail(
        override val length: Int,
        val reasonCode: Int,
    ) : PtpIpPacket {
        override val type: Int = PtpIpConstants.INIT_FAIL
    }

    // ---------- Operation request/response ----------

    /**
     * Camera-side response to an OperationRequest. Up to 5 32-bit parameters
     * may follow the response code, depending on the operation.
     */
    data class OperationResponse(
        override val length: Int,
        val responseCode: Int,
        val transactionId: Int,
        val parameters: List<Int>,
    ) : PtpIpPacket {
        override val type: Int = PtpIpConstants.OPERATION_RESPONSE
    }

    // ---------- Events ----------

    /**
     * Asynchronous event from the camera. Decoded further into [com.RAZStudio.
     * StudioRoom.feature.canon_sync.domain.models.PtpEvent] by the event
     * reader coroutine.
     */
    data class EventPacket(
        override val length: Int,
        val eventCode: Int,
        val transactionId: Int,
        val parameters: List<Int>,
        /** Trailing bytes that aren't 32-bit parameters — used by EOS_ObjectAddedEx for the filename. */
        val trailing: ByteString,
    ) : PtpIpPacket {
        override val type: Int = PtpIpConstants.EVENT
    }

    // ---------- Data phase ----------

    /**
     * Marks the beginning of a data-in/out phase. Carries the total uncompressed
     * payload size as a uint64 (CR3 frames can exceed 4 GB, so we store Long).
     */
    data class StartDataPacket(
        override val length: Int,
        val transactionId: Int,
        val totalDataLengthBytes: Long,
    ) : PtpIpPacket {
        override val type: Int = PtpIpConstants.START_DATA_PACKET
    }

    // ---------- Keepalive ----------

    object Ping : PtpIpPacket {
        override val length: Int = 8
        override val type: Int = PtpIpConstants.PING
    }

    object Pong : PtpIpPacket {
        override val length: Int = 8
        override val type: Int = PtpIpConstants.PONG
    }

    /** Anything we don't have a specialised case for — keep the body for logging. */
    data class Unknown(
        override val length: Int,
        override val type: Int,
        val body: ByteString,
    ) : PtpIpPacket

    /**
     * Lightweight description of a packet header without consuming its body.
     * Used by [com.RAZStudio.StudioRoom.feature.canon_sync.net.PtpIpPacketReader.readFraming]
     * so the caller can decide whether to materialise the body or stream it
     * straight to a file sink (zero-copy GetObject path).
     */
    data class Framing(val length: Int, val type: Int) {
        /** Number of bytes left in the body after the 8-byte header has been read. */
        val bodySize: Long get() = (length - HEADER_SIZE).toLong()
    }

    companion object {
        /** Header size in bytes (length + type). */
        const val HEADER_SIZE: Int = 8
    }
}

/**
 * Reads one full PTP/IP packet from [source], materialising the body into
 * memory. Use this for everything *except* DATA_PACKET / END_DATA_PACKET on a
 * GetObject data phase — for those, use [PtpIpPacketReader.readFraming] and
 * pipe the body straight to a file sink with [BufferedSource.read].
 *
 * Blocks until at least [PtpIpPacket.HEADER_SIZE] bytes are available, then
 * blocks again until the full body has arrived.
 *
 * @throws okio.EOFException if the socket is closed before a full packet arrives.
 */
internal object PtpIpPacketReader {

    fun readFraming(source: BufferedSource): PtpIpPacket.Framing {
        source.require(PtpIpPacket.HEADER_SIZE.toLong())
        val length = source.readIntLe()
        val type = source.readIntLe()
        android.util.Log.i(
            "CanonSync",
            "readFraming: length=$length type=0x${"%08X".format(type)}",
        )
        return PtpIpPacket.Framing(length, type)
    }

    fun readPacket(source: BufferedSource): PtpIpPacket {
        val framing = readFraming(source)
        return readBody(source, framing)
    }

    /**
     * Decode the body of a packet whose framing has already been read.
     * Useful when the caller wants to branch on type before deciding whether
     * to materialise the body (e.g., stream a DATA_PACKET payload to disk).
     */
    fun readBody(source: BufferedSource, framing: PtpIpPacket.Framing): PtpIpPacket {
        val bodySize = framing.bodySize
        return when (framing.type) {
            PtpIpConstants.INIT_COMMAND_ACK -> parseInitCommandAck(source, framing, bodySize)
            PtpIpConstants.INIT_EVENT_ACK -> {
                if (bodySize > 0) source.skip(bodySize)
                PtpIpPacket.InitEventAck(framing.length)
            }
            PtpIpConstants.INIT_FAIL -> {
                source.require(4)
                val reason = source.readIntLe()
                if (bodySize > 4) source.skip(bodySize - 4)
                PtpIpPacket.InitFail(framing.length, reason)
            }
            PtpIpConstants.OPERATION_RESPONSE -> parseOperationResponse(source, framing, bodySize)
            PtpIpConstants.EVENT -> parseEvent(source, framing, bodySize)
            PtpIpConstants.START_DATA_PACKET -> parseStartDataPacket(source, framing, bodySize)
            PtpIpConstants.PING -> {
                if (bodySize > 0) source.skip(bodySize)
                PtpIpPacket.Ping
            }
            PtpIpConstants.PONG -> {
                if (bodySize > 0) source.skip(bodySize)
                PtpIpPacket.Pong
            }
            else -> {
                val body = if (bodySize > 0) source.readByteString(bodySize) else ByteString.EMPTY
                PtpIpPacket.Unknown(framing.length, framing.type, body)
            }
        }
    }

    private fun parseInitCommandAck(
        source: BufferedSource,
        framing: PtpIpPacket.Framing,
        bodySize: Long,
    ): PtpIpPacket.InitCommandAck {
        // Body layout: connId (4) + camera GUID (16) + UTF-16LE null-terminated name + protocol version (4)
        source.require(20)
        val connectionId = source.readIntLe()
        val guid = source.readByteString(16)
        val remainingAfterGuid = bodySize - 20
        // Camera name is a null-terminated UTF-16LE string. Pull until we hit U+0000.
        val nameBuffer = Buffer()
        var consumed = 0L
        while (consumed < remainingAfterGuid - 4) {
            source.require(2)
            val lo = source.readByte()
            val hi = source.readByte()
            consumed += 2
            if (lo.toInt() == 0 && hi.toInt() == 0) break
            nameBuffer.writeByte(lo.toInt())
            nameBuffer.writeByte(hi.toInt())
        }
        val name = nameBuffer.readByteString().string(Charsets.UTF_16LE)
        // Discard any padding before the protocol version field.
        val tailBeforeVersion = (remainingAfterGuid - 4) - consumed
        if (tailBeforeVersion > 0) source.skip(tailBeforeVersion)
        source.require(4)
        val protocolVersion = source.readIntLe()
        return PtpIpPacket.InitCommandAck(
            length = framing.length,
            connectionId = connectionId,
            cameraGuid = guid,
            cameraName = name,
            protocolVersion = protocolVersion,
        )
    }

    private fun parseOperationResponse(
        source: BufferedSource,
        framing: PtpIpPacket.Framing,
        bodySize: Long,
    ): PtpIpPacket.OperationResponse {
        // Body layout: responseCode (2) + transactionId (4) + params (N * 4) = min 6 bytes.
        // Older code assumed responseCode took 4 bytes (uint16 + 2-byte padding),
        // requiring 8 bytes minimum — but the 6D in EOS Utility/SmartPhone mode
        // sends bodySize=6 (no padding), causing `require(8)` to block forever
        // waiting for two bytes the camera never sends. We treat the padding
        // as optional: read responseCode (2) + txn (4), then only skip 2 more
        // bytes if there's still padding within bodySize.
        source.require(6)
        val responseCode = source.readShortLe().toInt() and 0xFFFF
        val transactionId = source.readIntLe()
        var paramBytes = bodySize - 6
        // Optional 2-byte alignment padding before params on firmware variants
        // that emit it (responseCode as uint32 with high reserved bytes).
        if (paramBytes >= 2 && paramBytes % 4 == 2L) {
            source.skip(2)
            paramBytes -= 2
        }
        val params = ArrayList<Int>(5)
        var remaining = paramBytes
        while (remaining >= 4) {
            source.require(4)
            params += source.readIntLe()
            remaining -= 4
        }
        if (remaining > 0) source.skip(remaining)
        return PtpIpPacket.OperationResponse(framing.length, responseCode, transactionId, params)
    }

    private fun parseEvent(
        source: BufferedSource,
        framing: PtpIpPacket.Framing,
        bodySize: Long,
    ): PtpIpPacket.EventPacket {
        // Body layout: eventCode (2) + reserved (2) + transactionId (4) + up to 3 uint32 params + optional trailing
        source.require(8)
        val eventCode = source.readShortLe().toInt() and 0xFFFF
        source.skip(2)
        val transactionId = source.readIntLe()
        val remaining = bodySize - 8
        // PTP defines up to 3 event parameters of 4 bytes each. Anything past that we
        // capture as trailing (EOS_ObjectAddedEx packs the filename here as UTF-16LE).
        val paramSlots = minOf(3, (remaining / 4).toInt())
        val params = ArrayList<Int>(paramSlots)
        repeat(paramSlots) {
            source.require(4)
            params += source.readIntLe()
        }
        val trailingBytes = remaining - paramSlots * 4
        val trailing = if (trailingBytes > 0) source.readByteString(trailingBytes) else ByteString.EMPTY
        return PtpIpPacket.EventPacket(framing.length, eventCode, transactionId, params, trailing)
    }

    private fun parseStartDataPacket(
        source: BufferedSource,
        framing: PtpIpPacket.Framing,
        bodySize: Long,
    ): PtpIpPacket.StartDataPacket {
        // Body layout: transactionId (4) + totalDataLength (8, uint64 LE)
        source.require(12)
        val transactionId = source.readIntLe()
        val totalLen = source.readLongLe()
        if (bodySize > 12) source.skip(bodySize - 12)
        return PtpIpPacket.StartDataPacket(framing.length, transactionId, totalLen)
    }
}
