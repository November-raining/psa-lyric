package com.novemberraining.psalyric

data class OverlayDisplaySize(val width: Int, val height: Int)

object OverlayDisplaySpace {
    fun choose(
        appWidth: Int,
        appHeight: Int,
        realWidth: Int,
        realHeight: Int,
        carLifeForeground: Boolean
    ): OverlayDisplaySize {
        return if (
            carLifeForeground &&
            realWidth > 0 &&
            realHeight > 0
        ) {
            OverlayDisplaySize(realWidth, realHeight)
        } else {
            OverlayDisplaySize(appWidth, appHeight)
        }
    }
}
