package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleWordMaskTest {
    @Test fun maskMovesContinuouslyAcrossWordBoundary() {
        val g = AppleWordMask.geometry(floatArrayOf(20f, 40f, 30f))
        val endFirst = AppleWordMask.maskX(g, 0, 1f)
        val startSecond = AppleWordMask.maskX(g, 1, 0f)
        assertEquals(20f, endFirst, 0.0001f)
        assertEquals(endFirst, startSecond, 0.0001f)
    }

    @Test fun transitionWidthUsesAverageWordWidth() {
        val g = AppleWordMask.geometry(floatArrayOf(20f, 40f, 30f))
        assertEquals(30f, g.transitionHalfWidth, 0.0001f)
    }

    @Test fun alphaBlendsBrightToDarkAcrossBand() {
        val bright = 1f
        val dark = 0.4f
        assertEquals(bright, AppleWordMask.alphaAtX(0f, 10f, 20f, bright, dark), 0.0001f)
        assertEquals(0.7f, AppleWordMask.alphaAtX(20f, 10f, 20f, bright, dark), 0.0001f)
        assertEquals(dark, AppleWordMask.alphaAtX(40f, 10f, 20f, bright, dark), 0.0001f)
    }

    @Test fun longerCurrentWordMovesMaskMoreSlowlyPerMillisecond() {
        val g = AppleWordMask.geometry(floatArrayOf(20f, 80f))
        val shortQuarter = AppleWordMask.maskX(g, 0, 0.25f)
        val longQuarter = AppleWordMask.maskX(g, 1, 0.25f)
        assertEquals(5f, shortQuarter, 0.0001f)
        assertEquals(40f, longQuarter, 0.0001f)
        assertTrue(longQuarter > shortQuarter)
    }
}
