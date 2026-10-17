package com.novemberraining.psalyric

enum class OverlayPresentation {
    CARD,
    TOP_STRIP,
    HIDDEN
}

object OverlayVisibilityPolicy {
    const val CARLIFE_HU_PACKAGE = "com.baidu.carlifevehicle"

    fun presentation(
        bridgeEnabled: Boolean,
        carLifeOnlyCard: Boolean,
        foregroundPackage: String,
        carLifeConnected: Boolean,
        homePackages: Set<String>
    ): OverlayPresentation {
        // The CarLife-side switch is authoritative. When the integration is
        // disabled, neither the large card nor the top strip should cover the
        // native CarLife UI.
        if (!bridgeEnabled) return OverlayPresentation.HIDDEN

        if (carLifeOnlyCard) {
            return if (
                foregroundPackage == CARLIFE_HU_PACKAGE &&
                carLifeConnected
            ) {
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
