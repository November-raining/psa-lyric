package com.novemberraining.psalyric

data class WordTiming(
    val startMs: Long,
    val durationMs: Long,
    val text: String
) {
    val endMs: Long get() = startMs + durationMs
}

enum class DuetAlignment { LEFT, RIGHT, CENTER }

data class LyricLine(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
    val translation: String = "",
    val romaji: String = "",
    val words: List<WordTiming> = emptyList(),
    val alignment: DuetAlignment = DuetAlignment.LEFT
) {
    val endMs: Long
        get() = if (durationMs > 0) startMs + durationMs
        else words.lastOrNull()?.endMs ?: startMs
}

data class LyricDocument(
    val lines: List<LyricLine>,
    val source: String = ""
) {
    companion object {
        val EMPTY = LyricDocument(emptyList())
    }

    val hasWordTimings: Boolean
        get() = lines.any { it.words.isNotEmpty() }

    val timedWordCount: Int
        get() = lines.sumOf { it.words.size }

    fun currentIndex(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) lo = mid + 1 else hi = mid
        }
        return (lo - 1).coerceAtLeast(0)
    }
}

object LyricStore {
    @Volatile var document: LyricDocument = LyricDocument.EMPTY
}
