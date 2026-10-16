package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricSecondaryRulesTest {
    @Test fun disabledAlwaysHidesSecondaryLyric() {
        assertEquals(
            "",
            LyricSecondaryRules.select(
                enabled = false,
                mode = LyricSecondaryRules.MODE_TRANSLATION,
                translation = "翻译",
                romaji = "romanization"
            )
        )
    }

    @Test fun translationModeDoesNotRandomlyFallBackToRomaji() {
        assertEquals(
            "",
            LyricSecondaryRules.select(
                enabled = true,
                mode = LyricSecondaryRules.MODE_TRANSLATION,
                translation = "",
                romaji = "romanization"
            )
        )
    }

    @Test fun romajiModeDoesNotRandomlyFallBackToTranslation() {
        assertEquals(
            "",
            LyricSecondaryRules.select(
                enabled = true,
                mode = LyricSecondaryRules.MODE_ROMAJI,
                translation = "翻译",
                romaji = ""
            )
        )
    }

    @Test fun selectedModeReturnsOnlySelectedField() {
        assertEquals(
            "翻译",
            LyricSecondaryRules.select(
                enabled = true,
                mode = LyricSecondaryRules.MODE_TRANSLATION,
                translation = "翻译",
                romaji = "romanization"
            )
        )
        assertEquals(
            "romanization",
            LyricSecondaryRules.select(
                enabled = true,
                mode = LyricSecondaryRules.MODE_ROMAJI,
                translation = "翻译",
                romaji = "romanization"
            )
        )
    }
}
