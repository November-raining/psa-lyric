package com.novemberraining.psalyric

object LyricSecondaryRules {
    const val MODE_TRANSLATION = "translation"
    const val MODE_ROMAJI = "romaji"

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
}
