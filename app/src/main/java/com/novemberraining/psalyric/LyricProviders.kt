package com.novemberraining.psalyric

import android.content.Context
import android.text.Html
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class TrackQuery(val mediaId: String, val title: String, val artist: String, val durationMs: Long)

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
        val candidates = listOf(
            "${safe(query.title)} - ${safe(query.artist)}.lrc",
            "${safe(query.title)}.lrc"
        )
        val file = candidates.asSequence().map { File(root, it) }.firstOrNull { it.isFile } ?: return null
        val bytes = file.readBytes()
        val text = decodeText(bytes)
        val doc = LyricParsers.parse(text)
        return if (doc.lines.isEmpty()) null else doc.copy(source = name)
    }

    private fun safe(v: String) = v.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
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
}

object NetEaseLyricProvider : LyricProvider {
    override val name = "网易云"
    private const val SEARCH = "https://music.163.com/api/search/get/web"
    private const val LYRIC = "https://interface3.music.163.com/eapi/song/lyric/v1"
    private const val EAPI_PATH = "/api/song/lyric/v1"
    private const val SEP = "-36cd479b6b5-"
    private val KEY = "e82ckenh8dichen8".toByteArray(Charsets.US_ASCII)

    override fun load(context: Context, query: TrackQuery): LyricDocument? {
        val id = search(query)
        if (id <= 0) return null
        val body = eapiBody(id)
        val root = JSONObject(http(LYRIC, "POST", body, "https://music.163.com/"))
        val lrc = root.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val t = root.optJSONObject("tlyric")?.optString("lyric").orEmpty()
        val y = root.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val r = root.optJSONObject("romalrc")?.optString("lyric").orEmpty()
        val yr = root.optJSONObject("yromalrc")?.optString("lyric").orEmpty()
        val romaji = if (yr.isNotBlank()) yrcToLrc(yr) else r
        val doc = LyricParsers.parse(lrc, t, y, romaji)
        return if (doc.lines.isEmpty()) null else doc.copy(source = name)
    }

    private fun search(q: TrackQuery): Long {
        val query = listOf(q.title, q.artist).filter { it.isNotBlank() }.joinToString(" ")
        val body = "s=${enc(query)}&type=1&limit=20&offset=0"
        val root = JSONObject(http(SEARCH, "POST", body, "https://music.163.com/"))
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return -1
        var best = -1L
        var bestScore = Int.MIN_VALUE
        for (i in 0 until songs.length()) {
            val s = songs.optJSONObject(i) ?: continue
            val title = s.optString("name")
            val artists = s.optJSONArray("artists")
            val artist = artists?.optJSONObject(0)?.optString("name").orEmpty()
            val duration = s.optLong("duration", -1)
            val score = matchScore(q, title, artist, duration)
            if (score > bestScore) {
                bestScore = score
                best = s.optLong("id", -1)
            }
        }
        return if (bestScore >= 100) best else -1
    }

    private fun eapiBody(id: Long): String {
        val payload = """{"id":$id,"cp":false,"tv":0,"lv":0,"rv":0,"kv":0,"yv":0,"ytv":0,"yrv":0}"""
        val md5 = hex(MessageDigest.getInstance("MD5").digest(
            ("nobody" + EAPI_PATH + "use" + payload + "md5forencrypt").toByteArray(Charsets.UTF_8)
        )).lowercase(Locale.ROOT)
        val plain = EAPI_PATH + SEP + payload + SEP + md5
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"))
        return "params=" + hex(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private fun yrcToLrc(yrc: String): String {
        val re = Regex("""^\[(\d+),(\d+)](.*)$""")
        val word = Regex("""\(\d+,\d+,\d+\)""")
        return yrc.lineSequence().mapNotNull { raw ->
            val m = re.matchEntire(raw.trim()) ?: return@mapNotNull null
            val ms = m.groupValues[1].toLong()
            val text = m.groupValues[3].replace(word, "").trim()
            if (text.isBlank()) null
            else "[%02d:%02d.%03d]%s".format(ms / 60000, ms / 1000 % 60, ms % 1000, text)
        }.joinToString("\n")
    }
}

object QQLyricProvider : LyricProvider {
    override val name = "QQ"
    override fun load(context: Context, query: TrackQuery): LyricDocument? {
        val q = enc(listOf(query.title, query.artist).filter { it.isNotBlank() }.joinToString(" "))
        val root = JSONObject(http("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$q&format=json&p=1&n=20", "GET"))
        val list = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return null
        var mid = ""
        var bestScore = Int.MIN_VALUE
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val singers = s.optJSONArray("singer")
            val artist = singers?.optJSONObject(0)?.optString("name").orEmpty()
            val duration = s.optLong("interval", 0L) * 1000L
            val score = matchScore(query, s.optString("songname"), artist, duration)
            if (score > bestScore) {
                bestScore = score
                mid = s.optString("songmid")
            }
        }
        if (bestScore < 100 || mid.isBlank()) return null
        val response = http(
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${enc(mid)}&format=json&nobase64=1",
            "GET", referer = "https://y.qq.com/"
        )
        val lyricRoot = JSONObject(response)
        var lyric = lyricRoot.optString("lyric", "")
        if (lyric.isBlank()) {
            val encoded = lyricRoot.optString("lyric", "")
            if (encoded.isNotBlank()) lyric = String(Base64.getDecoder().decode(encoded))
        }
        lyric = Html.fromHtml(lyric, Html.FROM_HTML_MODE_LEGACY).toString()
        val trans = Html.fromHtml(lyricRoot.optString("trans", ""), Html.FROM_HTML_MODE_LEGACY).toString()
        val doc = LyricParsers.parse(lyric, trans)
        return if (doc.lines.isEmpty()) null else doc.copy(source = name)
    }
}

object KugouLyricProvider : LyricProvider {
    override val name = "酷狗"
    override fun load(context: Context, query: TrackQuery): LyricDocument? {
        val keyword = enc(listOf(query.title, query.artist).filter { it.isNotBlank() }.joinToString(" "))
        val root = JSONObject(http("https://songsearch.kugou.com/song_search_v2?keyword=$keyword&page=1&pagesize=20", "GET"))
        val list = root.optJSONObject("data")?.optJSONArray("lists") ?: return null
        var hash = ""
        var bestScore = Int.MIN_VALUE
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val score = matchScore(query, s.optString("SongName"), s.optString("SingerName"), s.optLong("Duration", 0L))
            if (score > bestScore) {
                bestScore = score
                hash = s.optString("FileHash")
            }
        }
        if (bestScore < 100 || hash.isBlank()) return null
        val search = JSONObject(http(
            "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=${enc(hash)}&keyword=$keyword&duration=${query.durationMs}",
            "GET"
        ))
        val candidates = search.optJSONArray("candidates") ?: return null
        val c = candidates.optJSONObject(0) ?: return null
        val id = c.optLong("id", -1)
        val key = c.optString("accesskey")
        if (id <= 0 || key.isBlank()) return null
        val dl = JSONObject(http(
            "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=${enc(key)}&fmt=lrc&charset=utf8",
            "GET"
        ))
        val content = dl.optString("content")
        if (content.isBlank()) return null
        val lyric = try { String(Base64.getDecoder().decode(content), Charsets.UTF_8) } catch (_: Throwable) { content }
        val doc = LyricParsers.parse(lyric)
        return if (doc.lines.isEmpty()) null else doc.copy(source = name)
    }
}

private fun matchScore(q: TrackQuery, title: String, artist: String, duration: Long): Int {
    val wt = norm(q.title)
    val wa = norm(q.artist)
    val ct = norm(title)
    val ca = norm(artist)
    if (wt.isBlank() || ct.isBlank()) return Int.MIN_VALUE
    if (wa.isNotBlank() && ca.isNotBlank() && !wa.contains(ca) && !ca.contains(wa)) return Int.MIN_VALUE
    if (q.durationMs > 0 && duration > 0 && kotlin.math.abs(q.durationMs - duration) > 15_000L) return Int.MIN_VALUE
    var score = if (wt == ct) 120 else if (wt.contains(ct) || ct.contains(wt)) 90 else 0
    if (wa.isNotBlank() && ca.isNotBlank()) score += if (wa == ca) 35 else 20
    if (q.durationMs > 0 && duration > 0) score += (20 - (kotlin.math.abs(q.durationMs - duration) / 1000L).toInt()).coerceAtLeast(0)
    return score
}

private fun norm(v: String) = v.lowercase(Locale.ROOT)
    .replace(Regex("""[\s\p{Punct}·・]+"""), "")
    .replace(Regex("""(?i)(live|remaster(?:ed)?|伴奏|翻唱|现场版|纯音乐)"""), "")

private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (b in bytes) append("%02X".format(b.toInt() and 0xFF))
}

private fun http(url: String, method: String, body: String? = null, referer: String? = null): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = method
    c.connectTimeout = 4500
    c.readTimeout = 6500
    c.setRequestProperty("User-Agent", "Mozilla/5.0 PSA-Lyric/0.1")
    c.setRequestProperty("Accept", "application/json,text/plain,*/*")
    if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
    if (body != null) {
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }
    val code = c.responseCode
    val stream = if (code in 200..299) c.inputStream else c.errorStream
    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    c.disconnect()
    if (code !in 200..299) throw IllegalStateException("HTTP $code")
    return text
}
