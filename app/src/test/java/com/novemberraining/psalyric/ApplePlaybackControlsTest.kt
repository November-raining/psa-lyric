package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePlaybackControlsTest {
    private val width = 535f
    private val height = 700f

    @Test fun progressTapMapsToPlaybackPosition() {
        val left = ApplePlaybackControls.progressLeft(height)
        val right = ApplePlaybackControls.progressRight(width, height)
        val middle = (left + right) / 2f

        assertEquals(
            120_000L,
            ApplePlaybackControls.positionForX(
                middle,
                width,
                height,
                240_000L
            ),
            1_000L
        )
        assertEquals(
            0L,
            ApplePlaybackControls.positionForX(
                left - 200f,
                width,
                height,
                240_000L
            )
        )
        assertEquals(
            240_000L,
            ApplePlaybackControls.positionForX(
                right + 200f,
                width,
                height,
                240_000L
            )
        )
    }

    @Test fun progressAndMediaButtonHitAreasDoNotOverlap() {
        val progressY = ApplePlaybackControls.progressY(height)
        val buttonY = ApplePlaybackControls.buttonY(height)
        val centerX = width * 0.5f

        assertTrue(
            ApplePlaybackControls.isProgressHit(
                centerX,
                progressY,
                width,
                height
            )
        )
        assertEquals(
            -1,
            ApplePlaybackControls.buttonIndexAt(
                centerX,
                progressY,
                width,
                height
            )
        )
        assertFalse(
            ApplePlaybackControls.isProgressHit(
                centerX,
                buttonY,
                width,
                height
            )
        )
        assertEquals(
            1,
            ApplePlaybackControls.buttonIndexAt(
                centerX,
                buttonY,
                width,
                height
            )
        )
    }

    @Test fun unusedBottomAreaNoLongerMapsToAnyMediaButton() {
        val y = ApplePlaybackControls.buttonY(height)
        assertEquals(-1, ApplePlaybackControls.buttonIndexAt(15f, y, width, height))
        assertEquals(-1, ApplePlaybackControls.buttonIndexAt(width - 15f, y, width, height))
        assertEquals(-1, ApplePlaybackControls.buttonIndexAt(width * 0.4f, y - 35f, width, height))
    }

    @Test fun timeLabelsUseMusicPlayerStyleFormatting() {
        assertEquals("00:09", ApplePlaybackControls.formatTime(9_900L))
        assertEquals("04:04", ApplePlaybackControls.formatTime(244_999L))
        assertEquals("1:02:03", ApplePlaybackControls.formatTime(3_723_000L))
    }
}
