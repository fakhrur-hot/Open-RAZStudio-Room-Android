package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmCurve
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawV3ToneCurveFilmTest {

    @Test
    fun identityWhenDefaultPointsAndNeutralFilm() {
        assertNull(RawV3ToneCurve.buildLut(UserMacro.DEFAULT_CURVE_POINTS, FilmCurve()))
    }

    @Test
    fun filmContrastAloneProducesLut() {
        val lut = RawV3ToneCurve.buildLut(
            UserMacro.DEFAULT_CURVE_POINTS,
            FilmCurve(contrast = 55f, pivot = 0.45f),
        )
        assertNotNull(lut)
        assertEquals(256 * 3, lut!!.size)
        // Midtone near pivot should still be near mid (normalized sigmoid).
        val mid = lut[128 * 3].toInt() and 0xFF
        assertTrue("mid=$mid", mid in 90..160)
        // Black stays near 0, white near 255.
        assertTrue((lut[0].toInt() and 0xFF) < 20)
        assertTrue((lut[255 * 3].toInt() and 0xFF) > 230)
    }

    @Test
    fun highContrastDarkensShadowsAndLiftsHighlightsRelativeToSoft() {
        val soft = RawV3ToneCurve.sampleFilmCurve(FilmCurve.SoftContrast)
        val hard = RawV3ToneCurve.sampleFilmCurve(FilmCurve.HighContrast)
        // Below pivot: high contrast should sit lower (or equal); above: higher.
        assertTrue(hard[40] <= soft[40] + 0.02f)
        assertTrue(hard[210] >= soft[210] - 0.02f)
    }

    @Test
    fun highlightKneeCompressesWhites() {
        val linear = RawV3ToneCurve.sampleFilmCurve(FilmCurve())
        val knee = RawV3ToneCurve.sampleFilmCurve(FilmCurve(highlightKnee = 80f))
        assertEquals(linear[100], knee[100], 1e-4f)
        assertTrue(knee[240] < linear[240])
        assertFalse(RawV3ToneCurve.isIdentity(UserMacro.DEFAULT_CURVE_POINTS, FilmCurve(highlightKnee = 10f)))
    }

    @Test
    fun signatureChangesWithFilm() {
        val a = UserMacro()
        val b = a.copy(filmCurve = FilmCurve.FilmSCurve)
        assertTrue(RawV3ToneCurve.signature(a) != RawV3ToneCurve.signature(b))
    }
}
