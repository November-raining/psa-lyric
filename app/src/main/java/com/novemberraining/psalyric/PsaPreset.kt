package com.novemberraining.psalyric

data class OverlayGeometry(val x: Int, val y: Int, val width: Int, val height: Int)

object PsaPreset {
    const val BASE_WIDTH = 1920
    const val BASE_HEIGHT = 720

    const val PRESET_LOWER_RIGHT = "carlife_lower_right"
    const val PRESET_FULL_HEIGHT = "carlife_full_height"
    const val PRESET_CUSTOM = "custom"

    // Pixel-matched against the supplied 1920×720 real-car screenshots.
    //
    // Full-height CarLife lyric/music card:
    //   x=1373, y=10, w=535, h=700
    //
    // CarLife dual-card layout:
    //   the lower music card keeps the exact same width/height and x position,
    //   but starts at y=249. Its logical bottom is therefore y=949 and the
    //   physical 720px display clips the lower 229px, matching CarLife.
    val LOWER_RIGHT = OverlayGeometry(1373, 249, 535, 700)
    val FULL_HEIGHT = OverlayGeometry(1373, 10, 535, 700)

    // Measured from the real CarLife 1920×720 music card screenshot.
    // The visible tangent points are ~30 px from each edge; keep this tied to
    // the 700 px reference card height so all four corners scale consistently.
    const val CARD_CORNER_RADIUS = 30f

    fun cardCornerRadius(height: Int): Float =
        CARD_CORNER_RADIUS * height / 700f

    fun normalizePreset(value: String?): String = when (value) {
        PRESET_FULL_HEIGHT -> PRESET_FULL_HEIGHT
        PRESET_CUSTOM -> PRESET_CUSTOM
        else -> PRESET_LOWER_RIGHT
    }

    fun geometryForPreset(value: String?): OverlayGeometry = when (normalizePreset(value)) {
        PRESET_FULL_HEIGHT -> FULL_HEIGHT
        else -> LOWER_RIGHT
    }

    fun scale(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        screenW: Int,
        screenH: Int
    ): OverlayGeometry {
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
