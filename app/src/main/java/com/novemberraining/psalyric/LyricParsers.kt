package com.novemberraining.psalyric

import java.util.TreeMap
import java.util.regex.Pattern

object LyricParsers {
    private val lrcTime = Pattern.compile("""\[(\d{1,3}):(\d{2})(?:[\.:](\d{1,3}))?]""")
    private val yrcLine = Pattern.compile("""^\[(\d+),(\d+)](.*)$""")
    private val yrcWord = Pattern.compile("""\((\d+),(\d+),\d+\)""")
    private val krcLine = Pattern.compile("""^\[(\d+),(\d+)](.*)$""")
    private val krcWord = Pattern.compile("""<(\d+),(\d+),\d+>""")
    private val duetPrefix = Pattern.compile("""^\s*(男|女|合)\s*[:：]\s*""")

    fun parse(
        lrc: String,
        translationLrc: String = "",
        yrc: String = "",
        romajiLrc: String = ""
    ): LyricDocument {
        val base = if (yrc.isNotBlank()) parseYrc(yrc) else parseLrc(lrc)
        if (base.isEmpty()) return LyricDocument.EMPTY
        val translations = parseLrcMap(translationLrc)
        val romaji = parseLrcMap(romajiLrc)
        val merged = base.map { raw ->
            val duet = cleanDuet(raw.text)
            raw.copy(
                text = duet.first,
                alignment = duet.second,
                translation = translations[raw.startMs]?.let(::cleanDuet)?.first.orEmpty(),
                romaji = romaji[raw.startMs]?.let(::cleanDuet)?.first.orEmpty()
            )
        }
        return LyricDocument(merged)
    }

    fun parseLrc(text: String): List<LyricLine> =
        parseLrcMap(text).map { (time, value) -> LyricLine(time, 0, value) }

    fun parseKrc(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            val line = krcLine.matcher(raw.trim())
            if (!line.matches()) continue
            val start = line.group(1)?.toLongOrNull() ?: continue
            val duration = line.group(2)?.toLongOrNull() ?: 0L
            val body = line.group(3).orEmpty()
            val tags = ArrayList<Triple<Int, Long, Long>>()
            val matcher = krcWord.matcher(body)
            while (matcher.find()) {
                val offset = matcher.group(1)?.toLongOrNull() ?: 0L
                val wordDuration = matcher.group(2)?.toLongOrNull() ?: 0L
                tags += Triple(matcher.end(), offset, wordDuration)
            }
            val words = ArrayList<WordTiming>()
            val plain = StringBuilder()
            for (i in tags.indices) {
                val item = tags[i]
                val end = if (i + 1 < tags.size) findKrcTagStart(body, tags[i + 1].first) else body.length
                val wordText = body.substring(item.first, end).replace(krcWord.toRegex(), "")
                if (wordText.isNotEmpty()) {
                    plain.append(wordText)
                    words += WordTiming(start + item.second, item.third, wordText)
                }
            }
            val display = if (plain.isNotEmpty()) plain.toString() else krcWord.matcher(body).replaceAll("")
            if (display.isNotBlank()) out += LyricLine(start, duration, display.trim(), words = words)
        }
        return out.sortedBy { it.startMs }
    }

    private fun findKrcTagStart(body: String, endOfNextTag: Int): Int {
        var i = endOfNextTag - 1
        while (i >= 0 && body[i] != '<') i--
        return if (i >= 0) i else endOfNextTag
    }

    fun parseYrc(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            val line = yrcLine.matcher(raw.trim())
            if (!line.matches()) continue
            val start = line.group(1).toLongOrNull() ?: continue
            val duration = line.group(2).toLongOrNull() ?: 0L
            val body = line.group(3)
            val matches = ArrayList<Triple<Int, Long, Long>>()
            val matcher = yrcWord.matcher(body)
            while (matcher.find()) {
                matches += Triple(matcher.end(), matcher.group(1).toLong(), matcher.group(2).toLong())
            }
            val words = ArrayList<WordTiming>()
            val plain = StringBuilder()
            for (i in matches.indices) {
                val item = matches[i]
                val end = if (i + 1 < matches.size) {
                    val nextMatcherStart = findTagStart(body, matches[i + 1].first)
                    nextMatcherStart
                } else body.length
                val wordText = body.substring(item.first, end)
                    .replace(yrcWord.toRegex(), "")
                if (wordText.isNotEmpty()) {
                    plain.append(wordText)
                    words += WordTiming(item.second, item.third, wordText)
                }
            }
            val display = if (plain.isNotEmpty()) plain.toString()
            else yrcWord.matcher(body).replaceAll("")
            if (display.isNotBlank()) out += LyricLine(start, duration, display.trim(), words = words)
        }
        return out.sortedBy { it.startMs }
    }

    private fun findTagStart(body: String, endOfNextTag: Int): Int {
        var i = endOfNextTag - 1
        while (i >= 0 && body[i] != '(') i--
        return if (i >= 0) i else endOfNextTag
    }

    private fun parseLrcMap(text: String): TreeMap<Long, String> {
        val map = TreeMap<Long, String>()
        for (raw in text.lineSequence()) {
            val matcher = lrcTime.matcher(raw)
            val times = ArrayList<Long>()
            var lastEnd = -1
            while (matcher.find()) {
                val min = matcher.group(1).toLong()
                val sec = matcher.group(2).toLong()
                val fracRaw = matcher.group(3).orEmpty()
                val ms = when (fracRaw.length) {
                    0 -> 0L
                    1 -> fracRaw.toLong() * 100L
                    2 -> fracRaw.toLong() * 10L
                    else -> fracRaw.take(3).padEnd(3, '0').toLong()
                }
                times += min * 60_000L + sec * 1_000L + ms
                lastEnd = matcher.end()
            }
            if (times.isEmpty()) continue
            val lyric = raw.substring(lastEnd.coerceAtLeast(0)).trim()
            if (lyric.isEmpty()) continue
            for (time in times) map[time] = lyric
        }
        return map
    }

    private fun cleanDuet(text: String): Pair<String, DuetAlignment> {
        val matcher = duetPrefix.matcher(text)
        if (!matcher.find()) return text.trim() to DuetAlignment.LEFT
        val alignment = when (matcher.group(1)) {
            "男" -> DuetAlignment.LEFT
            "女" -> DuetAlignment.RIGHT
            else -> DuetAlignment.CENTER
        }
        return text.substring(matcher.end()).trim() to alignment
    }
}
