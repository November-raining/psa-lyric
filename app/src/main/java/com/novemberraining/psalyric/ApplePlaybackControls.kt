package com.novemberraining.psalyric

import kotlin.math.abs

/**
 * Pure geometry/time helpers for the bottom playback controls.
 *
 * The hit boxes intentionally do not partition the entire lower card. A media
 * button reacts only inside a compact square around its icon, leaving the
 * progress bar and unused control area independent.
 */
object ApplePlaybackControls {
    const val DESIGN_HEIGHT = 700f
    const val PROGRESS_Y_FROM_BOTTOM = 96f
    const val BUTTON_Y_FROM_BOTTOM = 54f
    const val SIDE_INSET = 22f
    const val PROGRESS_HIT_HALF_HEIGHT = 16f
    const val BUTTON_HIT_HALF_SIZE = 24f

    fun scale(height: Float): Float =
        if (height <= 0f) 1f else height / DESIGN_HEIGHT

    fun progressY(height: Float): Float =
        height - PROGRESS_Y_FROM_BOTTOM * scale(height)

    fun buttonY(height: Float): Float =
        height - BUTTON_Y_FROM_BOTTOM * scale(height)

    fun progressLeft(height: Float): Float = SIDE_INSET * scale(height)

    fun progressRight(width: Float, height: Float): Float =
        width - SIDE_INSET * scale(height)

    fun isProgressHit(
        x: Float,
        y: Float,
        width: Float,
        height: Float
    ): Boolean {
        val s = scale(height)
        return x >= progressLeft(height) &&
            x <= progressRight(width, height) &&
            abs(y - progressY(height)) <= PROGRESS_HIT_HALF_HEIGHT * s
    }

    fun buttonIndexAt(
        x: Float,
        y: Float,
        width: Float,
        height: Float
    ): Int {
        val s = scale(height)
        val cy = buttonY(height)
        if (abs(y - cy) > BUTTON_HIT_HALF_SIZE * s) return -1

        val centers = floatArrayOf(width * 0.30f, width * 0.50f, width * 0.70f)
        val half = BUTTON_HIT_HALF_SIZE * s
        for (i in centers.indices) {
            if (abs(x - centers[i]) <= half) return i
        }
        return -1
    }

    fun positionForX(
        x: Float,
        width: Float,
        height: Float,
        durationMs: Long
    ): Long {
        if (durationMs <= 0L) return 0L
        val left = progressLeft(height)
        val right = progressRight(width, height)
        val span = (right - left).coerceAtLeast(1f)
        val ratio = ((x - left) / span).coerceIn(0f, 1f)
        return (durationMs * ratio).toLong().coerceIn(0L, durationMs)
    }

    fun formatTime(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }
}
