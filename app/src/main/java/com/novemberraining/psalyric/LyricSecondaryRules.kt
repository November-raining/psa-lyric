package com.novemberraining.psalyric

import kotlin.math.ceil

object LyricSecondaryRules {
    const val MODE_TRANSLATION = "translation"
    const val MODE_ROMAJI = "romaji"

    private const val MIN_COVERAGE_RATIO = 0.45

    fun normalizeMode(value: String?): String =
        if (value == MODE_ROMAJI) MODE_ROMAJI else MODE_TRANSLATION

    fun select(
        enabled: Boolean,
        mode: String,
        translation: String,
        romaji: String
    ): String {
        if (!enabled) return ""
        return when (normalizeMode(mode)) {
            MODE_ROMAJI -> romaji.trim()
            else -> translation.trim()
        }
    }

    fun dataLineCount(document: LyricDocument, mode: String): Int =
        when (normalizeMode(mode)) {
            MODE_ROMAJI -> document.romajiLineCount
            else -> document.translationLineCount
        }

    fun minimumUsefulLineCount(document: LyricDocument): Int {
        val total = document.lines.count { it.text.isNotBlank() }
        if (total <= 0) return 0
        val ratioTarget = ceil(total * MIN_COVERAGE_RATIO).toInt().coerceAtLeast(1)
        return if (total >= 8) ratioTarget.coerceAtLeast(3) else ratioTarget
    }

    fun hasAdequateData(document: LyricDocument, mode: String): Boolean {
        val target = minimumUsefulLineCount(document)
        if (target <= 0) return false
        return dataLineCount(document, mode) >= target
    }
}
