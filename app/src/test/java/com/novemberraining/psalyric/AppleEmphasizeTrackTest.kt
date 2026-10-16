package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleEmphasizeTrackTest {
    private fun line(): LyricLine = LyricLine(
        startMs = 1000L,
        durationMs = 2600L,
        text = "遥引山河",
        words = listOf(
            WordTiming(1000L, 1400L, "遥引"),
            WordTiming(2400L, 600L, "山"),
            WordTiming(3000L, 600L, "河")
        )
    )

    @Test fun frameClockAdvancesWaveSmoothly() {
        val track = AppleEmphasizeTrack()
        val flags = booleanArrayOf(true, false, false)
        var position = 1300L
        track.tick(line(), flags, position, true, 1f / 60f)
        val before = track.statesFor(0)[0]

        repeat(5) {
            position += 17L
            track.tick(line(), flags, position, true, 1f / 60f)
        }
        val after = track.statesFor(0)[0]

        assertTrue(after.scale != before.scale || after.glowLevel != before.glowLevel)
    }

    @Test fun previousWordTailSurvivesNextWordStart() {
        val track = AppleEmphasizeTrack()
        val flags = booleanArrayOf(true, false, false)

        track.tick(line(), flags, 2200L, true, 1f / 60f)
        track.tick(line(), flags, 2410L, true, 1f / 60f)

        assertTrue(track.statesFor(0).any { !it.isIdle })
    }

    @Test fun pausedWaveFreezes() {
        val track = AppleEmphasizeTrack()
        val flags = booleanArrayOf(true, false, false)

        track.tick(line(), flags, 1600L, true, 1f / 60f)
        val before = track.statesFor(0).toList()
        repeat(5) {
            track.tick(line(), flags, 1600L, false, 1f / 60f)
        }
        val after = track.statesFor(0).toList()

        assertEquals(before, after)
    }
}
