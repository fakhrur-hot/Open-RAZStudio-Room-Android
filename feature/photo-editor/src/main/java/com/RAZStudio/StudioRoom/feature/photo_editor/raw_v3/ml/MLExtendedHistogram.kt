/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML Intelligence — Extended Histogram Statistics.
 *
 * Computes scene dynamic range, highlight headroom, and per-channel clip
 * percentages from the 768-element interleaved R,G,B Stage A histogram.
 * These statistics drive automatic highlight recovery, film rolloff, and
 * gamut compression adjustments.
 *
 * Histogram layout: 256 bins × 3 channels interleaved as [R0,G0,B0, R1,G1,B1, ...].
 * Each bin index i for channel c is at histogram[i * 3 + c] where c ∈ {0=R, 1=G, 2=B}.
 *
 * Computed statistics:
 *   • Scene DR: log2(p99_green / p1_green), clamped [4..14] stops
 *   • Highlight headroom: log2(255 / p90_green), clamped [0..3] stops
 *   • Per-channel clip: sum of top 3 bins (253–255) / total pixels per channel, [0..1]
 *
 * Drive decisions:
 *   • Film rolloff when DR > 10 stops (compress highlights)
 *   • Gamut compress when any channel clips > 2%
 *   • Highlight recovery when headroom < 0.5 stops
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import kotlin.math.ln
import kotlin.math.max

/**
 * Extended histogram statistics and drive logic for Stage A processing.
 *
 * Analyzes the 768-element interleaved histogram to determine scene dynamic
 * range, available highlight headroom, and per-channel clipping. Uses these
 * stats to drive film rolloff, gamut compression, and highlight recovery.
 *
 * Slots affected: [404] mlSceneDR, [405] mlHighlightRoom, [348] highlightRecovery.
 * UserMacro fields affected: filmRolloff, gamutCompress.
 */
object MLExtendedHistogram {

    /** ln(2) constant for log2 conversion. */
    private const val LN2 = 0.6931471805599453f

    /**
     * Compute histogram statistics from the Stage A interleaved histogram.
     *
     * Analyzes each channel (R, G, B) to find percentile bins and clip counts.
     * Scene DR and highlight headroom are derived from the green channel
     * (most representative of luminance).
     *
     * @param histogram 768-element interleaved R,G,B histogram (256 bins × 3 channels).
     *   Layout: histogram[bin * 3 + channel] where channel ∈ {0=R, 1=G, 2=B}.
     * @param blackLevel Sensor black level (unused in current percentile-based algorithm,
     *   reserved for future offset-aware analysis).
     * @param whiteLevel Sensor white level used for headroom reference.
     * @return [StageAHistogramStats] with DR, headroom, and per-channel clip percentages.
     */
    fun computeStats(
        histogram: IntArray,
        blackLevel: Int,
        whiteLevel: Int,
    ): StageAHistogramStats {
        // Total pixels per channel (histogram is interleaved, so sum every 3rd element)
        val totalPixelsR = channelTotal(histogram, 0)
        val totalPixelsG = channelTotal(histogram, 1)
        val totalPixelsB = channelTotal(histogram, 2)

        // Per-channel analysis: find percentile bins and clip counts
        val (rP1, rP90, rP99, rClip) = channelStats(histogram, 0, totalPixelsR)
        val (gP1, gP90, gP99, gClip) = channelStats(histogram, 1, totalPixelsG)
        val (bP1, bP90, bP99, bClip) = channelStats(histogram, 2, totalPixelsB)

        // Scene DR from green channel (most representative of luminance)
        // DR = log2(p99 / p1) — ratio of highlight to shadow percentile bins
        val shadowBin = max(gP1, 1)  // avoid division by zero
        val highlightBin = max(gP99, shadowBin + 1)
        val sceneDR = log2(highlightBin.toFloat() / shadowBin.toFloat())
            .coerceIn(4f, 14f)

        // Highlight headroom: stops between 90th percentile and white level reference
        // Using bin 255 as the reference point (histogram ceiling)
        val headroom = log2(255f / max(gP90, 1).toFloat())
            .coerceIn(0f, 3f)

        return StageAHistogramStats(
            sceneDynamicRange = sceneDR,
            highlightHeadroom = headroom,
            redClipPercent = rClip,
            greenClipPercent = gClip,
            blueClipPercent = bClip,
        )
    }

    /**
     * Derive adjustment drives from histogram statistics.
     *
     * Determines how much film rolloff, gamut compression, and highlight
     * recovery should be applied based on the computed stats.
     *
     * @param stats Histogram statistics from [computeStats].
     * @return [HistogramDriveResult] with drive values in [0..1].
     */
    fun driveFromStats(stats: StageAHistogramStats): HistogramDriveResult {
        // Film rolloff: compress highlights when scene DR exceeds 10 stops
        val filmRolloff = if (stats.sceneDynamicRange > 10f) {
            ((stats.sceneDynamicRange - 10f) / 4f).coerceIn(0f, 1f)
        } else {
            0f
        }

        // Gamut compress: activate when any channel clips above 2%
        val maxClip = maxOf(stats.redClipPercent, stats.greenClipPercent, stats.blueClipPercent)
        val gamutCompress = if (maxClip > 0.02f) {
            maxClip.coerceIn(0f, 1f)
        } else {
            0f
        }

        // Highlight recovery: activate when headroom is tight (< 0.5 stops)
        val highlightRecovery = if (stats.highlightHeadroom < 0.5f) {
            (1f - stats.highlightHeadroom * 2f).coerceIn(0f, 1f)
        } else {
            0f
        }

        return HistogramDriveResult(
            filmRolloff = filmRolloff,
            gamutCompress = gamutCompress,
            highlightRecovery = highlightRecovery,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compute total pixel count for a single channel from the interleaved histogram.
     */
    private fun channelTotal(histogram: IntArray, channelOffset: Int): Long {
        var total = 0L
        for (bin in 0 until 256) {
            total += histogram[bin * 3 + channelOffset]
        }
        return total
    }

    /**
     * Compute percentile bins and clip percentage for a single channel.
     *
     * @return [ChannelResult] with p1, p90, p99 bin indices and clip fraction.
     */
    private fun channelStats(
        histogram: IntArray,
        channelOffset: Int,
        totalPixels: Long,
    ): ChannelResult {
        if (totalPixels <= 0L) {
            return ChannelResult(p1 = 0, p90 = 0, p99 = 0, clipPercent = 0f)
        }

        var cumulative = 0L
        var p1 = 0
        var p90 = 0
        var p99 = 0
        var p1Found = false
        var p90Found = false
        var p99Found = false
        var clipCount = 0L

        val threshold1 = (totalPixels * 0.01).toLong()
        val threshold90 = (totalPixels * 0.90).toLong()
        val threshold99 = (totalPixels * 0.99).toLong()

        for (bin in 0 until 256) {
            val count = histogram[bin * 3 + channelOffset].toLong()
            cumulative += count

            if (!p1Found && cumulative >= threshold1) {
                p1 = bin
                p1Found = true
            }
            if (!p90Found && cumulative >= threshold90) {
                p90 = bin
                p90Found = true
            }
            if (!p99Found && cumulative >= threshold99) {
                p99 = bin
                p99Found = true
            }

            // Top 3 bins (253, 254, 255) = clipped pixels
            if (bin >= 253) {
                clipCount += count
            }
        }

        val clipPercent = (clipCount.toFloat() / totalPixels.toFloat()).coerceIn(0f, 1f)

        return ChannelResult(p1, p90, p99, clipPercent)
    }

    /** log2(x) using natural log conversion. */
    private fun log2(x: Float): Float {
        if (x <= 0f) return 0f
        return ln(x) / LN2
    }

    /**
     * Internal result holder for per-channel percentile analysis.
     */
    private data class ChannelResult(
        val p1: Int,
        val p90: Int,
        val p99: Int,
        val clipPercent: Float,
    )
}
