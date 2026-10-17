package com.novemberraining.psalyric

enum class OverlayPresentation {
    CARD,
    TOP_STRIP
}

object OverlayVisibilityPolicy {
    const val CARLIFE_HU_PACKAGE = "com.baidu.carlifevehicle"

    fun presentation(
        carLifeOnlyCard: Boolean,
        foregroundPackage: String,
        homePackages: Set<String>
    ): OverlayPresentation {
        if (carLifeOnlyCard) {
            return if (foregroundPackage == CARLIFE_HU_PACKAGE) {
                OverlayPresentation.CARD
            } else {
                OverlayPresentation.TOP_STRIP
            }
        }

        return if (
            foregroundPackage.isNotBlank() &&
            foregroundPackage in homePackages
        ) {
            OverlayPresentation.TOP_STRIP
        } else {
            OverlayPresentation.CARD
        }
    }
}
