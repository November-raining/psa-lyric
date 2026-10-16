package com.novemberraining.psalyric

/**
 * Horizontal placement only for the interlude three-dot group.
 *
 * Keep the existing AMLL/md3Music dot size, spacing and animation unchanged;
 * mirror the old left placement for RIGHT duet lines and center it for CENTER.
 */
object AppleInterludePlacement {
    fun centerX(
        width: Float,
        font: Float,
        alignment: DuetAlignment
    ): Float {
        val radius = font * 0.18f
        val spacing = font * 0.90f
        val startInset = font * 1.50f
        val leftCenter = startInset + spacing + radius
        return when (alignment) {
            DuetAlignment.RIGHT -> width - leftCenter
            DuetAlignment.CENTER -> width / 2f
            DuetAlignment.LEFT -> leftCenter
        }
    }
}
