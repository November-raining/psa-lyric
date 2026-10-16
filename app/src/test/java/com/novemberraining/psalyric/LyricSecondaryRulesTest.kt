package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun sparseTranslationDoesNotCountAsComplete() {
        val sparse = LyricDocument(
            (0 until 34).map { index ->
                LyricLine(
                    startMs = index * 1000L,
                    durationMs = 1000L,
                    text = "line-$index",
                    translation = if (index < 5) "译-$index" else ""
                )
            }
        )
        assertEquals(16, LyricSecondaryRules.minimumUsefulLineCount(sparse))
        assertFalse(
            LyricSecondaryRules.hasAdequateData(
                sparse,
                LyricSecondaryRules.MODE_TRANSLATION
            )
        )

        val useful = sparse.copy(
            lines = sparse.lines.mapIndexed { index, line ->
                line.copy(translation = if (index < 16) "译-$index" else "")
            }
        )
        assertTrue(
            LyricSecondaryRules.hasAdequateData(
                useful,
                LyricSecondaryRules.MODE_TRANSLATION
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
