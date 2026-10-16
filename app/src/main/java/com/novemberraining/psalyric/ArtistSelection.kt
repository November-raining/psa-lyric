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

    /**
     * Online lyric search often resolves the exact QQ/Kugou song even when the
     * car MediaSession exposes only the primary singer. Promote that richer
     * identity only when it still contains the media-session artist.
     */
    fun chooseResolved(primary: String, resolved: String): String {
        val base = primary.trim()
        val candidate = resolved.trim()
        if (candidate.isBlank()) return normalizeSeparators(base)
        if (base.isBlank()) return normalizeSeparators(candidate)

        val baseKey = canonical(base)
        val candidateKey = canonical(candidate)
        val compatible =
            candidateKey.contains(baseKey) ||
                baseKey.contains(candidateKey) ||
                artistParts(base).any { left ->
                    artistParts(candidate).any { right ->
                        canonical(left) == canonical(right)
                    }
                }

        return if (
            compatible &&
            collaborationRichness(candidate) >= collaborationRichness(base)
        ) {
            normalizeSeparators(candidate)
        } else {
            normalizeSeparators(base)
        }
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

    private fun artistParts(value: String): List<String> =
        value.split(Regex("""\s*(?:[&＆/、,，]|\bfeat\.?\b|\bft\.?\b)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun normalizeSeparators(value: String): String =
        value.trim()
            .replace(
                Regex("""\s*(?:[&＆/、,，])\s*"""),
                "&"
            )
            .replace(Regex("""&{2,}"""), "&")
            .trim('&', ' ')

    private fun sameIdentity(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        return canonical(left) == canonical(right)
    }

    private fun canonical(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("""[\p{P}\s·・]+"""), "")
}
