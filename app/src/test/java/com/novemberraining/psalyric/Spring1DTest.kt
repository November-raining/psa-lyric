package com.novemberraining.psalyric

import org.junit.Assert.*
import org.junit.Test

class Spring1DTest {
    @Test fun denseLyricsUseStifferScroll() {
        assertTrue(AppleMotion.scrollStiffness(100) > AppleMotion.scrollStiffness(800))
    }

    @Test fun springConverges() {
        val spring = Spring1D(position = 0f, target = 100f, stiffness = 120f, damping = 24f)
        repeat(600) { spring.step(1f / 60f) }
        assertEquals(100f, spring.position, 0.1f)
    }
}
