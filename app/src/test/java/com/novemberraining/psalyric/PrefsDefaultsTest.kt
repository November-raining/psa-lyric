package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefsDefaultsTest {
    @Test fun sevenResettableSlidersUseProductBaselineValues() {
        assertEquals(29, Prefs.DEFAULT_FONT_SIZE)
        assertEquals(600, Prefs.DEFAULT_FONT_WEIGHT)
        assertEquals(0.9f, Prefs.DEFAULT_LINE_SPACING, 0.0001f)
        assertEquals(1373, Prefs.DEFAULT_X)
        assertEquals(10, Prefs.DEFAULT_Y)
        assertEquals(535, Prefs.DEFAULT_WIDTH)
        assertEquals(700, Prefs.DEFAULT_HEIGHT)
    }
}
