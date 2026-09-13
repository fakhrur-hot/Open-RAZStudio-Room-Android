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

package com.RAZStudio.StudioRoom.feature.canon_sync.domain

import com.RAZStudio.StudioRoom.feature.canon_sync.net.PtpIpConstants
import kotlin.math.abs

/**
 * Mappers from raw Canon EOS DPC wire values to human-readable chip
 * labels. All tables are derived from Canon EDSDK headers and verified
 * against the libgphoto2 `camlibs/ptp2/ptp.c` corpus.
 *
 * The functions return a best-effort label — when a value lies outside
 * the documented table we render the raw hex so the user can still tell
 * settings apart and report bugs.
 */
object EosPropertyLabels {

    /**
     * Top-level dispatcher: pick the right mapper for [dpc] and call it.
     * Used by the generic picker sheet, which doesn't need to know which
     * DPC it's rendering — just the descriptor + value.
     */
    fun label(dpc: Int, value: Long): String = when (dpc) {
        PtpIpConstants.DPC_EOS_ISO -> iso(value)
        PtpIpConstants.DPC_EOS_TV -> shutterSpeed(value)
        PtpIpConstants.DPC_EOS_AV -> aperture(value)
        PtpIpConstants.DPC_EOS_EXPOSURE_COMP -> exposureComp(value)
        PtpIpConstants.DPC_EOS_WHITE_BALANCE -> whiteBalance(value)
        PtpIpConstants.DPC_EOS_METERING_MODE -> metering(value)
        PtpIpConstants.DPC_EOS_DRIVE_MODE -> driveMode(value)
        PtpIpConstants.DPC_EOS_AF_MODE -> afMode(value)
        PtpIpConstants.DPC_EOS_AF_METHOD -> afMethod(value)
        PtpIpConstants.DPC_EOS_PICTURE_STYLE -> pictureStyle(value)
        PtpIpConstants.DPC_EOS_IMAGE_QUALITY -> imageQuality(value)
        PtpIpConstants.DPC_EOS_ASPECT_RATIO -> aspectRatio(value)
        PtpIpConstants.DPC_EOS_CAMERA_MODE -> cameraMode(value)
        PtpIpConstants.DPC_EOS_EVF_ZOOM -> evfZoom(value)
        else -> "0x${value.toString(16).uppercase()}"
    }

    /** Short top-line chip text — same as [label] for now, kept for future contraction. */
    fun chipText(dpc: Int, value: Long): String = label(dpc, value)

    /**
     * ISO sensitivity. Canon EDSDK `kEdsPropID_ISOSpeed` enum.
     * 0x00 = Auto, 0x28..0x9C step in 1/3 stops from 50 to 51200,
     * plus L/H expansions.
     */
    fun iso(v: Long): String = when (v) {
        0x00L -> "Auto"
        0x40L -> "50"
        0x48L -> "100"
        0x4BL -> "125"
        0x4DL -> "160"
        0x50L -> "200"
        0x53L -> "250"
        0x55L -> "320"
        0x58L -> "400"
        0x5BL -> "500"
        0x5DL -> "640"
        0x60L -> "800"
        0x63L -> "1000"
        0x65L -> "1250"
        0x68L -> "1600"
        0x6BL -> "2000"
        0x6DL -> "2500"
        0x70L -> "3200"
        0x73L -> "4000"
        0x75L -> "5000"
        0x78L -> "6400"
        0x7BL -> "8000"
        0x7DL -> "10000"
        0x80L -> "12800"
        0x83L -> "16000"
        0x85L -> "20000"
        0x88L -> "25600"
        0x90L -> "51200"
        0x98L -> "102400"
        else -> "0x${v.toString(16).uppercase()}"
    }

    /**
     * Tv / shutter speed. Canon EDSDK `kEdsPropID_Tv` table — 1/8000 …
     * 30 s + Bulb. Values are non-linear stepping in 1/3 stops.
     */
    fun shutterSpeed(v: Long): String = when (v) {
        0x0CL -> "Bulb"
        0x10L -> "30\""
        0x13L -> "25\""
        0x15L -> "20\""
        0x18L -> "15\""
        0x1BL -> "13\""
        0x1DL -> "10\""
        0x20L -> "8\""
        0x23L -> "6\""
        0x25L -> "5\""
        0x28L -> "4\""
        0x2BL -> "3\"2"
        0x2DL -> "2\"5"
        0x30L -> "2\""
        0x33L -> "1\"6"
        0x35L -> "1\"3"
        0x38L -> "1\""
        0x3BL -> "0\"8"
        0x3DL -> "0\"6"
        0x40L -> "0\"5"
        0x43L -> "0\"4"
        0x45L -> "0\"3"
        0x48L -> "1/4"
        0x4BL -> "1/5"
        0x4DL -> "1/6"
        0x50L -> "1/8"
        0x53L -> "1/10"
        0x55L -> "1/13"
        0x58L -> "1/15"
        0x5BL -> "1/20"
        0x5DL -> "1/25"
        0x60L -> "1/30"
        0x63L -> "1/40"
        0x65L -> "1/50"
        0x68L -> "1/60"
        0x6BL -> "1/80"
        0x6DL -> "1/100"
        0x70L -> "1/125"
        0x73L -> "1/160"
        0x75L -> "1/200"
        0x78L -> "1/250"
        0x7BL -> "1/320"
        0x7DL -> "1/400"
        0x80L -> "1/500"
        0x83L -> "1/640"
        0x85L -> "1/800"
        0x88L -> "1/1000"
        0x8BL -> "1/1250"
        0x8DL -> "1/1600"
        0x90L -> "1/2000"
        0x93L -> "1/2500"
        0x95L -> "1/3200"
        0x98L -> "1/4000"
        0x9BL -> "1/5000"
        0x9DL -> "1/6400"
        0xA0L -> "1/8000"
        else -> "0x${v.toString(16).uppercase()}"
    }

    /**
     * Aperture (f-number). Canon EDSDK `kEdsPropID_Av` table —
     * 1/3-stop quantised.
     */
    fun aperture(v: Long): String = when (v) {
        0x08L -> "f/1.0"
        0x0BL -> "f/1.1"
        0x0CL -> "f/1.2"
        0x0DL -> "f/1.2L"
        0x10L -> "f/1.4"
        0x13L -> "f/1.6"
        0x14L -> "f/1.8"
        0x15L -> "f/1.8L"
        0x18L -> "f/2.0"
        0x1BL -> "f/2.2"
        0x1CL -> "f/2.5"
        0x1DL -> "f/2.5L"
        0x20L -> "f/2.8"
        0x23L -> "f/3.2"
        0x25L -> "f/3.5"
        0x28L -> "f/4.0"
        0x2BL -> "f/4.5"
        0x2CL -> "f/4.5L"
        0x2DL -> "f/5.0"
        0x30L -> "f/5.6"
        0x33L -> "f/6.3"
        0x35L -> "f/7.1"
        0x38L -> "f/8.0"
        0x3BL -> "f/9.0"
        0x3DL -> "f/10"
        0x40L -> "f/11"
        0x43L -> "f/13"
        0x45L -> "f/14"
        0x48L -> "f/16"
        0x4BL -> "f/18"
        0x4DL -> "f/20"
        0x50L -> "f/22"
        0x53L -> "f/25"
        0x55L -> "f/29"
        0x58L -> "f/32"
        0x5BL -> "f/36"
        0x5DL -> "f/40"
        0x60L -> "f/45"
        0x63L -> "f/51"
        0x65L -> "f/57"
        0x68L -> "f/64"
        0x6BL -> "f/72"
        0x6DL -> "f/81"
        0x70L -> "f/91"
        else -> "0x${v.toString(16).uppercase()}"
    }

    /**
     * Exposure compensation. uint8 with sign-bit encoding: high nibble
     * 0x00..0x28 = 0..+5 EV, 0xE8..0xFF = -3..0 EV (and the inverse
     * for -5..-3). 1/3 EV step = 3 wire units.
     */
    fun exposureComp(v: Long): String {
        // Canon encodes 1/3 EV as wire-value 3.
        val signed = v.toByte().toInt()  // sign-extend
        val whole = signed / 8
        val third = (abs(signed) % 8) / 3
        val sign = when {
            signed > 0 -> "+"
            signed < 0 -> "-"
            else -> ""
        }
        val absWhole = abs(whole)
        val fraction = when (third) {
            1 -> " 1/3"
            2 -> " 2/3"
            else -> ""
        }
        return "$sign$absWhole$fraction EV"
    }

    /** Canon EDSDK `kEdsPropID_WhiteBalance`. */
    fun whiteBalance(v: Long): String = when (v) {
        0x00L -> "Auto"
        0x01L -> "Daylight"
        0x02L -> "Cloudy"
        0x03L -> "Tungsten"
        0x04L -> "Fluorescent"
        0x05L -> "Flash"
        0x06L -> "Manual"
        0x07L -> "Shade"
        0x08L -> "Kelvin"
        0x09L -> "PC-1"
        0x0AL -> "PC-2"
        0x0BL -> "PC-3"
        0x0FL -> "PC-4"
        0x10L -> "PC-5"
        0x13L -> "AutoWh"   // Ambience-priority Auto (newer bodies)
        0x14L -> "AutoCl"   // White-priority Auto
        else -> "0x${v.toString(16).uppercase()}"
    }

    fun metering(v: Long): String = when (v) {
        0x01L -> "Spot"
        0x03L -> "Evaluative"
        0x04L -> "Partial"
        0x05L -> "Center"
        else -> "0x${v.toString(16).uppercase()}"
    }

    fun driveMode(v: Long): String = when (v) {
        0x00L -> "Single"
        0x01L -> "Continuous"
        0x02L -> "Hi-Speed"
        0x04L -> "Self 10s"
        0x05L -> "Continuous-Hi"
        0x06L -> "Continuous-Lo"
        0x07L -> "Silent"
        0x09L -> "Self 2s"
        0x10L -> "Self 10s + Cont"
        0x11L -> "Silent Single"
        0x12L -> "Silent Cont"
        else -> "0x${v.toString(16).uppercase()}"
    }

    fun afMode(v: Long): String = when (v) {
        0x00L -> "One-Shot"
        0x01L -> "AI-Servo"
        0x02L -> "AI-Focus"
        0x03L -> "Manual"
        else -> "0x${v.toString(16).uppercase()}"
    }

    fun afMethod(v: Long): String = when (v) {
        0x00L -> "FlexiZone"
        0x01L -> "Face+Tracking"
        0x02L -> "Quick"
        0x03L -> "Live 1-pt"
        0x04L -> "Live Zone"
        else -> "0x${v.toString(16).uppercase()}"
    }

    fun pictureStyle(v: Long): String = when (v) {
        0x81L -> "Standard"
        0x82L -> "Portrait"
        0x83L -> "Landscape"
        0x84L -> "Neutral"
        0x85L -> "Faithful"
        0x86L -> "Monochrome"
        0x87L -> "Auto"
        0x88L -> "Fine Detail"
        0x21L -> "User 1"
        0x22L -> "User 2"
        0x23L -> "User 3"
        else -> "0x${v.toString(16).uppercase()}"
    }

    /**
     * ImageQuality is a packed 32-bit composite (compression / size /
     * RAW flag / dual format). We surface the most user-meaningful axis
     * — the format combo — and let the full picker show the raw hex
     * for the rest until a dedicated quality picker lands.
     */
    fun imageQuality(v: Long): String {
        val raw = (v ushr 16) and 0xFFFFL
        val jpeg = v and 0xFFFFL
        val hasRaw = raw != 0L
        val hasJpeg = jpeg != 0L
        return when {
            hasRaw && hasJpeg -> "RAW + JPG"
            hasRaw -> "RAW"
            hasJpeg -> "JPG"
            else -> "0x${v.toString(16).uppercase()}"
        }
    }

    fun aspectRatio(v: Long): String = when (v) {
        0x00L -> "3:2"
        0x01L -> "1:1"
        0x02L -> "4:3"
        0x07L -> "16:9"
        else -> "0x${v.toString(16).uppercase()}"
    }

    fun cameraMode(v: Long): String = when (v) {
        0x00L -> "P"
        0x01L -> "Tv"
        0x02L -> "Av"
        0x03L -> "M"
        0x04L -> "Bulb"
        0x05L -> "A-DEP"
        0x06L -> "DEP"
        0x07L -> "Custom"
        0x08L -> "Lock"
        0x09L -> "Auto"
        0x0AL -> "Night"
        0x0BL -> "Sports"
        0x0CL -> "Portrait"
        0x0DL -> "Landscape"
        0x0EL -> "Close-up"
        0x0FL -> "Flash-off"
        0x13L -> "C1"
        0x14L -> "C2"
        0x15L -> "C3"
        0x16L -> "CA"
        0x19L -> "B"
        else -> "0x${v.toString(16).uppercase()}"
    }

    fun evfZoom(v: Long): String = when (v) {
        1L -> "1×"
        5L -> "5×"
        10L -> "10×"
        else -> "${v}×"
    }
}
