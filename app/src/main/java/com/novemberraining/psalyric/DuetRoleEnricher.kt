package com.novemberraining.psalyric

import java.util.Locale
import kotlin.math.abs

data class DuetLayoutStats(
    val left: Int,
    val right: Int,
    val center: Int
) {
    val splitCount: Int get() = right + center
    val hasSplit: Boolean get() = splitCount > 0
}

object DuetRoleEnricher {
    private const val MAX_EXACT_TIME_DISTANCE_MS = 12_000L
    private const val MAX_FUZZY_TIME_DISTANCE_MS = 2_000L

    fun stats(document: LyricDocument): DuetLayoutStats =
        DuetLayoutStats(
            left = document.lines.count { it.alignment == DuetAlignment.LEFT },
            right = document.lines.count { it.alignment == DuetAlignment.RIGHT },
            center = document.lines.count { it.alignment == DuetAlignment.CENTER }
        )

    fun hasSplit(document: LyricDocument): Boolean = stats(document).hasSplit

    /**
     * Keep the primary provider's timing/text and borrow only duet alignment from
     * another provider when the primary document contains no split information.
     */
    fun enrich(
        primary: LyricDocument,
        donors: List<LyricDocument>
    ): LyricDocument {
        if (primary.lines.isEmpty() || hasSplit(primary)) return primary

        val usefulDonors = donors
            .filter { it !== primary && it.lines.isNotEmpty() && hasSplit(it) }
        if (usefulDonors.isEmpty()) return primary

        val donor = usefulDonors.maxByOrNull { donorScore(primary, it) } ?: return primary
        val byText = donor.lines
            .filter { canonicalText(it.text).isNotBlank() }
            .groupBy { canonicalText(it.text) }

        var transferred = 0
        var transferredSplit = 0
        val mapped = primary.lines.map { line ->
            val key = canonicalText(line.text)
            val exact = byText[key]
                ?.minByOrNull { abs(it.startMs - line.startMs) }
                ?.takeIf { abs(it.startMs - line.startMs) <= MAX_EXACT_TIME_DISTANCE_MS }

            val fallback = exact ?: nearestFuzzy(line, donor.lines)
            if (fallback == null) {
                line
            } else {
                transferred++
                if (fallback.alignment != DuetAlignment.LEFT) transferredSplit++
                line.copy(alignment = fallback.alignment)
            }
        }

        // One accidental right/center match is too weak to rewrite a whole song.
        if (transferredSplit < 2 || transferred < 3) return primary
        return primary.copy(lines = mapped)
    }

    private fun donorScore(primary: LyricDocument, donor: LyricDocument): Int {
        val donorStats = stats(donor)
        val primaryKeys = primary.lines.mapTo(HashSet()) { canonicalText(it.text) }
        val exactMatches = donor.lines.count {
            val key = canonicalText(it.text)
            key.isNotBlank() && key in primaryKeys
        }
        return exactMatches * 20 + donorStats.splitCount * 3 + donor.lines.size.coerceAtMost(100)
    }

    private fun nearestFuzzy(
        primary: LyricLine,
        donorLines: List<LyricLine>
    ): LyricLine? {
        val key = canonicalText(primary.text)
        if (key.length < 4) return null

        var best: LyricLine? = null
        var bestDistance = Long.MAX_VALUE
        for (candidate in donorLines) {
            val distance = abs(candidate.startMs - primary.startMs)
            if (distance > MAX_FUZZY_TIME_DISTANCE_MS || distance >= bestDistance) continue
            val donorKey = canonicalText(candidate.text)
            if (donorKey.length < 4) continue
            if (!key.contains(donorKey) && !donorKey.contains(key)) continue
            best = candidate
            bestDistance = distance
        }
        return best
    }

    private fun canonicalText(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("""[\p{P}\s·・]+"""), "")
}
