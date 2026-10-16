package com.novemberraining.psalyric

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * Frame-driven lyric animation clock, ported from md3Music's AppleLyricsView.
 *
 * MediaSession position updates can be sparse or report playbackSpeed=0 even while
 * STATE_PLAYING. Driving word motion directly from those samples produces visible
 * 3-5 step jumps on long syllables. This clock advances every Choreographer frame
 * and softly corrects to the authoritative media position.
 */
class AppleLyricClock {
    companion object {
        const val SEEK_JUMP_MS = 500L
        const val CORRECTION_RATE = 20f
        const val MAX_DRIFT_MS = 300.0
    }

    private var smoothMs = 0.0
    private var lastAuthorityMs = Long.MIN_VALUE

    fun reset() {
        smoothMs = 0.0
        lastAuthorityMs = Long.MIN_VALUE
    }

    fun update(
        authorityMs: Long,
        playing: Boolean,
        dtSeconds: Float
    ): Long {
        val authority = authorityMs.coerceAtLeast(0L)
        if (lastAuthorityMs == Long.MIN_VALUE) {
            smoothMs = authority.toDouble()
            lastAuthorityMs = authority
            return authority
        }

        if (!playing) {
            smoothMs = authority.toDouble()
            lastAuthorityMs = authority
            return authority
        }

        val dt = dtSeconds.coerceIn(0f, 1f / 15f)
        smoothMs += dt * 1000.0

        if (authority != lastAuthorityMs) {
            val jump = abs(authority - lastAuthorityMs)
            lastAuthorityMs = authority
            if (jump > SEEK_JUMP_MS) {
                smoothMs = authority.toDouble()
            } else {
                val correction = 1.0 - exp((-CORRECTION_RATE * dt).toDouble())
                smoothMs += (authority - smoothMs) * correction
            }
        } else if (abs(smoothMs - lastAuthorityMs) > MAX_DRIFT_MS) {
            // A stalled/buffering player should not let visual lyrics run away.
            smoothMs = lastAuthorityMs.toDouble()
        }

        return smoothMs.coerceAtLeast(0.0).roundToLong()
    }
}
