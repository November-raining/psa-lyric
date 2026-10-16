package com.novemberraining.psalyric

import kotlin.math.sqrt

/**
 * Small pure helpers for visual tuning controls.
 *
 * Defaults are chosen so 100% blur settings and glow=220 reproduce the
 * pre-control 0.1.15 rendering exactly.
 */
object AppleVisualTuning {
    const val BASE_GLOW_INTENSITY = 220
    const val BASE_BLUR_RADIUS = 0.85f

    fun glowScale(intensity: Int): Float {
        val ratio = (intensity.coerceAtLeast(1) / BASE_GLOW_INTENSITY.toFloat())
            .coerceAtLeast(0.01f)
        return sqrt(ratio)
    }

    fun glowRadius(
        fontPx: Float,
        progress: Float,
        intensity: Int
    ): Float =
        fontPx * 0.24f *
            progress.coerceIn(0f, 1f) *
            glowScale(intensity)

    fun glowAlpha(
        progress: Float,
        intensity: Int
    ): Int =
        (intensity.coerceAtLeast(0) * progress.coerceIn(0f, 1f))
            .toInt()
            .coerceIn(0, 255)

    fun blurRadius(
        distanceFromCurrent: Int,
        strengthPercent: Int,
        distanceBoostPercent: Int,
        heightScale: Float,
        fade: Float
    ): Float {
        val d = distanceFromCurrent.coerceIn(1, 5)
        val strength = strengthPercent.coerceAtLeast(0) / 100f
        val boost = distanceBoostPercent.coerceAtLeast(0) / 100f
        val distanceFactor = 1f + (d - 1) * boost
        return BASE_BLUR_RADIUS *
            distanceFactor *
            strength *
            heightScale.coerceAtLeast(0f) *
            fade.coerceIn(0f, 1f)
    }
}
