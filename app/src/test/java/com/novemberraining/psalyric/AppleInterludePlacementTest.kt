package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleInterludePlacementTest {
    private val width = 535f
    private val font = 29f

    @Test fun leftKeepsExistingDotPlacement() {
        val expected = font * (1.50f + 0.90f + 0.18f)
        assertEquals(
            expected,
            AppleInterludePlacement.centerX(width, font, DuetAlignment.LEFT),
            0.0001f
        )
    }

    @Test fun rightMirrorsExistingDotPlacement() {
        val left = AppleInterludePlacement.centerX(
            width,
            font,
            DuetAlignment.LEFT
        )
        val right = AppleInterludePlacement.centerX(
            width,
            font,
            DuetAlignment.RIGHT
        )
        assertEquals(width, left + right, 0.0001f)
    }

    @Test fun centerPlacesDotsAtCardCenter() {
        assertEquals(
            width / 2f,
            AppleInterludePlacement.centerX(
                width,
                font,
                DuetAlignment.CENTER
            ),
            0.0001f
        )
    }
}
