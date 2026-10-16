package com.novemberraining.psalyric

import org.junit.Assert.assertTrue
import org.junit.Test

class AppleEmphasizeMotionTest {
    @Test fun longSingleGlyphHasContinuousMidpointLiftAndScale() {
        val word = WordTiming(1000L, 1800L, "衢")
        val state = AppleEmphasizeMotion.state(word, 1900L, 0, 1, false)
        assertTrue(state.scale > 1f)
        assertTrue(state.glowLevel > 0f)
        assertTrue(state.offsetYEm < 0f)
    }

    @Test fun multiGlyphPhraseStartsAsAStaggeredWave() {
        val word = WordTiming(1000L, 2400L, "遥引")
        val first = AppleEmphasizeMotion.state(word, 1500L, 0, 2, false)
        val second = AppleEmphasizeMotion.state(word, 1500L, 1, 2, false)
        assertTrue(first.scale > second.scale)
    }

    @Test fun effectSettlesAfterTailFinishes() {
        val word = WordTiming(1000L, 1600L, "尘烬")
        val state = AppleEmphasizeMotion.state(word, 5000L, 1, 2, false)
        assertTrue(state.isIdle)
    }
}
