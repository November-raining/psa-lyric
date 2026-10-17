package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayVisibilityPolicyTest {
    private val homes = setOf("com.cusc.launcher")

    @Test fun carLifeOnlyModeRequiresForegroundAndConnected() {
        assertEquals(
            OverlayPresentation.CARD,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = true,
                foregroundPackage = OverlayVisibilityPolicy.CARLIFE_HU_PACKAGE,
                carLifeConnected = true,
                homePackages = homes
            )
        )
        assertEquals(
            OverlayPresentation.TOP_STRIP,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = true,
                foregroundPackage = OverlayVisibilityPolicy.CARLIFE_HU_PACKAGE,
                carLifeConnected = false,
                homePackages = homes
            )
        )
        assertEquals(
            OverlayPresentation.TOP_STRIP,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = true,
                foregroundPackage = "com.android.settings",
                carLifeConnected = true,
                homePackages = homes
            )
        )
        assertEquals(
            OverlayPresentation.TOP_STRIP,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = true,
                foregroundPackage = "",
                carLifeConnected = true,
                homePackages = homes
            )
        )
    }

    @Test fun disabledModePreservesOldLauncherOnlyStripBehavior() {
        assertEquals(
            OverlayPresentation.TOP_STRIP,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = false,
                foregroundPackage = "com.cusc.launcher",
                carLifeConnected = false,
                homePackages = homes
            )
        )
        assertEquals(
            OverlayPresentation.CARD,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = false,
                foregroundPackage = OverlayVisibilityPolicy.CARLIFE_HU_PACKAGE,
                carLifeConnected = false,
                homePackages = homes
            )
        )
        assertEquals(
            OverlayPresentation.CARD,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = false,
                foregroundPackage = "com.android.settings",
                carLifeConnected = false,
                homePackages = homes
            )
        )
        assertEquals(
            OverlayPresentation.CARD,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = false,
                foregroundPackage = "",
                carLifeConnected = false,
                homePackages = homes
            )
        )
    }
}
