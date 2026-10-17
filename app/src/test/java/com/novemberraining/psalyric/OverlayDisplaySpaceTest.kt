package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayDisplaySpaceTest {
    @Test fun carLifeUsesPhysicalDisplayInsteadOfNarrowAppDisplay() {
        val chosen = OverlayDisplaySpace.choose(
            appWidth = 1808,
            appHeight = 720,
            realWidth = 1920,
            realHeight = 720,
            carLifeForeground = true
        )
        assertEquals(1920, chosen.width)
        assertEquals(720, chosen.height)

        val g = PsaPreset.scale(
            PsaPreset.LOWER_RIGHT.x,
            PsaPreset.LOWER_RIGHT.y,
            PsaPreset.LOWER_RIGHT.width,
            PsaPreset.LOWER_RIGHT.height,
            chosen.width,
            chosen.height
        )
        assertEquals(PsaPreset.LOWER_RIGHT, g)
    }

    @Test fun nonCarLifeKeepsExistingAppDisplayMapping() {
        val chosen = OverlayDisplaySpace.choose(
            appWidth = 1808,
            appHeight = 720,
            realWidth = 1920,
            realHeight = 720,
            carLifeForeground = false
        )
        assertEquals(1808, chosen.width)
        assertEquals(720, chosen.height)
    }

    @Test fun invalidRealDisplayFallsBackToAppDisplay() {
        val chosen = OverlayDisplaySpace.choose(
            appWidth = 1808,
            appHeight = 720,
            realWidth = 0,
            realHeight = 0,
            carLifeForeground = true
        )
        assertEquals(1808, chosen.width)
        assertEquals(720, chosen.height)
    }
}
