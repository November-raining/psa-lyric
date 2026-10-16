package com.novemberraining.psalyric

/**
 * Assigns left/right/center alignment for duet lyrics while preserving word timing.
 *
 * The layout intentionally accepts several lyric-authoring conventions because
 * providers do not expose duet roles consistently:
 * - inline gender roles: "男：...", "女：...", "合：..."
 * - bracket gender roles: "(男)...", "（女）...", "[合]..."
 * - standalone gender marker rows whose state applies to following rows
 * - standalone singer marker rows: "周杰伦：" / "Lara梁心颐："
 * - inline singer aliases: "杰：...", "SA：..."
 * - multi-singer labels: "周杰伦/Lara梁心颐：..." -> center
 *
 * Arbitrary singer-name prefixes are only activated when two distinct singer
 * labels are found in the same document. This avoids mistaking ordinary
 * colon-containing lyric text or metadata for duet roles.
 */
object DuetLayout {
    private data class RolePrefix(
        val role: String,
        val content: String,
        val consumedChars: Int
    )

    private data class SingerPrefix(
        val singers: List<String>,
        val content: String,
        val consumedChars: Int
    )

    private val genderColon = Regex("""^\s*(男|女|合)\s*[:：]\s*""")
    private val genderBracket = Regex(
        """^\s*[\(（\[【]\s*(男|女|合)\s*[\)）\]】]\s*[:：]?\s*"""
    )
    private val singerColon = Regex("""^\s*([^:：]{1,48}?)\s*[:：]\s*(.*)$""")
    private val singerSplit = Regex("""\s*[/&＆、+]\s*""")
    private val creditLabel = Regex(
        """^(lyrics\s*by|composed\s*by|produced\s*by|music\s*by|words\s*by|""" +
            """arranged\s*by|mixed\s*by|recorded\s*by|written\s*by|""" +
            """作词|作曲|编曲|混音|录音|制作人|制作|词|曲|""" +
            """cover|vocal|guitar|bass|drum|keyboard|piano|string)$""",
        RegexOption.IGNORE_CASE
    )

    fun process(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return lines

        var firstGender: String? = null
        val discoveredSingers = ArrayList<String>(4)

        // First pass: discover role vocabulary before mutating any row.
        for (line in lines) {
            val text = line.text.trim()
            val gender = genderPrefix(text)
            if (gender != null) {
                if (gender.role == "男" || gender.role == "女") {
                    firstGender = firstGender ?: gender.role
                }
                continue
            }

            val singer = singerPrefix(text) ?: continue
            if (singer.singers.size != 1) continue
            val value = singer.singers.first()
            if (discoveredSingers.none { sameSinger(it, value) }) {
                discoveredSingers += value
            }
        }

        // Generic aliases such as "杰：" / "SA：" are only meaningful when at
        // least two different voices are evidenced by the same lyric document.
        val singerMode = discoveredSingers.size >= 2
        val leftSinger = discoveredSingers.getOrNull(0)
        val rightSinger = discoveredSingers.getOrNull(1)

        val leftGender = firstGender ?: "男"
        val rightGender = if (leftGender == "男") "女" else "男"

        var activeGender: String? = null
        var activeSingers: List<String>? = null
        val out = ArrayList<LyricLine>(lines.size)

        for (line in lines) {
            val text = line.text

            val gender = genderPrefix(text)
            if (gender != null) {
                activeGender = gender.role
                activeSingers = null
                if (gender.content.isBlank()) {
                    // Pure marker row: state only, not visible lyrics.
                    continue
                }

                val cleaned = gender.content.trim()
                out += line.copy(
                    text = cleaned,
                    translation = stripSecondaryPrefix(
                        line.translation,
                        singerMode,
                        discoveredSingers
                    ),
                    romaji = stripSecondaryPrefix(
                        line.romaji,
                        singerMode,
                        discoveredSingers
                    ),
                    words = stripPrefixWords(
                        line.words,
                        cleaned,
                        gender.consumedChars
                    ),
                    alignment = genderAlignment(
                        gender.role,
                        leftGender,
                        rightGender
                    )
                )
                continue
            }

            val singer = if (singerMode) singerPrefix(text) else null
            if (singer != null && singersBelongToDocument(singer.singers, discoveredSingers)) {
                activeSingers = singer.singers
                activeGender = null
                if (singer.content.isBlank()) {
                    // Pure singer marker row.
                    continue
                }

                val cleaned = singer.content.trim()
                out += line.copy(
                    text = cleaned,
                    translation = stripSecondaryPrefix(
                        line.translation,
                        singerMode,
                        discoveredSingers
                    ),
                    romaji = stripSecondaryPrefix(
                        line.romaji,
                        singerMode,
                        discoveredSingers
                    ),
                    words = stripPrefixWords(
                        line.words,
                        cleaned,
                        singer.consumedChars
                    ),
                    alignment = singerAlignment(
                        singer.singers,
                        leftSinger,
                        rightSinger
                    )
                )
                continue
            }

            val alignment = when {
                activeGender != null ->
                    genderAlignment(activeGender, leftGender, rightGender)
                singerMode && activeSingers != null ->
                    singerAlignment(activeSingers, leftSinger, rightSinger)
                else -> line.alignment
            }
            out += line.copy(alignment = alignment)
        }

        return out
    }

    private fun genderPrefix(text: String): RolePrefix? {
        val bracket = genderBracket.find(text)
        if (bracket != null) {
            return RolePrefix(
                role = bracket.groupValues[1],
                content = text.substring(bracket.range.last + 1),
                consumedChars = bracket.range.last + 1
            )
        }

        val colon = genderColon.find(text) ?: return null
        return RolePrefix(
            role = colon.groupValues[1],
            content = text.substring(colon.range.last + 1),
            consumedChars = colon.range.last + 1
        )
    }

    private fun singerPrefix(text: String): SingerPrefix? {
        if (text.isBlank()) return null
        if (genderPrefix(text) != null) return null

        val match = singerColon.matchEntire(text) ?: return null
        val label = match.groupValues[1].trim()
        if (!plausibleSingerLabel(label)) return null

        val singers = label
            .split(singerSplit)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (singers.isEmpty()) return null

        return SingerPrefix(
            singers = singers,
            content = match.groupValues[2],
            consumedChars = match.groups[2]?.range?.first ?: text.length
        )
    }

    private fun plausibleSingerLabel(label: String): Boolean {
        if (label.isBlank() || label.length > 48) return false
        if (creditLabel.matches(label.trim())) return false
        if (label.any { it == '[' || it == ']' || it == '(' || it == ')' || it == '（' || it == '）' }) {
            return false
        }
        // A lyric sentence before a colon is unlikely to be a singer label.
        if (label.count { it.isWhitespace() } > 3) return false
        return true
    }

    private fun singersBelongToDocument(
        singers: List<String>,
        discovered: List<String>
    ): Boolean {
        if (singers.size > 1) {
            return singers.all { singer ->
                discovered.any { sameSinger(it, singer) }
            }
        }
        val singer = singers.firstOrNull() ?: return false
        return discovered.any { sameSinger(it, singer) }
    }

    private fun genderAlignment(
        gender: String?,
        leftGender: String,
        rightGender: String
    ): DuetAlignment = when {
        gender == "合" -> DuetAlignment.CENTER
        gender == leftGender -> DuetAlignment.LEFT
        gender == rightGender -> DuetAlignment.RIGHT
        else -> DuetAlignment.LEFT
    }

    private fun singerAlignment(
        singers: List<String>?,
        leftSinger: String?,
        rightSinger: String?
    ): DuetAlignment {
        if (singers.isNullOrEmpty()) return DuetAlignment.LEFT
        if (singers.size > 1) return DuetAlignment.CENTER

        val singer = singers.first()
        if (leftSinger != null && sameSinger(leftSinger, singer)) return DuetAlignment.LEFT
        if (rightSinger != null && sameSinger(rightSinger, singer)) return DuetAlignment.RIGHT

        // A third independent vocalist/background role is centered, mirroring
        // md3Music's "additional agent" behavior.
        return DuetAlignment.CENTER
    }

    private fun sameSinger(a: String, b: String): Boolean =
        canonicalSinger(a) == canonicalSinger(b)

    private fun canonicalSinger(value: String): String =
        value.lowercase()
            .replace(Regex("""[\s._\-·・]+"""), "")

    private fun stripSecondaryPrefix(
        value: String,
        singerMode: Boolean,
        discoveredSingers: List<String>
    ): String {
        if (value.isBlank()) return value

        val gender = genderPrefix(value)
        if (gender != null) return gender.content.trim()

        if (singerMode) {
            val singer = singerPrefix(value)
            if (
                singer != null &&
                singersBelongToDocument(singer.singers, discoveredSingers)
            ) {
                return singer.content.trim()
            }
        }
        return value.trim()
    }

    private fun stripPrefixWords(
        words: List<WordTiming>,
        cleanedText: String,
        fallbackPrefixChars: Int
    ): List<WordTiming> {
        if (words.isEmpty()) return words

        val source = words.joinToString(separator = "") { it.text }
        val located = source.indexOf(cleanedText)
        val prefixChars = when {
            located >= 0 -> located
            fallbackPrefixChars in 1 until source.length -> fallbackPrefixChars
            else -> return words
        }
        if (prefixChars <= 0) return words

        var remaining = prefixChars
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
            if (tail.isNotEmpty()) {
                out += word.copy(text = tail)
            }
        }

        return if (remaining > 0 || out.isEmpty()) words else out
    }
}
