package com.novemberraining.psalyric

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class LyricCacheStats(
    val entries: Int,
    val bytes: Long
) {
    val megabytes: Double get() = bytes.toDouble() / (1024.0 * 1024.0)
}

object LyricDiskCache {
    const val MAX_ENTRIES = 300
    const val TARGET_ENTRIES = 240
    const val MAX_BYTES = 32L * 1024L * 1024L
    const val TARGET_BYTES = 24L * 1024L * 1024L

    private const val VERSION = 1
    private const val DIR = "lyrics-cache-v1"
    private const val MAX_SINGLE_FILE_BYTES = 4L * 1024L * 1024L
    private const val DURATION_TOLERANCE_MS = 20_000L

    fun directory(context: Context): File =
        File(context.noBackupFilesDir, DIR)

    @Synchronized
    fun load(context: Context, query: TrackQuery): LyricDocument? {
        val key = cacheKey(query) ?: return null
        val file = File(directory(context), "$key.json")
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_SINGLE_FILE_BYTES) {
            return null
        }

        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("version", -1) != VERSION) return null
            if (!sameIdentity(root, query)) return null

            val storedDuration = root.optLong("durationMs", -1L)
            if (
                storedDuration > 0L &&
                query.durationMs > 0L &&
                kotlin.math.abs(storedDuration - query.durationMs) > DURATION_TOLERANCE_MS
            ) {
                return null
            }

            val linesJson = root.optJSONArray("lines") ?: return null
            val lines = ArrayList<LyricLine>(linesJson.length())
            for (i in 0 until linesJson.length()) {
                val lineJson = linesJson.optJSONObject(i) ?: continue
                val wordsJson = lineJson.optJSONArray("words")
                val words = ArrayList<WordTiming>(wordsJson?.length() ?: 0)
                if (wordsJson != null) {
                    for (j in 0 until wordsJson.length()) {
                        val word = wordsJson.optJSONObject(j) ?: continue
                        val text = word.optString("text")
                        if (text.isEmpty()) continue
                        words += WordTiming(
                            startMs = word.optLong("startMs", 0L),
                            durationMs = word.optLong("durationMs", 0L).coerceAtLeast(0L),
                            text = text
                        )
                    }
                }

                val text = lineJson.optString("text")
                if (text.isBlank()) continue
                val alignment = runCatching {
                    DuetAlignment.valueOf(lineJson.optString("alignment", DuetAlignment.LEFT.name))
                }.getOrDefault(DuetAlignment.LEFT)

                lines += LyricLine(
                    startMs = lineJson.optLong("startMs", 0L),
                    durationMs = lineJson.optLong("durationMs", 0L).coerceAtLeast(0L),
                    text = text,
                    translation = lineJson.optString("translation"),
                    romaji = lineJson.optString("romaji"),
                    words = words,
                    alignment = alignment
                )
            }

            if (lines.isEmpty()) return null
            file.setLastModified(System.currentTimeMillis())
            val origin = root.optString("source").ifBlank { "未知来源" }
            LyricDocument(
                lines = DuetLayout.process(lines.sortedBy { it.startMs }),
                source = "本地缓存 · $origin",
                resolvedArtist = root.optString("resolvedArtist")
            )
        } catch (_: Throwable) {
            // A partial/corrupt cache entry should never block online lookup.
            runCatching { file.delete() }
            null
        }
    }

    @Synchronized
    fun save(context: Context, query: TrackQuery, document: LyricDocument) {
        if (document.lines.isEmpty()) return
        if (document.source.startsWith("本地缓存")) return
        val key = cacheKey(query) ?: return

        val dir = directory(context)
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) return

        val root = JSONObject()
            .put("version", VERSION)
            .put("titleKey", canonical(query.title))
            .put("artistKey", canonical(query.artist))
            .put("title", query.title)
            .put("artist", query.artist)
            .put("durationMs", query.durationMs)
            .put("source", document.source)
            .put("resolvedArtist", document.resolvedArtist)
            .put("savedAt", System.currentTimeMillis())

        val lines = JSONArray()
        for (line in document.lines) {
            val words = JSONArray()
            for (word in line.words) {
                words.put(
                    JSONObject()
                        .put("startMs", word.startMs)
                        .put("durationMs", word.durationMs)
                        .put("text", word.text)
                )
            }
            lines.put(
                JSONObject()
                    .put("startMs", line.startMs)
                    .put("durationMs", line.durationMs)
                    .put("text", line.text)
                    .put("translation", line.translation)
                    .put("romaji", line.romaji)
                    .put("alignment", line.alignment.name)
                    .put("words", words)
            )
        }
        root.put("lines", lines)

        val target = File(dir, "$key.json")
        val temp = File(dir, "$key.tmp")
        try {
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (temp.length() <= 0L || temp.length() > MAX_SINGLE_FILE_BYTES) {
                temp.delete()
                return
            }
            if (target.exists() && !target.delete()) {
                temp.delete()
                return
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target.setLastModified(System.currentTimeMillis())
            trimLocked(dir)
        } catch (_: Throwable) {
            runCatching { temp.delete() }
        }
    }

    @Synchronized
    fun stats(context: Context): LyricCacheStats {
        val files = cacheFiles(directory(context))
        return LyricCacheStats(
            entries = files.size,
            bytes = files.sumOf { it.length() }
        )
    }

    @Synchronized
    fun clear(context: Context): Int {
        val dir = directory(context)
        var deleted = 0
        dir.listFiles()?.forEach { file ->
            if ((file.extension == "json" || file.extension == "tmp") && file.delete()) {
                deleted++
            }
        }
        return deleted
    }

    private fun trimLocked(dir: File) {
        dir.listFiles()
            ?.filter { it.isFile && it.extension == "tmp" }
            ?.filter { System.currentTimeMillis() - it.lastModified() > 60_000L }
            ?.forEach { runCatching { it.delete() } }

        val files = cacheFiles(dir).sortedBy { it.lastModified() }.toMutableList()
        var count = files.size
        var bytes = files.sumOf { it.length() }
        if (count <= MAX_ENTRIES && bytes <= MAX_BYTES) return

        for (file in files) {
            if (count <= TARGET_ENTRIES && bytes <= TARGET_BYTES) break
            val size = file.length()
            if (file.delete()) {
                count--
                bytes -= size
            }
        }
    }

    private fun cacheFiles(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            .orEmpty()

    private fun sameIdentity(root: JSONObject, query: TrackQuery): Boolean =
        root.optString("titleKey") == canonical(query.title) &&
            root.optString("artistKey") == canonical(query.artist)

    private fun cacheKey(query: TrackQuery): String? {
        val title = canonical(query.title)
        val artist = canonical(query.artist)
        if (title.isBlank()) return null
        val identity = "$title|$artist"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString(separator = "") {
            "%02x".format(Locale.ROOT, it.toInt() and 0xff)
        }
    }

    private fun canonical(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("""[\p{P}\s·・]+"""), "")
            .trim()
}
