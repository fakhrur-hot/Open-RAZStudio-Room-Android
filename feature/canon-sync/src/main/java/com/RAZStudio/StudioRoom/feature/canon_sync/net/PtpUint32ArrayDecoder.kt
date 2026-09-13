/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.canon_sync.net

import okio.Buffer

/**
 * Decoder for the standard PTP `uint32 length + uint32[length]` array
 * payload returned by GetStorageIDs (0x1004) and GetObjectHandles (0x1007).
 */
internal object PtpUint32ArrayDecoder {
    fun decode(data: Buffer): IntArray {
        if (data.size < 4) return IntArray(0)
        val n = data.readIntLe()
        if (n < 0 || n > 100_000) return IntArray(0)
        return IntArray(n) { data.readIntLe() }
    }
}
