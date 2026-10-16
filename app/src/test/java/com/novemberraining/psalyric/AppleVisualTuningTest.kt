package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleVisualTuningTest {
    @Test fun defaultsPreserveExistingGlow220() {
        val progress = 0.5f
        assertEquals(
            29f * 0.24f * progress,
            AppleVisualTuning.glowRadius(
                fontPx = 29f,
                progress = progress,
                intensity = AppleVisualTuning.BASE_GLOW_INTENSITY
            ),
            0.0001f
        )
        assertEquals(
            (220f * progress).toInt(),
            AppleVisualTuning.glowAlpha(
                progress = progress,
                intensity = AppleVisualTuning.BASE_GLOW_INTENSITY
            )
        )
    }

    @Test fun defaultsPreserveExistingDistanceBlur() {
        for (distance in 1..5) {
            assertEquals(
                distance * 0.85f,
                AppleVisualTuning.blurRadius(
                    distanceFromCurrent = distance,
                    strengthPercent = 100,
                    distanceBoostPercent = 100,
                    heightScale = 1f,
                    fade = 1f
                ),
                0.0001f
            )
        }
    }

    @Test fun strongerSettingsIncreaseBlurAndGlow() {
        val baseGlow = AppleVisualTuning.glowRadius(29f, 1f, 220)
        val strongerGlow = AppleVisualTuning.glowRadius(29f, 1f, 400)
        assertTrue(strongerGlow > baseGlow)

        val baseBlur = AppleVisualTuning.blurRadius(4, 100, 100, 1f, 1f)
        val strongerBlur = AppleVisualTuning.blurRadius(4, 200, 180, 1f, 1f)
        assertTrue(strongerBlur > baseBlur)
    }
}
