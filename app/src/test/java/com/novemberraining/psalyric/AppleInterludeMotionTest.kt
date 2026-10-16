package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleInterludeMotionTest {
    @Test fun detectsFourSecondGapUsingActualLastWordEnd() {
        val lines = listOf(
            LyricLine(
                startMs = 0,
                durationMs = 10_000,
                text = "上一句",
                words = listOf(
                    WordTiming(0, 1_000, "上"),
                    WordTiming(1_000, 1_000, "一句")
                )
            ),
            LyricLine(7_000, 1_000, "下一句")
        )

        val window = AppleInterludeMotion.findWindow(lines, 3_000)

        assertNotNull(window)
        assertEquals(0, window!!.anchorIndex)
        assertEquals(2_000L, window.startMs)
        assertEquals(6_750L, window.endMs)
    }

    @Test fun shortGapDoesNotCreateInterlude() {
        val lines = listOf(
            LyricLine(0, 1_000, "A"),
            LyricLine(4_500, 1_000, "B")
        )
        assertNull(AppleInterludeMotion.findWindow(lines, 2_000))
    }

    @Test fun placeholderExpandsThenCollapsesSmoothly() {
        val lines = listOf(
            LyricLine(0, 1_000, "A"),
            LyricLine(7_000, 1_000, "B")
        )
        val motion = AppleInterludeMotion()

        repeat(20) { motion.update(lines, 2_000 + it * 16L, 0.016f, true) }
        assertTrue(motion.expandProgress > 0.9f)
        assertTrue(motion.isPlaceholderVisible)

        repeat(60) { motion.update(lines, 7_000 + it * 16L, 0.016f, true) }
        assertTrue(motion.expandProgress < 0.01f)
        assertFalse(motion.isPlaceholderVisible)
    }

    @Test fun threeDotsSweepFromLeftToRight() {
        val lines = listOf(
            LyricLine(0, 1_000, "A"),
            LyricLine(10_000, 1_000, "B")
        )
        val motion = AppleInterludeMotion()
        motion.update(lines, 3_000, 0.016f, true)

        val frame = motion.dotFrame(3_000)!!
        assertTrue(frame.dotAlphas[0] > frame.dotAlphas[1])
        assertTrue(frame.dotAlphas[1] >= frame.dotAlphas[2])
    }

    @Test fun dotGroupFadesToZeroBeforeNextLyric() {
        val lines = listOf(
            LyricLine(0, 1_000, "A"),
            LyricLine(10_000, 1_000, "B")
        )
        val motion = AppleInterludeMotion()
        val end = 10_000L - AppleInterludeMotion.EARLY_END_MS
        motion.update(lines, end - 10, 0.016f, true)

        val frame = motion.dotFrame(end - 10)!!
        assertTrue(frame.scale < 0.1f)
        assertTrue(frame.dotAlphas.all { it < 0.1f })
    }

    @Test fun seekAwayClearsPlaceholderImmediately() {
        val lines = listOf(
            LyricLine(0, 1_000, "A"),
            LyricLine(10_000, 1_000, "B")
        )
        val motion = AppleInterludeMotion()
        repeat(15) { motion.update(lines, 2_000 + it * 16L, 0.016f, true) }
        assertTrue(motion.isPlaceholderVisible)

        motion.update(lines, 30_000, 0.016f, true)

        assertFalse(motion.isPlaceholderVisible)
        assertEquals(-1, motion.lastAnchorIndex)
    }
}
