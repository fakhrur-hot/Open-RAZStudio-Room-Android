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
 * Decoded camera-pushed events from the PTP/IP event channel.
 *
 * Events traverse a Channel.UNLIMITED on the way to the CaptureCoordinator;
 * see claude advanced plan.md §3.2. A dropped ObjectAdded event = a
 * permanently-lost photo, so this type is intentionally lossless.
 */
sealed interface PtpEvent {

    /**
     * Transaction ID associated with the carrying packet. For standard PTP/IP
     * events this is the txn from the EVENT packet header; for EOS records
     * polled via 0x9116 it's the txn from the GetEvent operation that
     * surfaced the record.
     */
    val transactionId: Int

    /**
     * Canon-preferred ObjectAdded variant (event code 0xC181). Arrives inside
     * an [com.RAZStudio.StudioRoom.feature.canon_sync.net.PtpIpConstants.OC_EOS_GET_EVENT]
     * data blob, not as a PTP/IP EVENT packet. Carries filename + format +
     * size inline, saving a GetObjectInfo round-trip.
     */
    data class EosObjectAddedEx(
        override val transactionId: Int,
        val handle: Int,
        val storageId: Int,
        val format: Int,
        val filename: String,
        val sizeBytes: Long,
        val parentObject: Int,
    ) : PtpEvent

    /**
     * 64-bit-size variant (event code 0xC1A7) used by some current R-series
     * bodies for CR3 frames larger than 4 GB. Same semantics as
     * [EosObjectAddedEx] but the size came off the wire as uint64.
     */
    data class EosObjectAddedEx64(
        override val transactionId: Int,
        val handle: Int,
        val storageId: Int,
        val format: Int,
        val filename: String,
        val sizeBytes: Long,
        val parentObject: Int,
        val secondaryOid: Int,
    ) : PtpEvent

    /** EOS record: object metadata changed (same fields as [EosObjectAddedEx]). */
    data class EosObjectInfoChangedEx(
        override val transactionId: Int,
        val handle: Int,
        val storageId: Int,
        val format: Int,
        val filename: String,
        val sizeBytes: Long,
        val parentObject: Int,
    ) : PtpEvent

    /** EOS record: object removed from the card. Handle only. */
    data class EosObjectRemoved(
        override val transactionId: Int,
        val handle: Int,
    ) : PtpEvent

    /**
     * Standard PTP/IP EVENT packet for ObjectAdded (event code 0x4002). Used
     * as a fallback on older bodies that don't emit [EosObjectAddedEx].
     * Carries only the handle — the consumer must follow up with
     * GetObjectInfo (0x1008) to learn filename / format / size.
     */
    data class ObjectAdded(
        override val transactionId: Int,
        val handle: Int,
    ) : PtpEvent

    data class DeviceInfoChanged(override val transactionId: Int) : PtpEvent

    data class StoreFull(
        override val transactionId: Int,
        val storageId: Int,
    ) : PtpEvent

    data class CaptureComplete(override val transactionId: Int) : PtpEvent

    /**
     * EOS_DevicePropChanged (0xC189) or EOS_PropertyValueChanged (0xC18B).
     * Carries the affected DPC code. Both event codes flatten into this
     * single type because the controller's reaction is identical:
     * invalidate the cached descriptor / value for that DPC.
     */
    data class EosPropertyChanged(
        override val transactionId: Int,
        val dpcCode: Int,
    ) : PtpEvent

    /**
     * `EOS_OLCInfoChanged (0xC18A)`. Live On-Lens-Camera status pushed
     * by the body during half-press, in live view, and on AE / WB
     * changes. Layout is a bitmask header followed by sparse fields —
     * we extract the few we surface in the status row.
     */
    data class EosOlcInfo(
        override val transactionId: Int,
        val aeLocked: Boolean,
        val afLocked: Boolean,
        val flashReady: Boolean,
        val rawHeader: Int,
    ) : PtpEvent

    /** Any event code we don't have a specialised case for. Useful for logging. */
    data class Unknown(
        override val transactionId: Int,
        val eventCode: Int,
        val parameters: List<Int>,
    ) : PtpEvent
}
