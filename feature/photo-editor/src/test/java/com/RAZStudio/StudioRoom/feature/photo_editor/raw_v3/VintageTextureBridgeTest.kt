package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import org.junit.Assert.assertEquals
import org.junit.Test

class VintageTextureBridgeTest {
    @Test
    fun vintageTextureParamsAreMappedIntoShaderBlob() {
        val macro = UserMacro(
            fxVintageStrength = 80f,
            fxVintageFade = 30f,
            fxVintageVig = 15f,
            fxVintageMistIntensity = 42f,
            fxVintageMistScale = 18f,
            fxVintageTextureIntensity = 64f,
            fxVintageTextureScale = 25f,
        )

        val params = RawV3ActionReplay.mapMacroToShaderParams(macro)
        val blob = params.toFloatArray()

        assertEquals(0.8f, params.fxVintageStrength, 0.0001f)
        assertEquals(0.3f, params.fxVintageFade, 0.0001f)
        assertEquals(0.15f, params.fxVintageVig, 0.0001f)
        assertEquals(0.42f, params.fxVintageMistIntensity, 0.0001f)
        assertEquals(18f, params.fxVintageMistScale, 0.0001f)
        assertEquals(0.64f, params.fxVintageTextureIntensity, 0.0001f)
        assertEquals(25f, params.fxVintageTextureScale, 0.0001f)

        assertEquals(0.42f, blob[454], 0.0001f)
        assertEquals(18f, blob[455], 0.0001f)
        assertEquals(0.64f, blob[456], 0.0001f)
        assertEquals(25f, blob[457], 0.0001f)
    }

    @Test
    fun vintageMistAndTextureParamsSurviveMacroMerge() {
        val base = UserMacro()
        val delta = UserMacro(
            fxVintageMistIntensity = 42f,
            fxVintageMistScale = 18f,
            fxVintageTextureIntensity = 64f,
            fxVintageTextureScale = 25f,
        )

        val merged = base.mergeWith(delta)

        assertEquals(42f, merged.fxVintageMistIntensity, 0.0001f)
        assertEquals(18f, merged.fxVintageMistScale, 0.0001f)
        assertEquals(64f, merged.fxVintageTextureIntensity, 0.0001f)
        assertEquals(25f, merged.fxVintageTextureScale, 0.0001f)
    }
}
