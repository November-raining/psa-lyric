package com.novemberraining.psalyric

import org.junit.Assert.*
import org.junit.Test

class StripDissolveTest {
    @Test fun waveTravelsLeftToRightAndEndsAtSixHundredMs() {
        val d = StripDissolve()
        d.begin("abcdef", 1000)
        assertTrue(d.characterAlpha(0, 6, 1100) < d.characterAlpha(5, 6, 1100))
        assertTrue(d.hasVisibleCharacter(6, 1500))
        assertFalse(d.hasVisibleCharacter(6, 1600))
    }

    @Test fun newLineResetRearmsParticleEmissionState() {
        val d = StripDissolve()
        d.begin("abc", 0)
        d.emit(0, 3, 0f, 0f, 10f, 20f, 20f, 0xffffffff.toInt(), 200)
        assertTrue(d.liveParticles(200).isNotEmpty())
        d.begin("def", 300)
        assertTrue(d.liveParticles(300).isEmpty())
    }
}
