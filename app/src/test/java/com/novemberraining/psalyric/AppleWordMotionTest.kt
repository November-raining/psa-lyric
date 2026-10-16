package com.novemberraining.psalyric

import org.junit.Assert.*
import org.junit.Test

class AppleWordMotionTest {
    private val line = LyricLine(
        startMs = 1000,
        durationMs = 1800,
        text = "你好啊",
        words = listOf(
            WordTiming(1000, 500, "你"),
            WordTiming(1500, 600, "好"),
            WordTiming(2100, 700, "啊")
        )
    )

    @Test fun activeScaleUsesMd3AlphaRange() {
        assertEquals(0.4f, AppleWordMotion.dynamicDark(1f), 0.0001f)
        assertEquals(1.0f, AppleWordMotion.dynamicBright(1f), 0.0001f)
        assertEquals(0.2f, AppleWordMotion.dynamicDark(0.85f), 0.0001f)
        assertEquals(0.2f, AppleWordMotion.dynamicBright(0.85f), 0.0001f)
    }

    @Test fun currentWordProgressAndPastWordLiftFollowTimeline() {
        val motion = AppleWordMotion()
        repeat(120) { motion.tick(line, 1800, 1f, true, 1f / 60f) }
        assertEquals(1, motion.currentWordIndex)
        assertEquals(0.5f, motion.intraWordProgress, 0.001f)
        assertTrue(motion.alphaAt(0) > 0.95f)
        assertTrue(motion.liftAt(0) < -2.8f)
        assertTrue(motion.liftAt(1) < -1.0f)
        assertTrue(motion.alphaAt(2) < 0.45f)
    }

    @Test fun inactiveLineReturnsToDarkAndZeroLift() {
        val motion = AppleWordMotion()
        repeat(120) { motion.tick(line, 2300, 1f, true, 1f / 60f) }
        repeat(180) { motion.tick(line, 2300, 0.85f, false, 1f / 60f) }
        for (i in line.words.indices) {
            assertEquals(0.2f, motion.alphaAt(i), 0.01f)
            assertEquals(0f, motion.liftAt(i), 0.02f)
        }
    }
}
