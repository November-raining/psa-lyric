package com.novemberraining.psalyric

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class InterludeWindow(
    val anchorIndex: Int,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class InterludeDotFrame(
    val scale: Float,
    val dotAlphas: FloatArray
)

class AppleInterludeMotion {
    companion object {
        const val THRESHOLD_MS = 4_000L
        const val EARLY_END_MS = 250L
        const val ENTER_SCALE_MS = 400f
        const val EXIT_SCALE_MS = 750f
        const val OPACITY_FADE_IN_MS = 200f
        const val OPACITY_FADE_OUT_MS = 400f
        const val TARGET_BREATHE_MS = 1_500f
        const val EXPAND_SPEED = 18f
        const val COLLAPSE_SPEED = 9f

        fun effectiveLineEnd(line: LyricLine): Long {
            if (line.words.isEmpty()) return line.endMs
            return min(line.endMs, line.words.last().endMs)
        }

        fun findWindow(lines: List<LyricLine>, positionMs: Long): InterludeWindow? {
            if (lines.size < 2) return null
            var lo = 0
            var hi = lines.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (lines[mid].startMs <= positionMs) lo = mid + 1 else hi = mid
            }
            val current = (lo - 1).coerceAtLeast(0)
            val startIndex = max(0, current - 1)
            val endIndex = min(current, lines.lastIndex - 1)
            for (i in startIndex..endIndex) {
                val start = effectiveLineEnd(lines[i])
                val nextStart = lines[i + 1].startMs
                if (nextStart - start < THRESHOLD_MS) continue
                val end = nextStart - EARLY_END_MS
                if (end <= start) continue
                if (positionMs >= start && positionMs < end) {
                    return InterludeWindow(i, start, end)
                }
            }
            return null
        }

        private fun easeOutBack(x: Float): Float {
            if (x <= 0f) return 0f
            if (x >= 1f) return 1f
            val c1 = 1.70158f
            val c3 = c1 + 1f
            val t = x - 1f
            return 1f + c3 * t * t * t + c1 * t * t
        }

        private fun easeInBack(x: Float): Float {
            if (x <= 0f) return 0f
            if (x >= 1f) return 1f
            val c1 = 1.70158f
            val c3 = c1 + 1f
            return c3 * x * x * x - c1 * x * x
        }

        private fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)
    }

    var activeWindow: InterludeWindow? = null
        private set

    var lastAnchorIndex: Int = -1
        private set

    var expandProgress: Float = 0f
        private set

    private var lastPositionMs: Long = Long.MIN_VALUE

    val isActive: Boolean get() = activeWindow != null

    val isPlaceholderVisible: Boolean
        get() = lastAnchorIndex >= 0 && expandProgress > 0.001f

    fun reset() {
        activeWindow = null
        lastAnchorIndex = -1
        expandProgress = 0f
        lastPositionMs = Long.MIN_VALUE
    }

    fun update(
        lines: List<LyricLine>,
        positionMs: Long,
        dtSeconds: Float,
        playing: Boolean
    ) {
        val window = findWindow(lines, positionMs)
        val previousWindow = activeWindow
        val jumped = lastPositionMs != Long.MIN_VALUE &&
            abs(positionMs - lastPositionMs) > 1_250L

        activeWindow = window
        if (window != null) {
            lastAnchorIndex = window.anchorIndex
        } else if (jumped && previousWindow != null) {
            // Seeking away from an interlude must not leave a floating placeholder.
            expandProgress = 0f
            lastAnchorIndex = -1
        }

        val target = if (window != null) 1f else 0f
        if (!playing) {
            expandProgress = target
        } else {
            val speed = if (window != null) EXPAND_SPEED else COLLAPSE_SPEED
            val dt = dtSeconds.coerceIn(0f, 1f / 30f)
            val decay = (1.0 - exp((-speed * dt).toDouble())).toFloat()
            expandProgress += (target - expandProgress) * decay
        }

        if (window == null && expandProgress < 0.001f) {
            expandProgress = 0f
            lastAnchorIndex = -1
        } else if (window != null && expandProgress > 0.999f) {
            expandProgress = 1f
        }

        lastPositionMs = positionMs
    }

    fun offsetBefore(lineIndex: Int, placeholderHeight: Float): Float {
        if (!isPlaceholderVisible) return 0f
        if (lastAnchorIndex < 0 || lastAnchorIndex >= lineIndex) return 0f
        return placeholderHeight * expandProgress
    }

    fun dotFrame(positionMs: Long): InterludeDotFrame? {
        val window = activeWindow ?: return null
        val duration = window.durationMs.toFloat()
        if (duration <= 0f) return null
        val current = (positionMs - window.startMs).toFloat().coerceIn(0f, duration)

        val periods = max(1, ceil(duration / TARGET_BREATHE_MS).toInt())
        val breatheDuration = max(1f, (duration / periods).toInt().toFloat())
        var scale = 1f + (
            sin(1.5 * PI - (current / breatheDuration) * 2.0) / 20.0
        ).toFloat()

        if (current < ENTER_SCALE_MS) {
            scale *= easeOutBack(current / ENTER_SCALE_MS)
        }

        var globalOpacity = if (current < OPACITY_FADE_IN_MS) {
            current / OPACITY_FADE_IN_MS
        } else {
            1f
        }

        val remaining = duration - current
        if (remaining < EXIT_SCALE_MS) {
            val t = (EXIT_SCALE_MS - remaining) / EXIT_SCALE_MS
            scale *= 1f - easeInBack(t)
        }
        if (remaining < OPACITY_FADE_OUT_MS) {
            globalOpacity *= clamp01(remaining / OPACITY_FADE_OUT_MS)
        }

        scale = max(0f, scale)
        globalOpacity = clamp01(globalOpacity)
        if (scale <= 0f || globalOpacity <= 0f) {
            return InterludeDotFrame(0f, floatArrayOf(0f, 0f, 0f))
        }

        val dotsDuration = max(0f, duration - EXIT_SCALE_MS)
        val alphas = FloatArray(3)
        for (i in 0..2) {
            val dotAlpha = if (dotsDuration <= 0f) {
                1f
            } else {
                val offset = i * dotsDuration / 3f
                val t = (current - offset) * 3f / dotsDuration
                (t * 0.75f + 0.25f).coerceIn(0.25f, 1f)
            }
            alphas[i] = clamp01(globalOpacity * dotAlpha)
        }
        return InterludeDotFrame(scale, alphas)
    }
}
