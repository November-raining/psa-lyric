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

    @Test fun analyticSpringIsStableAcrossFrameCadence() {
        val sixty = Spring1D(position = 0f, target = 100f, mass = 2f, stiffness = 100f, damping = 25f)
        val thirty = Spring1D(position = 0f, target = 100f, mass = 2f, stiffness = 100f, damping = 25f)

        repeat(60) { sixty.step(1f / 60f) }
        repeat(30) { thirty.step(1f / 30f) }

        assertEquals(sixty.position, thirty.position, 0.05f)
        assertEquals(sixty.velocity, thirty.velocity, 0.1f)
    }

    @Test fun retargetPreservesVelocityContinuity() {
        val spring = Spring1D(position = 0f, target = 100f, mass = 2f, stiffness = 100f, damping = 25f)
        repeat(8) { spring.step(1f / 60f) }
        val before = spring.velocity
        spring.retarget(60f)
        assertEquals(before, spring.velocity, 0.0001f)
        spring.step(1f / 60f)
        assertTrue(spring.position.isFinite())
    }
}
