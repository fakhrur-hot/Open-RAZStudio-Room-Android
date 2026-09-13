/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Unit tests for MLExtendedHistogram module (pure Kotlin, no Android deps).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import org.junit.Assert.*
import org.junit.Test

class MLExtendedHistogramTest {

    // ── Helper: create a 768-element interleaved histogram ──

    /**
     * Creates a synthetic 768-element histogram with all pixels in a single bin
     * per channel. Useful for edge case testing.
     */
    private fun uniformHistogram(bin: Int, count: Int = 1000): IntArray {
        val histogram = IntArray(768)
        histogram[bin * 3 + 0] = count  // R
        histogram[bin * 3 + 1] = count  // G
        histogram[bin * 3 + 2] = count  // B
        return histogram
    }

    /**
     * Creates a histogram with a spread distribution for a channel.
     * Fills bins from [low..high] with equal counts.
     */
    private fun spreadHistogram(low: Int, high: Int, countPerBin: Int = 100): IntArray {
        val histogram = IntArray(768)
        for (bin in low..high) {
            histogram[bin * 3 + 0] = countPerBin  // R
            histogram[bin * 3 + 1] = countPerBin  // G
            histogram[bin * 3 + 2] = countPerBin  // B
        }
        return histogram
    }

    // ── computeStats: Scene Dynamic Range ──

    @Test
    fun computeStats_wideSpread_returnsHighDR() {
        // Spread from bin 2 to bin 250 — large dynamic range
        val histogram = spreadHistogram(2, 250)
        val stats = MLExtendedHistogram.computeStats(histogram, 0, 16383)

        // DR should be high (log2(250/2) ≈ 6.97, but with percentiles it varies)
        assertTrue("Scene DR should be >= 4", stats.sceneDynamicRange >= 4f)
        assertTrue("Scene DR should be <= 14", stats.sceneDynamicRange <= 14f)
    }

    @Test
    fun computeStats_narrowSpread_returnsLowDR() {
        // Spread from bin 100 to bin 110 — very narrow range
        val histogram = spreadHistogram(100, 110)
        val stats = MLExtendedHistogram.computeStats(histogram, 0, 16383)

        // DR = log2(110/100) ≈ 0.14, but clamped to minimum 4
        assertEquals(4f, stats.sceneDynamicRange, 0.01f)
    }

    @Test
    fun computeStats_drClampsAt14() {
        // Very wide spread: bin 1 to bin 255 → log2(255/1) ≈ 8, so won't hit 14
        // Use bins 1 and 254 with heavy weights at extremes to push DR
        val histogram = IntArray(768)
        // Put 1% of pixels at bin 1, rest spread far apart
        histogram[1 * 3 + 1] = 10    // G at bin 1 (this will be the 1st percentile)
        histogram[254 * 3 + 1] = 990  // G at bin 254 (this will be the 99th percentile)
        // Fill R and B similarly
        histogram[1 * 3 + 0] = 10
        histogram[254 * 3 + 0] = 990
        histogram[1 * 3 + 2] = 10
        histogram[254 * 3 + 2] = 990

        val stats = MLExtendedHistogram.computeStats(histogram, 0, 16383)

        // log2(254/1) ≈ 7.99 — still within 14
        assertTrue("Scene DR should be <= 14", stats.sceneDynamicRange <= 14f)
        assertTrue("Scene DR should be >= 4", stats.sceneDynamicRange >= 4f)
    }

    // ── computeStats: Highlight Headroom ──

    @Test
    fun computeStats_lowP90_returnsHighHeadroom() {
        // All pixels at a low bin → p90 is low → headroom is high
        val histogram = uniformHistogram(32, 1000)
        val stats = MLExtendedHistogram.computeStats(histogram, 0, 16383)

        // headroom = log2(255/32) ≈ 3.0, clamped to [0..3]
        assertTrue("Headroom should be at max 3", stats.highlightHeadroom <= 3f)
        assertTrue("Headroom should be positive", stats.highlightHeadroom > 0f)
    }

    @Test
    fun computeStats_highP90_returnsLowHeadroom() {
        // All pixels at bin 250 → p90 = 250 → headroom = log2(255/250) ≈ 0.03
        val histogram = uniformHistogram(250, 1000)
        val stats = MLExtendedHistogram.computeStats(histogram, 0, 16383)

        assertTrue("Headroom should be very low", stats.highlightHeadroom < 0.5f)
    }

    @Test
    fun computeStats_headroomClampsAt0() {
        // All pixels at bin 255 → p90 = 255 → headroom = log2(255/255) = 0
        val histogram = uniformHistogram(255, 1000)
        val stats = MLExtendedHistogram.computeStats(histogram, 0, 16383)

        assertEquals(0f, stats.highlightHeadroom, 0.01f)
    }

    // ── computeStats: Per-Channel Clip Percentages ──

    @Test
    fun computeStats_noClipping_returnsZeroClip() {
        // All pixels at mid-range — no clipping
        val histogram = uniformHistogram(128, 1000)
        val stats = MLExtendedHistogram.computeStats(histogram, 0, 16383)

        assertEquals(0f, stats.redClipPercent, 0.001f)
        assertEquals(0f, stats.greenClipPercent, 0.001f)
        assertEquals(0f, stats.blueClipPercent, 0.001f)
    }

    @Test
    fun computeStats_allClipped_returns100PercentClip() {
        // All pixels in top 3 bins (253, 254, 255)
        val histogram = IntArray(768)
        histogram[253 * 3 + 0] = 333; histogram[254 * 3 + 0] = 333; histogram[255 * 3 + 0] = 334  // R
        histogram[253 * 3 + 1] = 333; histogram[254 * 3 + 1] = 333; histogram[255 * 3 + 1] = 334  // G
        histogram[253 * 3 + 2] = 333; histogram[254 * 3 + 2] = 333; histogram[255 * 3 + 2] = 334  // B

        val stats = MLExtendedHistogram.computeStats(histogram, 0, 16383)

        assertEquals(1f, stats.redClipPercent, 0.01f)
        assertEquals(1f, stats.greenClipPercent, 0.01f)
        assertEquals(1f, stats.blueClipPercent, 0.01f)
    }

    @Test
    fun computeStats_partialClipping_returnsCorrectPercent() {
        // 100 pixels total per channel, 5 in top bins → 5%
        val histogram = IntArray(768)
        // Put most pixels at bin 128
        histogram[128 * 3 + 0] = 95
        histogram[128 * 3 + 1] = 95
        histogram[128 * 3 + 2] = 95
        // Put 5 pixels in top 3 bins (clipped)
        histogram[253 * 3 + 0] = 2; histogram[254 * 3 + 0] = 2; histogram[255 * 3 + 0] = 1
        histogram[253 * 3 + 1] = 2; histogram[254 * 3 + 1] = 2; histogram[255 * 3 + 1] = 1
        histogram[253 * 3 + 2] = 2; histogram[254 * 3 + 2] = 2; histogram[255 * 3 + 2] = 1

        val stats = MLExtendedHistogram.computeStats(histogram, 0, 16383)

        assertEquals(0.05f, stats.redClipPercent, 0.01f)
        assertEquals(0.05f, stats.greenClipPercent, 0.01f)
        assertEquals(0.05f, stats.blueClipPercent, 0.01f)
    }

    // ── computeStats: Bounds guarantees ──

    @Test
    fun computeStats_allBoundsWithinSpec() {
        val histogram = spreadHistogram(10, 200)
        val stats = MLExtendedHistogram.computeStats(histogram, 0, 16383)

        assertTrue("DR in [4..14]", stats.sceneDynamicRange in 4f..14f)
        assertTrue("Headroom in [0..3]", stats.highlightHeadroom in 0f..3f)
        assertTrue("Red clip in [0..1]", stats.redClipPercent in 0f..1f)
        assertTrue("Green clip in [0..1]", stats.greenClipPercent in 0f..1f)
        assertTrue("Blue clip in [0..1]", stats.blueClipPercent in 0f..1f)
    }

    // ── driveFromStats: Film Rolloff ──

    @Test
    fun driveFromStats_drBelow10_noRolloff() {
        val stats = StageAHistogramStats(
            sceneDynamicRange = 9f,
            highlightHeadroom = 2f,
            redClipPercent = 0f,
            greenClipPercent = 0f,
            blueClipPercent = 0f,
        )
        val result = MLExtendedHistogram.driveFromStats(stats)
        assertEquals(0f, result.filmRolloff, 0.001f)
    }

    @Test
    fun driveFromStats_drExactly10_noRolloff() {
        val stats = StageAHistogramStats(
            sceneDynamicRange = 10f,
            highlightHeadroom = 2f,
            redClipPercent = 0f,
            greenClipPercent = 0f,
            blueClipPercent = 0f,
        )
        val result = MLExtendedHistogram.driveFromStats(stats)
        assertEquals(0f, result.filmRolloff, 0.001f)
    }

    @Test
    fun driveFromStats_dr12_rolloffHalf() {
        val stats = StageAHistogramStats(
            sceneDynamicRange = 12f,
            highlightHeadroom = 2f,
            redClipPercent = 0f,
            greenClipPercent = 0f,
            blueClipPercent = 0f,
        )
        val result = MLExtendedHistogram.driveFromStats(stats)
        // (12 - 10) / 4 = 0.5
        assertEquals(0.5f, result.filmRolloff, 0.001f)
    }

    @Test
    fun driveFromStats_dr14_rolloffClampsAt1() {
        val stats = StageAHistogramStats(
            sceneDynamicRange = 14f,
            highlightHeadroom = 2f,
            redClipPercent = 0f,
            greenClipPercent = 0f,
            blueClipPercent = 0f,
        )
        val result = MLExtendedHistogram.driveFromStats(stats)
        // (14 - 10) / 4 = 1.0
        assertEquals(1f, result.filmRolloff, 0.001f)
    }

    // ── driveFromStats: Gamut Compress ──

    @Test
    fun driveFromStats_noClipping_noGamutCompress() {
        val stats = StageAHistogramStats(
            sceneDynamicRange = 8f,
            highlightHeadroom = 2f,
            redClipPercent = 0.01f,
            greenClipPercent = 0.01f,
            blueClipPercent = 0.01f,
        )
        val result = MLExtendedHistogram.driveFromStats(stats)
        assertEquals(0f, result.gamutCompress, 0.001f)
    }

    @Test
    fun driveFromStats_redClipAbove2Percent_gamutCompressActive() {
        val stats = StageAHistogramStats(
            sceneDynamicRange = 8f,
            highlightHeadroom = 2f,
            redClipPercent = 0.05f,
            greenClipPercent = 0.01f,
            blueClipPercent = 0.01f,
        )
        val result = MLExtendedHistogram.driveFromStats(stats)
        // max clip = 0.05, which is > 0.02 → gamutCompress = 0.05
        assertEquals(0.05f, result.gamutCompress, 0.001f)
    }

    @Test
    fun driveFromStats_clipExactly2Percent_noGamutCompress() {
        val stats = StageAHistogramStats(
            sceneDynamicRange = 8f,
            highlightHeadroom = 2f,
            redClipPercent = 0.02f,
            greenClipPercent = 0.02f,
            blueClipPercent = 0.02f,
        )
        val result = MLExtendedHistogram.driveFromStats(stats)
        // Exactly at threshold — not above, so no compress
        assertEquals(0f, result.gamutCompress, 0.001f)
    }

    // ── driveFromStats: Highlight Recovery ──

    @Test
    fun driveFromStats_headroomAbove05_noRecovery() {
        val stats = StageAHistogramStats(
            sceneDynamicRange = 8f,
            highlightHeadroom = 1f,
            redClipPercent = 0f,
            greenClipPercent = 0f,
            blueClipPercent = 0f,
        )
        val result = MLExtendedHistogram.driveFromStats(stats)
        assertEquals(0f, result.highlightRecovery, 0.001f)
    }

    @Test
    fun driveFromStats_headroomExactly05_noRecovery() {
        val stats = StageAHistogramStats(
            sceneDynamicRange = 8f,
            highlightHeadroom = 0.5f,
            redClipPercent = 0f,
            greenClipPercent = 0f,
            blueClipPercent = 0f,
        )
        val result = MLExtendedHistogram.driveFromStats(stats)
        assertEquals(0f, result.highlightRecovery, 0.001f)
    }

    @Test
    fun driveFromStats_headroom025_recoveryHalf() {
        val stats = StageAHistogramStats(
            sceneDynamicRange = 8f,
            highlightHeadroom = 0.25f,
            redClipPercent = 0f,
            greenClipPercent = 0f,
            blueClipPercent = 0f,
        )
        val result = MLExtendedHistogram.driveFromStats(stats)
        // 1 - 0.25 * 2 = 0.5
        assertEquals(0.5f, result.highlightRecovery, 0.001f)
    }

    @Test
    fun driveFromStats_headroom0_recoveryFull() {
        val stats = StageAHistogramStats(
            sceneDynamicRange = 8f,
            highlightHeadroom = 0f,
            redClipPercent = 0f,
            greenClipPercent = 0f,
            blueClipPercent = 0f,
        )
        val result = MLExtendedHistogram.driveFromStats(stats)
        // 1 - 0 * 2 = 1.0
        assertEquals(1f, result.highlightRecovery, 0.001f)
    }

    // ── driveFromStats: All values in [0..1] ──

    @Test
    fun driveFromStats_allResultsWithinBounds() {
        val stats = StageAHistogramStats(
            sceneDynamicRange = 14f,
            highlightHeadroom = 0f,
            redClipPercent = 1f,
            greenClipPercent = 1f,
            blueClipPercent = 1f,
        )
        val result = MLExtendedHistogram.driveFromStats(stats)

        assertTrue("filmRolloff in [0..1]", result.filmRolloff in 0f..1f)
        assertTrue("gamutCompress in [0..1]", result.gamutCompress in 0f..1f)
        assertTrue("highlightRecovery in [0..1]", result.highlightRecovery in 0f..1f)
    }

    // ── Property 14: Histogram Stats Bounds (task 5.2) ──

    @Test
    fun property14_histogramStatsBounds() {
        val rnd = kotlin.random.Random(42)
        repeat(60) {
            val histogram = IntArray(768) { rnd.nextInt(0, 5000) }
            val blackLevel = rnd.nextInt(0, 2048)
            val whiteLevel = rnd.nextInt(8000, 16384)
            val stats = MLExtendedHistogram.computeStats(histogram, blackLevel, whiteLevel)

            assertTrue("sceneDR in [4..14]", stats.sceneDynamicRange in 4f..14f)
            assertTrue("highlightHeadroom in [0..3]", stats.highlightHeadroom in 0f..3f)
            assertTrue("redClipPercent in [0..1]", stats.redClipPercent in 0f..1f)
            assertTrue("greenClipPercent in [0..1]", stats.greenClipPercent in 0f..1f)
            assertTrue("blueClipPercent in [0..1]", stats.blueClipPercent in 0f..1f)
        }

        // Degenerate all-zero histogram (no pixels at all) must not throw and
        // must still stay in range.
        val emptyStats = MLExtendedHistogram.computeStats(IntArray(768), 0, 16383)
        assertTrue(emptyStats.sceneDynamicRange in 4f..14f)
        assertTrue(emptyStats.highlightHeadroom in 0f..3f)
    }
}
