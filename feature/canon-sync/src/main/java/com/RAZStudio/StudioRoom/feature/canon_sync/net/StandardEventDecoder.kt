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

/**
 * Decoder for standard PTP/IP `EVENT` packets arriving on the event socket.
 * Canon-vendor shutter events ([PtpIpConstants.EC_EOS_OBJECT_ADDED_EX] et al.)
 * do not arrive here — they're polled via [PtpIpConstants.OC_EOS_GET_EVENT]
 * on the command channel and decoded by [EosEventStreamDecoder].
 *
 * The event socket carries portable PTP events (0x4002 ObjectAdded for older
 * bodies, 0x4006 DeviceInfoChanged, 0x4009 StoreFull, 0x400D CaptureComplete).
 */
internal object StandardEventDecoder {

    fun decode(packet: PtpIpPacket.EventPacket): PtpEvent {
        val txn = packet.transactionId
        return when (packet.eventCode) {
            PtpIpConstants.EC_OBJECT_ADDED -> PtpEvent.ObjectAdded(
                transactionId = txn,
                handle = packet.parameters.firstOrNull() ?: 0,
            )
            PtpIpConstants.EC_DEVICE_INFO_CHANGED -> PtpEvent.DeviceInfoChanged(txn)
            PtpIpConstants.EC_STORE_FULL -> PtpEvent.StoreFull(
                transactionId = txn,
                storageId = packet.parameters.firstOrNull() ?: 0,
            )
            PtpIpConstants.EC_CAPTURE_COMPLETE -> PtpEvent.CaptureComplete(txn)
            else -> PtpEvent.Unknown(
                transactionId = txn,
                eventCode = packet.eventCode,
                parameters = packet.parameters,
            )
        }
    }
}
