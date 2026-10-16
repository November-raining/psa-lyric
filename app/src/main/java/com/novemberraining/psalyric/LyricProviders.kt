package com.novemberraining.psalyric

import android.content.Context
import android.text.Html
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class TrackQuery(
    val packageName: String,
    val mediaId: String,
    val title: String,
    val artist: String,
    val durationMs: Long
)

interface LyricProvider {
    val name: String
    fun load(context: Context, query: TrackQuery): LyricDocument?
}

object LocalLyricProvider : LyricProvider {
    override val name = "本地"

    override fun load(context: Context, query: TrackQuery): LyricDocument? {
        val dir = Prefs.localLyricDir(context)
        if (dir.isBlank()) return null
        val root = File(dir)
        if (!root.isDirectory) return null

        val baseNames = listOf(
            "${safe(query.title)} - ${safe(query.artist)}",
            safe(query.title)
        )
        val extensions = listOf("qrc", "yrc", "krc", "lrc")
        val file = baseNames.asSequence()
            .flatMap { base -> extensions.asSequence().map { ext -> File(root, "$base.$ext") } }
            .firstOrNull { it.isFile } ?: return null

        val bytes = file.readBytes()
        val text = decodeText(bytes)
        val doc = when (file.extension.lowercase(Locale.ROOT)) {
            "krc" -> {
                val decrypted = KrcCodec.decrypt(bytes)
                LyricDocument(LyricParsers.parseKrc(decrypted), source = "本地 · KRC")
            }
            "yrc" -> LyricDocument(LyricParsers.parseYrc(text), source = "本地 · YRC")
            "qrc" -> {
                val enhanced = QrcLyricCodec.toEnhancedTimeline(text)
                LyricDocument(LyricParsers.parseYrc(enhanced), source = "本地 · QRC")
            }
            else -> LyricParsers.parse(text).copy(source = "本地 · LRC")
        }
        return doc.takeIf { it.lines.isNotEmpty() }
    }

    private fun safe(v: String) =
        v.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
}

object QQLyricProvider : LyricProvider {
    override val name = "QQ"

    override fun load(context: Context, query: TrackQuery): LyricDocument? {
        val candidate = search(query) ?: return null

        if (candidate.songId > 0L) {
            try {
                val body = "version=15&miniversion=100&lrctype=4&musicid=${candidate.songId}"
                val response = http(
                    "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg",
                    "POST",
                    body,
                    "https://y.qq.com/"
                )
                val enhanced = QrcLyricCodec.decryptToTimedLyric(
                    QrcLyricCodec.encryptedContent(response, "content")
                )
                val translated = QrcLyricCodec.decryptToLrc(
                    QrcLyricCodec.encryptedContent(response, "contentts")
                )
                if (enhanced.isNotBlank()) {
                    val doc = LyricParsers.parse(
                        lrc = "",
                        translationLrc = translated,
                        yrc = enhanced
                    ).copy(source = "QQ · QRC")
                    if (doc.lines.isNotEmpty()) return doc
                }
            } catch (_: Throwable) {
                // Fall through to line-timed LRC. Engine diagnostics still records provider success/miss.
            }
        }

        val response = http(
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${enc(candidate.songMid)}&format=json&nobase64=1",
            "GET",
            referer = "https://y.qq.com/"
        )
        val root = JSONObject(response)
        val lyric = decodeEntities(root.optString("lyric", ""))
        val trans = decodeEntities(root.optString("trans", ""))
        val doc = LyricParsers.parse(lyric, trans).copy(source = "QQ · LRC")
        return doc.takeIf { it.lines.isNotEmpty() }
    }

    private data class Candidate(val songMid: String, val songId: Long)

    private fun search(query: TrackQuery): Candidate? {
        val queries = buildList {
            add(listOf(query.title, query.artist).filter { it.isNotBlank() }.joinToString(" "))
            if (query.artist.isNotBlank()) add(query.title)
        }.distinct()

        var bestMid = ""
        var bestId = -1L
        var bestScore = Int.MIN_VALUE

        for (keyword in queries) {
            val root = JSONObject(
                http(
                    "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=20&w=${enc(keyword)}&format=json",
                    "GET",
                    referer = "https://y.qq.com/"
                )
            )
            val list = root.optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("list") ?: continue

            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                val singers = s.optJSONArray("singer")
                val artist = singers?.optJSONObject(0)?.optString("name").orEmpty()
                val duration = s.optLong("interval", 0L) * 1000L
                if (!plausibleMatch(query, s.optString("songname"), artist, duration)) continue
                val score = matchScore(query, s.optString("songname"), artist, duration)
                if (score > bestScore) {
                    bestScore = score
                    bestMid = s.optString("songmid")
                    bestId = s.optLong("songid", -1L)
                }
            }
            if (bestScore >= 170) break
        }

        return if (bestScore >= 100 && bestMid.isNotBlank()) Candidate(bestMid, bestId) else null
    }
}

object NetEaseLyricProvider : LyricProvider {
    override val name = "网易云"

    private const val SEARCH = "https://music.163.com/api/search/get/web"
    private const val LYRIC = "https://interface3.music.163.com/eapi/song/lyric/v1"
    private const val EAPI_PATH = "/api/song/lyric/v1"
    private const val SEP = "-36cd479b6b5-"
    private val KEY = "e82ckenh8dichen8".toByteArray(Charsets.US_ASCII)

    override fun load(context: Context, query: TrackQuery): LyricDocument? {
        val direct = if (query.packageName.startsWith("com.netease.")) {
            standaloneSongId(query.mediaId)
        } else -1L

        if (direct > 0L) {
            loadById(direct)?.let { return it }
        }

        val id = search(query)
        if (id <= 0L || id == direct) return null
        return loadById(id)
    }

    private fun loadById(id: Long): LyricDocument? {
        val root = JSONObject(
            http(
                LYRIC,
                "POST",
                eapiBody(id),
                "https://music.163.com/",
                mapOf("Origin" to "https://music.163.com")
            )
        )
        if (root.optInt("code", 200) != 200) return null

        val lrc = root.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val translation = root.optJSONObject("tlyric")?.optString("lyric").orEmpty()
        val yrc = root.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val lineRomaji = root.optJSONObject("romalrc")?.optString("lyric").orEmpty()
        val wordRomaji = root.optJSONObject("yromalrc")?.optString("lyric").orEmpty()
        val romaji = if (wordRomaji.isNotBlank()) yrcToLrc(wordRomaji) else lineRomaji

        val doc = LyricParsers.parse(lrc, translation, yrc, romaji)
        if (doc.lines.isEmpty()) return null
        return doc.copy(source = if (doc.hasWordTimings) "网易云 · YRC" else "网易云 · LRC")
    }

    private fun search(query: TrackQuery): Long {
        val queries = buildList {
            add(listOf(query.title, query.artist).filter { it.isNotBlank() }.joinToString(" "))
            if (query.artist.isNotBlank()) add(query.title)
        }.distinct()

        var bestId = -1L
        var bestScore = Int.MIN_VALUE

        for (keyword in queries) {
            val body = "s=${enc(keyword)}&type=1&limit=20&offset=0"
            val root = JSONObject(
                http(
                    SEARCH,
                    "POST",
                    body,
                    "https://music.163.com/",
                    mapOf("Origin" to "https://music.163.com")
                )
            )
            val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: continue
            for (i in 0 until songs.length()) {
                val s = songs.optJSONObject(i) ?: continue
                val artist = s.optJSONArray("artists")
                    ?.optJSONObject(0)
                    ?.optString("name")
                    .orEmpty()
                val duration = s.optLong("duration", -1L)
                if (!plausibleMatch(query, s.optString("name"), artist, duration)) continue
                val score = matchScore(query, s.optString("name"), artist, duration)
                if (score > bestScore) {
                    bestScore = score
                    bestId = s.optLong("id", -1L)
                }
            }
            if (bestScore >= 170) break
        }

        return if (bestScore >= 100) bestId else -1L
    }

    private fun eapiBody(id: Long): String {
        val payload = """{"id":$id,"cp":false,"tv":0,"lv":0,"rv":0,"kv":0,"yv":0,"ytv":0,"yrv":0}"""
        val md5 = hex(
            MessageDigest.getInstance("MD5").digest(
                ("nobody" + EAPI_PATH + "use" + payload + "md5forencrypt")
                    .toByteArray(Charsets.UTF_8)
            )
        ).lowercase(Locale.ROOT)
        val plain = EAPI_PATH + SEP + payload + SEP + md5
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"))
        return "params=" + hex(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private fun yrcToLrc(yrc: String): String {
        val line = Regex("""^\[(\d+),(\d+)](.*)$""")
        val word = Regex("""\(\d+,\d+,\d+\)""")
        return yrc.lineSequence().mapNotNull { raw ->
            val m = line.matchEntire(raw.trim()) ?: return@mapNotNull null
            val ms = m.groupValues[1].toLong()
            val text = m.groupValues[3].replace(word, "").trim()
            if (text.isBlank()) null
            else "[%02d:%02d.%03d]%s".format(
                Locale.ROOT,
                ms / 60_000L,
                ms / 1_000L % 60L,
                ms % 1_000L,
                text
            )
        }.joinToString("\n")
    }

    private fun standaloneSongId(value: String): Long {
        val m = Regex("""(?<![A-Za-z0-9])(\d{4,})(?![A-Za-z0-9])""").find(value.trim())
            ?: return -1L
        return m.groupValues[1].toLongOrNull() ?: -1L
    }
}

object KugouLyricProvider : LyricProvider {
    override val name = "酷狗"

    override fun load(context: Context, query: TrackQuery): LyricDocument? {
        val keyword = listOf(query.title, query.artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val root = JSONObject(
            http(
                "https://songsearch.kugou.com/song_search_v2?keyword=${enc(keyword)}&page=1&pagesize=20&userid=-1&platform=WebFilter",
                "GET",
                referer = "https://www.kugou.com/"
            )
        )
        val list = root.optJSONObject("data")?.optJSONArray("lists") ?: return null

        var hash = ""
        var bestScore = Int.MIN_VALUE
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val duration = s.optLong("Duration", 0L).let {
                if (it in 1..20_000) it * 1000L else it
            }
            if (!plausibleMatch(query, s.optString("SongName"), s.optString("SingerName"), duration)) {
                continue
            }
            val score = matchScore(
                query,
                s.optString("SongName"),
                s.optString("SingerName"),
                duration
            )
            if (score > bestScore) {
                bestScore = score
                hash = s.optString("FileHash")
            }
        }
        if (bestScore < 100 || hash.isBlank()) return null

        val search = JSONObject(
            http(
                "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=${enc(hash)}",
                "GET",
                referer = "https://www.kugou.com/"
            )
        )
        val candidate = search.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val id = candidate.optString("id")
        val access = candidate.optString("accesskey")
        if (id.isBlank() || access.isBlank()) return null

        val base = "https://lyrics.kugou.com/download?ver=1&client=pc&id=${enc(id)}&accesskey=${enc(access)}"

        try {
            val krcRoot = JSONObject(
                http(
                    "$base&fmt=krc&charset=utf8",
                    "GET",
                    referer = "https://www.kugou.com/"
                )
            )
            val encoded = krcRoot.optString("content")
            if (encoded.isNotBlank()) {
                val decrypted = KrcCodec.decrypt(Base64.decode(encoded, Base64.DEFAULT))
                val lines = LyricParsers.parseKrc(decrypted)
                if (lines.isNotEmpty()) {
                    return LyricDocument(lines, source = "酷狗 · KRC")
                }
            }
        } catch (_: Throwable) {
            // Fall back to LRC below.
        }

        val lrcRoot = JSONObject(
            http(
                "$base&fmt=lrc&charset=utf8",
                "GET",
                referer = "https://www.kugou.com/"
            )
        )
        val encoded = lrcRoot.optString("content")
        if (encoded.isBlank()) return null
        val lyric = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            .replace("\uFEFF", "")
        val doc = LyricParsers.parse(lyric).copy(source = "酷狗 · LRC")
        return doc.takeIf { it.lines.isNotEmpty() }
    }
}

private fun plausibleMatch(
    q: TrackQuery,
    title: String,
    artist: String,
    duration: Long
): Boolean {
    val wt = norm(q.title)
    val wa = norm(q.artist)
    val ct = norm(title)
    val ca = norm(artist)
    if (wt.isBlank() || ct.isBlank()) return false

    if (
        wa.isNotBlank() &&
        ca.isNotBlank() &&
        !wa.contains(ca) &&
        !ca.contains(wa)
    ) return false

    val durationDiff = if (q.durationMs > 0L && duration > 0L) {
        kotlin.math.abs(q.durationMs - duration)
    } else -1L
    if (durationDiff > 15_000L) return false

    if (wt == ct) return true
    return wa.isNotBlank() &&
        ca.isNotBlank() &&
        durationDiff in 0..2_000L &&
        (wt.contains(ct) || ct.contains(wt)) &&
        hasVersionNoise(q.title) == hasVersionNoise(title)
}

private fun matchScore(
    q: TrackQuery,
    title: String,
    artist: String,
    duration: Long
): Int {
    val wt = norm(q.title)
    val wa = norm(q.artist)
    val ct = norm(title)
    val ca = norm(artist)
    if (wt.isBlank() || ct.isBlank()) return Int.MIN_VALUE

    var score = when {
        wt == ct -> 100
        wt.contains(ct) || ct.contains(wt) -> 55
        else -> -80
    }

    if (wa.isNotBlank() && ca.isNotBlank()) {
        score += when {
            wa == ca -> 70
            wa.contains(ca) || ca.contains(wa) -> 45
            else -> -45
        }
    }

    if (q.durationMs > 0L && duration > 0L) {
        val diff = kotlin.math.abs(q.durationMs - duration)
        score += when {
            diff <= 2_000L -> 35
            diff <= 5_000L -> 25
            diff <= 15_000L -> 5
            else -> -20
        }
    }

    if (!hasVersionNoise(q.title) && hasVersionNoise(title)) score -= 60
    return score
}

private fun norm(v: String) = v.lowercase(Locale.ROOT)
    .replace(Regex("""[\p{P}\s]+"""), "")

private fun hasVersionNoise(v: String): Boolean =
    v.lowercase(Locale.ROOT).matches(
        Regex(""".*(伴奏|翻唱|现场|升调|降调|live|remix|dj|sped|slowed|ktv).*""")
    )

private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (b in bytes) append("%02X".format(Locale.ROOT, b.toInt() and 0xFF))
}

private fun decodeEntities(value: String): String =
    Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
        .replace("&#58;", ":")
        .replace("&#46;", ".")

private fun decodeText(bytes: ByteArray): String {
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
    }
    val utf8 = bytes.toString(Charsets.UTF_8)
    return if ('\uFFFD' !in utf8) utf8 else bytes.toString(Charset.forName("GB18030"))
}

private fun http(
    address: String,
    method: String,
    body: String? = null,
    referer: String? = null,
    headers: Map<String, String> = emptyMap()
): String {
    var last: Throwable? = null
    repeat(2) { attempt ->
        try {
            val c = URL(address).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 7_000
                c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 PSA-Lyric/0.1")
                c.setRequestProperty("Accept", "application/json,text/plain,*/*")
                if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
                for ((key, value) in headers) c.setRequestProperty(key, value)

                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    c.doOutput = true
                    c.setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                    c.setFixedLengthStreamingMode(bytes.size)
                    c.outputStream.use { it.write(bytes) }
                }

                val code = c.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                c.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            if (out.size() + count > 12 * 1024 * 1024) {
                                throw IllegalStateException("lyric response too large")
                            }
                            out.write(buffer, 0, count)
                        }
                    }
                    return out.toString(Charsets.UTF_8.name())
                }
            } finally {
                c.disconnect()
            }
        } catch (error: Throwable) {
            last = error
            if (attempt == 0) Thread.sleep(250L)
        }
    }
    throw last ?: IllegalStateException("HTTP failed")
}
