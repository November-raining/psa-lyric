package com.novemberraining.psalyric

import kotlin.math.max

/**
 * AMLL/md3Music-compatible line-level word mask.
 *
 * The highlight is not a per-word on/off switch. A mask boundary moves across the
 * cumulative rendered width of the lyric line; a fixed transition band (one
 * average word width on each side) blends bright -> dark alpha continuously.
 */
object AppleWordMask {
    data class Geometry(
        val wordStarts: FloatArray,
        val wordWidths: FloatArray,
        val transitionHalfWidth: Float
    )

    fun geometry(wordWidths: FloatArray): Geometry {
        val starts = FloatArray(wordWidths.size)
        var x = 0f
        var sum = 0f
        for (i in wordWidths.indices) {
            val w = max(0f, wordWidths[i])
            starts[i] = x
            x += w
            sum += w
        }
        val half = if (wordWidths.isEmpty()) 0f else sum / wordWidths.size
        return Geometry(starts, wordWidths.copyOf(), half)
    }

    fun maskX(
        geometry: Geometry,
        currentWordIndex: Int,
        intraWordProgress: Float
    ): Float {
        if (currentWordIndex < 0 || geometry.wordWidths.isEmpty()) return -1f
        if (currentWordIndex >= geometry.wordWidths.size) return Float.POSITIVE_INFINITY
        return geometry.wordStarts[currentWordIndex] +
            geometry.wordWidths[currentWordIndex] * intraWordProgress.coerceIn(0f, 1f)
    }

    fun alphaAtX(
        x: Float,
        transitionStart: Float,
        transitionSpan: Float,
        bright: Float,
        dark: Float
    ): Float {
        if (transitionSpan <= 0f) return if (x <= transitionStart) bright else dark
        if (x <= transitionStart) return bright
        val t = (x - transitionStart) / transitionSpan
        if (t >= 1f) return dark
        return bright + (dark - bright) * t
    }
}
