package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayVisibilityPolicyTest {
    private val homes = setOf("com.cusc.launcher")

    @Test fun carLifeOnlyModeShowsCardOnlyForHeadUnitCarLife() {
        assertEquals(
            OverlayPresentation.CARD,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = true,
                foregroundPackage = OverlayVisibilityPolicy.CARLIFE_HU_PACKAGE,
                homePackages = homes
            )
        )
        assertEquals(
            OverlayPresentation.TOP_STRIP,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = true,
                foregroundPackage = "com.android.settings",
                homePackages = homes
            )
        )
        assertEquals(
            OverlayPresentation.TOP_STRIP,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = true,
                foregroundPackage = "com.cusc.launcher",
                homePackages = homes
            )
        )
        assertEquals(
            OverlayPresentation.TOP_STRIP,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = true,
                foregroundPackage = "",
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
                homePackages = homes
            )
        )
        assertEquals(
            OverlayPresentation.CARD,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = false,
                foregroundPackage = OverlayVisibilityPolicy.CARLIFE_HU_PACKAGE,
                homePackages = homes
            )
        )
        assertEquals(
            OverlayPresentation.CARD,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = false,
                foregroundPackage = "com.android.settings",
                homePackages = homes
            )
        )
        assertEquals(
            OverlayPresentation.CARD,
            OverlayVisibilityPolicy.presentation(
                carLifeOnlyCard = false,
                foregroundPackage = "",
                homePackages = homes
            )
        )
    }
}
