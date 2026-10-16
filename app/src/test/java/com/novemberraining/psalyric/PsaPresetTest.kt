package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Test

class PsaPresetTest {
    @Test fun nativeCarLifeGeometryIsFullHeightRightCard() {
        val g = PsaPreset.scale(1373, 10, 535, 700, 1920, 720)
        assertEquals(1373, g.x)
        assertEquals(10, g.y)
        assertEquals(535, g.width)
        assertEquals(700, g.height)
    }
}
