package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricClockTest {
    @Test fun sparseAuthorityStillAdvancesEveryFrame() {
        val clock = AppleLyricClock()
        assertEquals(1000L, clock.update(1000L, true, 1f / 60f))
        val p1 = clock.update(1000L, true, 1f / 60f)
        val p2 = clock.update(1000L, true, 1f / 60f)
        assertTrue(p1 > 1000L)
        assertTrue(p2 > p1)
    }

    @Test fun normalAuthorityCorrectionDoesNotHardJump() {
        val clock = AppleLyricClock()
        clock.update(1000L, true, 1f / 60f)
        repeat(6) { clock.update(1000L, true, 1f / 60f) }
        val before = clock.update(1000L, true, 1f / 60f)
        val corrected = clock.update(1200L, true, 1f / 60f)
        assertTrue(corrected > before)
        assertTrue(corrected < 1200L)
    }

    @Test fun seekJumpSnapsImmediately() {
        val clock = AppleLyricClock()
        clock.update(1000L, true, 1f / 60f)
        assertEquals(5000L, clock.update(5000L, true, 1f / 60f))
    }

    @Test fun pauseSnapsToAuthority() {
        val clock = AppleLyricClock()
        clock.update(1000L, true, 1f / 60f)
        clock.update(1000L, true, 1f / 60f)
        assertEquals(1010L, clock.update(1010L, false, 1f / 60f))
    }
}
