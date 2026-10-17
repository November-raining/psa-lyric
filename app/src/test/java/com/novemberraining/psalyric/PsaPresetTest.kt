package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Test

class PsaPresetTest {
    @Test fun lowerRightCarLifeGeometryMatchesDualCardScreenshot() {
        val g = PsaPreset.geometryForPreset(PsaPreset.PRESET_LOWER_RIGHT)
        assertEquals(1373, g.x)
        assertEquals(249, g.y)
        assertEquals(535, g.width)
        assertEquals(700, g.height)
        assertEquals(949, g.y + g.height)
    }

    @Test fun fullHeightCarLifeGeometryPreservesPreviousBaseline() {
        val g = PsaPreset.geometryForPreset(PsaPreset.PRESET_FULL_HEIGHT)
        assertEquals(1373, g.x)
        assertEquals(10, g.y)
        assertEquals(535, g.width)
        assertEquals(700, g.height)
    }

    @Test fun nativeScaleKeepsBoth1920x720PresetsPixelExact() {
        for (g in listOf(PsaPreset.LOWER_RIGHT, PsaPreset.FULL_HEIGHT)) {
            assertEquals(
                g,
                PsaPreset.scale(g.x, g.y, g.width, g.height, 1920, 720)
            )
        }
    }

    @Test fun nativeCarLifeCornerRadiusMatchesRealCard() {
        assertEquals(30f, PsaPreset.cardCornerRadius(700), 0.0001f)
        assertEquals(15f, PsaPreset.cardCornerRadius(350), 0.0001f)
    }
}
