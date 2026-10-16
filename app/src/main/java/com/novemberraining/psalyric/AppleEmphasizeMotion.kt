package com.novemberraining.psalyric

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class AppleEmphasizeState(
    val scale: Float = 1f,
    val glowLevel: Float = 0f,
    val shadowBlurEm: Float = 0f,
    val offsetXEm: Float = 0f,
    val offsetYEm: Float = 0f,
    val floatYEm: Float = 0f
) {
    val isIdle: Boolean
        get() = abs(scale - 1f) < 0.0001f &&
            glowLevel < 0.0001f &&
            abs(offsetXEm) < 0.0001f &&
            abs(offsetYEm) < 0.0001f &&
            abs(floatYEm) < 0.0001f
}

/**
 * AMLL/md3Music long-word emphasis wave.
 *
 * Long syllables are animated per glyph rather than moving one whole word in a
 * few visible steps. Characters start left-to-right, gently scale/float/glow,
 * then settle with a tail that may continue after the next timing unit begins.
 */
object AppleEmphasizeMotion {
    private const val STAGGER_SPREAD_DIVISOR = 2.5
    private const val AMOUNT_SCALE = 0.6
    private const val AMOUNT_CAP = 1.2
    private const val BLUR_SCALE = 0.5
    private const val BLUR_CAP = 0.8
    private const val LAST_WORD_AMOUNT_BOOST = 1.6
    private const val LAST_WORD_BLUR_BOOST = 1.5
    private const val SCALE_FACTOR = 0.1
    private const val SHADOW_BLUR_EM_CAP = 0.3
    private const val OFFSET_X_FACTOR = 0.03
    private const val OFFSET_Y_FACTOR = 0.025
    private const val FLOAT_AMP_EM = 0.05
    private const val FLOAT_LEAD_MS = 400.0
    private const val FLOAT_DURATION_FACTOR = 1.4
    private const val FLOAT_EDGE_FADE = 0.15

    fun state(
        word: WordTiming,
        positionMs: Long,
        charIndex: Int,
        charCount: Int,
        isLastWord: Boolean
    ): AppleEmphasizeState {
        if (word.durationMs <= 0L || charCount <= 0 || charIndex !in 0 until charCount) {
            return AppleEmphasizeState()
        }

        val duration = word.durationMs.toDouble()
        val du = maxOf(1000.0, duration)
        val charDelay = (du / STAGGER_SPREAD_DIVISOR / charCount) * charIndex
        val wordDelay = word.startMs + charDelay
        val bumpPhase = (positionMs - wordDelay) / du
        val floatPhase =
            (positionMs - (wordDelay - FLOAT_LEAD_MS)) / (du * FLOAT_DURATION_FACTOR)

        val bumpActive = bumpPhase in 0.0..1.0
        val floatActive = floatPhase in 0.0..1.0
        if (!bumpActive && !floatActive) return AppleEmphasizeState()

        val trans = when {
            !bumpActive -> 0.0
            bumpPhase < 0.5 ->
                cubicBezier(bumpPhase * 2.0, 0.2, 0.4)
            else ->
                1.0 - cubicBezier((bumpPhase - 0.5) * 2.0, 0.3, 0.0)
        }

        val edgeFade = when {
            !bumpActive -> 0.0
            bumpPhase < FLOAT_EDGE_FADE -> bumpPhase / FLOAT_EDGE_FADE
            bumpPhase > 1.0 - FLOAT_EDGE_FADE ->
                (1.0 - bumpPhase) / FLOAT_EDGE_FADE
            else -> 1.0
        }.coerceIn(0.0, 1.0)

        val floatY = if (floatActive) {
            -sin(PI * floatPhase) * FLOAT_AMP_EM * edgeFade
        } else 0.0

        var amount = sqrt(duration / 2000.0) * AMOUNT_SCALE
        amount = min(amount, AMOUNT_CAP)
        var blur = min(duration / 3000.0 * BLUR_SCALE, BLUR_CAP)
        if (isLastWord) {
            amount *= LAST_WORD_AMOUNT_BOOST
            blur *= LAST_WORD_BLUR_BOOST
        }

        val state = AppleEmphasizeState(
            scale = (1.0 + trans * SCALE_FACTOR * amount).toFloat(),
            glowLevel = (trans * amount).toFloat(),
            shadowBlurEm = min(SHADOW_BLUR_EM_CAP, blur * 0.3).toFloat(),
            offsetXEm = (
                -trans * OFFSET_X_FACTOR * amount * (charCount / 2.0 - charIndex)
            ).toFloat(),
            offsetYEm = (-trans * OFFSET_Y_FACTOR * amount).toFloat(),
            floatYEm = floatY.toFloat()
        )

        return if (state.isIdle) AppleEmphasizeState() else state
    }

    private fun cubicBezier(t: Double, p1: Double, p2: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        val u = 1.0 - x
        return 3.0 * u * u * x * p1 +
            3.0 * u * x * x * p2 +
            x * x * x
    }
}
