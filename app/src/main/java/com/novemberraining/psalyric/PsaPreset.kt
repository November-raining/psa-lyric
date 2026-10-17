package com.novemberraining.psalyric

data class OverlayGeometry(val x: Int, val y: Int, val width: Int, val height: Int)

object PsaPreset {
    const val BASE_WIDTH = 1920
    const val BASE_HEIGHT = 720

    // Measured from the real CarLife 1920×720 music card screenshot.
    // The visible tangent points are ~30 px from each edge; keep this tied to
    // the 700 px reference card height so all four corners scale consistently.
    const val CARD_CORNER_RADIUS = 30f

    fun cardCornerRadius(height: Int): Float =
        CARD_CORNER_RADIUS * height / 700f

    fun scale(x: Int, y: Int, width: Int, height: Int, screenW: Int, screenH: Int): OverlayGeometry {
        val sx = screenW.toFloat() / BASE_WIDTH
        val sy = screenH.toFloat() / BASE_HEIGHT
        return OverlayGeometry(
            (x * sx).toInt(),
            (y * sy).toInt(),
            (width * sx).toInt().coerceAtLeast(1),
            (height * sy).toInt().coerceAtLeast(1)
        )
    }
}
