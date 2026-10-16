package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefsDefaultsTest {
    @Test fun resettableSlidersUseProductBaselineValues() {
        assertEquals(29, Prefs.DEFAULT_FONT_SIZE)
        assertEquals(600, Prefs.DEFAULT_FONT_WEIGHT)
        assertEquals(0.9f, Prefs.DEFAULT_LINE_SPACING, 0.0001f)
        assertEquals(1373, Prefs.DEFAULT_X)
        assertEquals(10, Prefs.DEFAULT_Y)
        assertEquals(535, Prefs.DEFAULT_WIDTH)
        assertEquals(700, Prefs.DEFAULT_HEIGHT)
        assertEquals(260, Prefs.DEFAULT_GLOW_INTENSITY)
        assertEquals(200, Prefs.DEFAULT_GAUSSIAN_BLUR_STRENGTH)
        assertEquals(100, Prefs.DEFAULT_GAUSSIAN_BLUR_DISTANCE_BOOST)
    }
}
