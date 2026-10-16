package com.novemberraining.psalyric

/**
 * Assigns left/right/center alignment for duet lyrics while preserving word timing.
 *
 * Supported structures mirror the mature patterns used by md3Music and QRC tooling:
 * - inline gender prefixes: "男：...", "女：...", "合：..."
 * - standalone gender marker rows whose state applies to following lyric rows
 * - standalone singer marker rows such as "周杰伦：" / "Lara梁心颐："
 * - multi-singer marker rows such as "周杰伦/Lara梁心颐：" -> center
 *
 * Singer/role marker rows are metadata rather than sung text, so they are removed.
 */
object DuetLayout {
    private val genderPrefix = Regex("""^\s*(男|女|合)\s*[:：]\s*""")
    private val singerMarker = Regex("""^\s*(.+?)\s*[:：]\s*$""")
    private val singerSplit = Regex("""\s*[/&＆、+]\s*""")
    private val credits = Regex(
        """^\s*(lyrics\s*by|composed\s*by|produced\s*by|music\s*by|words\s*by|""" +
            """arranged\s*by|mixed\s*by|recorded\s*by|written\s*by|""" +
            """作词|作曲|编曲|混音|录音|制作人|制作|词|曲|""" +
            """cover|vocal|guitar|bass|drum|keyboard|piano|string)\s*(?:[:：]|$)""",
        RegexOption.IGNORE_CASE
    )

    fun process(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return lines

        var firstGender: String? = null
        var leftSinger: String? = null
        var rightSinger: String? = null

        // Discover the two primary voices first so marker order is deterministic.
        for (line in lines) {
            val text = line.text.trim()
            val gender = genderPrefix.find(text)?.groupValues?.getOrNull(1)
            if (gender == "男" || gender == "女") {
                firstGender = firstGender ?: gender
                continue
            }

            val singers = singerMarker(text)
            if (singers?.size == 1) {
                val singer = singers.first()
                if (leftSinger == null) {
                    leftSinger = singer
                } else if (rightSinger == null && !sameSinger(leftSinger, singer)) {
                    rightSinger = singer
                }
            }
        }

        val leftGender = firstGender ?: "男"
        val rightGender = if (leftGender == "男") "女" else "男"

        var activeGender: String? = null
        var activeSingers: List<String>? = null
        val out = ArrayList<LyricLine>(lines.size)

        for (line in lines) {
            val text = line.text
            val genderMatch = genderPrefix.find(text)
            if (genderMatch != null) {
                val gender = genderMatch.groupValues[1]
                activeGender = gender
                activeSingers = null
                val cleanedText = text.substring(genderMatch.range.last + 1).trimStart()
                if (cleanedText.isBlank()) {
                    // Standalone role marker: keep its state, not its visual row.
                    continue
                }

                out += line.copy(
                    text = cleanedText.trim(),
                    translation = stripGenderPrefix(line.translation),
                    romaji = stripGenderPrefix(line.romaji),
                    words = stripPrefixWords(
                        line.words,
                        genderMatch.range.last + 1
                    ),
                    alignment = genderAlignment(gender, leftGender, rightGender)
                )
                continue
            }

            val singers = singerMarker(text)
            if (singers != null) {
                activeSingers = singers
                activeGender = null
                // QRC singer marker rows are metadata and should not appear as lyrics.
                continue
            }

            val alignment = when {
                activeGender != null ->
                    genderAlignment(activeGender, leftGender, rightGender)
                activeSingers != null ->
                    singerAlignment(activeSingers, leftSinger, rightSinger)
                else -> line.alignment
            }
            out += line.copy(alignment = alignment)
        }

        return out
    }

    private fun singerMarker(text: String): List<String>? {
        if (text.isBlank()) return null
        if (genderPrefix.containsMatchIn(text)) return null
        if (credits.containsMatchIn(text)) return null

        val match = singerMarker.matchEntire(text) ?: return null
        val label = match.groupValues[1].trim()
        if (label.isBlank() || label.length > 80) return null
        if (label.any { it == '[' || it == ']' || it == '(' || it == ')' }) return null

        val singers = label
            .split(singerSplit)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return singers.takeIf { it.isNotEmpty() }
    }

    private fun genderAlignment(
        gender: String,
        leftGender: String,
        rightGender: String
    ): DuetAlignment = when {
        gender == "合" -> DuetAlignment.CENTER
        gender == leftGender -> DuetAlignment.LEFT
        gender == rightGender -> DuetAlignment.RIGHT
        else -> DuetAlignment.LEFT
    }

    private fun singerAlignment(
        singers: List<String>,
        leftSinger: String?,
        rightSinger: String?
    ): DuetAlignment {
        if (singers.size > 1) return DuetAlignment.CENTER
        val singer = singers.firstOrNull() ?: return DuetAlignment.LEFT
        if (leftSinger != null && sameSinger(leftSinger, singer)) return DuetAlignment.LEFT
        if (rightSinger != null && sameSinger(rightSinger, singer)) return DuetAlignment.RIGHT
        return DuetAlignment.CENTER
    }

    private fun sameSinger(a: String, b: String): Boolean =
        canonicalSinger(a) == canonicalSinger(b)

    private fun canonicalSinger(value: String): String =
        value.lowercase()
            .replace(Regex("""[\s._\-·・]+"""), "")

    private fun stripGenderPrefix(value: String): String =
        genderPrefix.replaceFirst(value, "").trim()

    private fun stripPrefixWords(
        words: List<WordTiming>,
        prefixCharCount: Int
    ): List<WordTiming> {
        if (words.isEmpty() || prefixCharCount <= 0) return words

        var remaining = prefixCharCount
        val out = ArrayList<WordTiming>(words.size)
        for (word in words) {
            if (remaining <= 0) {
                out += word
                continue
            }

            val length = word.text.length
            if (remaining >= length) {
                remaining -= length
                continue
            }

            val tail = word.text.substring(remaining)
            remaining = 0
            if (tail.isNotEmpty()) out += word.copy(text = tail)
        }

        // If the word segmentation did not cover the prefix reliably, preserve timing
        // rather than risk deleting actual lyrics.
        return if (remaining > 0) words else out
    }
}
