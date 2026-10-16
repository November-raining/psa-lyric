package com.novemberraining.psalyric

import java.util.Locale

/**
 * Chooses the richest artist identity exposed by MediaSession/MediaMetadata without
 * replacing a reliable primary artist with an unrelated album or display subtitle.
 */
object ArtistSelection {
    fun choose(
        artist: String,
        albumArtist: String,
        displaySubtitle: String,
        queueSubtitle: String = "",
        album: String = "",
        title: String = ""
    ): String {
        val primary = artist.trim()
        val alternates = listOf(
            albumArtist.trim(),
            displaySubtitle.trim(),
            queueSubtitle.trim()
        )
            .filter { it.isNotBlank() }
            .filterNot { sameIdentity(it, album) || sameIdentity(it, title) }

        if (primary.isBlank()) {
            return alternates.maxByOrNull(::collaborationRichness).orEmpty()
        }

        var best = primary
        var bestRichness = collaborationRichness(primary)
        val primaryKey = canonical(primary)

        for (candidate in alternates) {
            val candidateKey = canonical(candidate)
            if (candidateKey.isBlank()) continue
            // Only promote a richer alias when it still contains the primary artist.
            if (!candidateKey.contains(primaryKey) && !primaryKey.contains(candidateKey)) continue

            val richness = collaborationRichness(candidate)
            if (richness > bestRichness) {
                best = candidate
                bestRichness = richness
            }
        }
        return best
    }

    fun looksCollaborative(value: String): Boolean =
        collaborationRichness(value) > 0

    private fun collaborationRichness(value: String): Int {
        val lower = value.lowercase(Locale.ROOT)
        var score = 0
        score += Regex("""[&＆/、,，]""").findAll(value).count() * 10
        if (Regex("""\b(feat\.?|ft\.?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            score += 10
        }
        return score
    }

    private fun sameIdentity(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        return canonical(left) == canonical(right)
    }

    private fun canonical(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("""[\p{P}\s·・]+"""), "")
}
