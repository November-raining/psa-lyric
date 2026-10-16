package com.novemberraining.psalyric

import android.content.Context
import android.text.Html
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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

class LyricLookupException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

private data class HttpPolicy(
    val connectMs: Int,
    val readMs: Int,
    val attempts: Int
)

private val WORD_HTTP = HttpPolicy(connectMs = 3_000, readMs = 4_000, attempts = 1)
private val FALLBACK_HTTP = HttpPolicy(connectMs = 4_000, readMs = 5_500, attempts = 1)

object LocalLyricProvider : LyricProvider {
    override val name = "本地"

    override fun load(context: Context, query: TrackQuery): LyricDocument? =
        LyricDiskCache.load(context, query)
}

object QQLyricProvider : LyricProvider {
    override val name = "QQ"
    private const val MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val REFERER = "https://y.qq.com/"

    private data class Candidate(
        val songMid: String,
        val songId: Long,
        val title: String,
        val artist: String,
        val durationMs: Long
    )

    private data class LegacyAux(
        val original: String,
        val translation: String
    )

    override fun load(context: Context, query: TrackQuery): LyricDocument? {
        val candidate = try {
            search(query)
        } catch (error: Throwable) {
            throw LyricLookupException("search:${shortError(error)}", error)
        } ?: return null

        var wordError: Throwable? = null
        try {
            loadMusicuWord(context, candidate)?.let { return it }
        } catch (error: Throwable) {
            wordError = error
        }

        try {
            loadLegacyLrc(candidate)?.let { return it }
        } catch (error: Throwable) {
            val word = wordError?.let(::shortError).orEmpty()
            throw LyricLookupException(
                "musicu=${word.ifBlank { "MISS" }},legacy=${shortError(error)}",
                error
            )
        }

        if (wordError != null) {
            throw LyricLookupException("musicu:${shortError(wordError)}")
        }
        return null
    }

    private fun loadMusicuWord(
        context: Context,
        candidate: Candidate
    ): LyricDocument? {
        val param = JSONObject()
            .put("songMID", candidate.songMid)
            .put("songID", 0)
            .put("crypt", 1)
            .put("qrc", 1)
            .put("roma", 1)
            .put("trans", 1)
            .put("lrc_t", 0)
            .put("qrc_t", 0)
            .put("roma_t", 0)
            .put("trans_t", 0)
            .put("interval", 0)
            .put("type", 0)
            .put("format", "json")
            .put("ct", 19)
            .put("cv", 1859)

        val body = JSONObject()
            .put("comm", JSONObject().put("ct", 19).put("cv", 1859).put("uin", "0"))
            .put(
                "req",
                JSONObject()
                    .put("module", "music.musichallSong.PlayLyricInfo")
                    .put("method", "GetPlayLyricInfo")
                    .put("param", param)
            )
            .toString()

        val root = JSONObject(
            http(
                MUSICU,
                "POST",
                body = body,
                referer = REFERER,
                contentType = "application/json; charset=UTF-8",
                policy = WORD_HTTP
            )
        )
        val req = root.optJSONObject("req")
        val code = req?.optInt("code", 0) ?: 0
        if (code != 0) throw LyricLookupException("musicu-code=$code")
        val data = req?.optJSONObject("data")
            ?: throw LyricLookupException("musicu-no-data")

        val payloads = linkedSetOf<String>()
        data.optString("lyric").takeIf { it.isNotBlank() }?.let(payloads::add)
        data.optString("qrc").takeIf { it.isNotBlank() }?.let(payloads::add)

        var enhanced = ""
        var decodeFailure: Throwable? = null
        for (payload in payloads) {
            try {
                enhanced = decodeQqWordPayload(payload)
                if (enhanced.isNotBlank()) break
            } catch (error: Throwable) {
                decodeFailure = error
            }
        }
        if (enhanced.isBlank()) {
            if (decodeFailure != null) {
                throw LyricLookupException(
                    "qrc-decode:${shortError(decodeFailure)}",
                    decodeFailure
                )
            }
            return null
        }

        val wantTranslation =
            Prefs.secondaryLyricEnabled(context) &&
                Prefs.secondaryLyricMode(context) == LyricSecondaryRules.MODE_TRANSLATION

        var original = ""
        var translated = decodeQqAux(data.optString("trans"))
        val romaji = decodeQqAux(data.optString("roma"))

        var doc = LyricParsers.parse(
            lrc = original,
            translationLrc = translated,
            yrc = enhanced,
            romajiLrc = romaji
        )

        // A successful QRC response does not guarantee a useful translation track.
        // Treat sparse rows as incomplete as well: a 5/34 cache is visibly intermittent.
        // Query the legacy translation track and union any additional rows onto the same
        // QRC timeline. This preserves word timing while upgrading secondary coverage.
        if (
            wantTranslation &&
            !LyricSecondaryRules.hasAdequateData(
                doc,
                LyricSecondaryRules.MODE_TRANSLATION
            )
        ) {
            try {
                val aux = loadLegacyAux(candidate, WORD_HTTP)
                original = aux.original
                translated = aux.translation
                val legacyEnriched = LyricParsers.parse(
                    lrc = original,
                    translationLrc = translated,
                    yrc = enhanced,
                    romajiLrc = romaji
                )
                doc = mergeSecondaryRows(doc, legacyEnriched)
            } catch (_: Throwable) {
                // QRC stays usable even when optional translation enrichment fails.
            }
        }

        if (!doc.hasWordTimings) return null
        return doc.copy(source = "QQ · QRC", resolvedArtist = candidate.artist)
    }

    private fun mergeSecondaryRows(
        primary: LyricDocument,
        enrichment: LyricDocument
    ): LyricDocument {
        if (primary.lines.size != enrichment.lines.size) {
            return if (
                enrichment.translationLineCount > primary.translationLineCount ||
                enrichment.romajiLineCount > primary.romajiLineCount
            ) enrichment else primary
        }

        val merged = primary.lines.mapIndexed { index, line ->
            val extra = enrichment.lines[index]
            line.copy(
                translation = line.translation.ifBlank { extra.translation },
                romaji = line.romaji.ifBlank { extra.romaji }
            )
        }
        return primary.copy(lines = merged)
    }

    private fun loadLegacyLrc(candidate: Candidate): LyricDocument? {
        val aux = loadLegacyAux(candidate, FALLBACK_HTTP)
        val doc = LyricParsers.parse(aux.original, aux.translation)
            .copy(source = "QQ · LRC", resolvedArtist = candidate.artist)
        return doc.takeIf { it.lines.isNotEmpty() }
    }

    private fun loadLegacyAux(
        candidate: Candidate,
        policy: HttpPolicy
    ): LegacyAux {
        val response = http(
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                "?songmid=${enc(candidate.songMid)}&songtype=0&format=json&nobase64=1" +
                "&g_tk=5381&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0",
            "GET",
            referer = "https://y.qq.com/portal/player.html",
            policy = policy
        )
        val root = JSONObject(response)
        return LegacyAux(
            original = decodeEntities(root.optString("lyric", "")),
            translation = decodeEntities(
                root.optString("trans", root.optString("tlyric", ""))
            )
        )
    }

    private fun decodeQqWordPayload(raw: String): String {
        if (raw.isBlank()) return ""
        try {
            val timed = QrcLyricCodec.decryptToTimedLyric(raw)
            if (timed.isNotBlank()) return timed
        } catch (_: Throwable) {
        }
        val plain = decodeMaybeBase64(raw)
        val converted = QrcLyricCodec.toEnhancedTimeline(plain)
        if (converted.isNotBlank()) return converted
        return if (LyricParsers.parseYrc(plain).any { it.words.isNotEmpty() }) plain else ""
    }

    private fun decodeQqAux(raw: String): String {
        if (raw.isBlank()) return ""
        try {
            val decoded = QrcLyricCodec.decryptToLrc(raw)
            if (decoded.isNotBlank()) return decoded
        } catch (_: Throwable) {
        }
        val plain = decodeMaybeBase64(raw)
        return if (plain.contains('[')) decodeEntities(plain) else ""
    }

    private fun search(query: TrackQuery): Candidate? {
        val terms = buildList {
            add(listOf(query.title, query.artist).filter { it.isNotBlank() }.joinToString(" "))
            if (query.artist.isNotBlank()) add(query.title)
        }.filter { it.isNotBlank() }.distinct()

        var best: Candidate? = null
        var bestScore = Int.MIN_VALUE
        var lastError: Throwable? = null

        for (term in terms) {
            val candidates = try {
                searchSoso(term)
            } catch (error: Throwable) {
                lastError = error
                try {
                    searchSmartBox(term)
                } catch (fallbackError: Throwable) {
                    lastError = fallbackError
                    emptyList()
                }
            }

            for (candidate in candidates) {
                if (
                    !plausibleMatch(
                        query,
                        candidate.title,
                        candidate.artist,
                        candidate.durationMs
                    )
                ) continue
                val score = matchScore(
                    query,
                    candidate.title,
                    candidate.artist,
                    candidate.durationMs
                )
                if (score > bestScore) {
                    bestScore = score
                    best = candidate
                }
            }
            if (bestScore >= 170) break
        }

        if (best != null && bestScore >= 100) return best
        if (lastError != null && best == null) {
            throw LyricLookupException(shortError(lastError), lastError)
        }
        return null
    }

    private fun searchSoso(term: String): List<Candidate> {
        val root = JSONObject(
            http(
                "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp" +
                    "?g_tk=5381&format=json&inCharset=utf-8&outCharset=utf-8" +
                    "&notice=0&platform=yqq.json&needNewCode=0&w=${enc(term)}" +
                    "&p=1&n=20&t=0&aggr=1&cr=1&lossless=0&flag_qc=0",
                "GET",
                referer = "https://y.qq.com/portal/player.html",
                policy = WORD_HTTP
            )
        )
        val list = root.optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list") ?: return emptyList()
        return parseQqSongList(list)
    }

    private fun searchSmartBox(term: String): List<Candidate> {
        val root = JSONObject(
            http(
                "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg" +
                    "?key=${enc(term)}&format=json&g_tk=5381&utf8=1&loginUin=0" +
                    "&hostUin=0&inCharset=utf8&outCharset=utf-8&platform=yqq",
                "GET",
                referer = "https://y.qq.com/portal/player.html",
                policy = WORD_HTTP
            )
        )
        val items = root.optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("itemlist") ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val mid = item.optString("mid")
            if (mid.isBlank()) continue
            out += Candidate(
                songMid = mid,
                songId = -1L,
                title = item.optString("name"),
                artist = item.optString("singer"),
                durationMs = -1L
            )
        }
        return out
    }

    private fun parseQqSongList(list: JSONArray): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (i in 0 until list.length()) {
            val song = list.optJSONObject(i) ?: continue
            val mid = song.optString("songmid")
            if (mid.isBlank()) continue
            val singers = song.optJSONArray("singer")
            val artist = buildString {
                if (singers != null) {
                    for (j in 0 until singers.length()) {
                        val name = singers.optJSONObject(j)?.optString("name").orEmpty()
                        if (name.isBlank()) continue
                        if (isNotEmpty()) append('/')
                        append(name)
                    }
                }
            }
            out += Candidate(
                songMid = mid,
                songId = song.optLong("songid", -1L),
                title = song.optString("songname"),
                artist = artist,
                durationMs = song.optLong("interval", -1L).let {
                    if (it > 0L) it * 1000L else -1L
                }
            )
        }
        return out
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

        var directError: Throwable? = null
        if (direct > 0L) {
            try {
                loadById(direct)?.let { return it }
            } catch (error: Throwable) {
                directError = error
            }
        }

        return try {
            val id = search(query)
            if (id <= 0L || id == direct) {
                if (directError != null) throw directError
                null
            } else {
                loadById(id)
            }
        } catch (error: Throwable) {
            throw LyricLookupException("netease:${shortError(error)}", error)
        }
    }

    private fun loadById(id: Long): LyricDocument? {
        val root = JSONObject(
            http(
                LYRIC,
                "POST",
                eapiBody(id),
                "https://music.163.com/",
                mapOf("Origin" to "https://music.163.com"),
                contentType = "application/x-www-form-urlencoded; charset=UTF-8",
                policy = WORD_HTTP
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
        val terms = buildList {
            add(listOf(query.title, query.artist).filter { it.isNotBlank() }.joinToString(" "))
            if (query.artist.isNotBlank()) add(query.title)
        }.filter { it.isNotBlank() }.distinct()

        var bestId = -1L
        var bestScore = Int.MIN_VALUE

        for (term in terms) {
            val body = "s=${enc(term)}&type=1&limit=20&offset=0"
            val root = JSONObject(
                http(
                    SEARCH,
                    "POST",
                    body,
                    "https://music.163.com/",
                    mapOf("Origin" to "https://music.163.com"),
                    contentType = "application/x-www-form-urlencoded; charset=UTF-8",
                    policy = WORD_HTTP
                )
            )
            val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: continue
            for (i in 0 until songs.length()) {
                val song = songs.optJSONObject(i) ?: continue
                val artist = song.optJSONArray("artists")
                    ?.optJSONObject(0)
                    ?.optString("name")
                    .orEmpty()
                val duration = song.optLong("duration", -1L)
                if (!plausibleMatch(query, song.optString("name"), artist, duration)) continue
                val score = matchScore(query, song.optString("name"), artist, duration)
                if (score > bestScore) {
                    bestScore = score
                    bestId = song.optLong("id", -1L)
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
            val match = line.matchEntire(raw.trim()) ?: return@mapNotNull null
            val ms = match.groupValues[1].toLong()
            val text = match.groupValues[3].replace(word, "").trim()
            if (text.isBlank()) null else "[%02d:%02d.%03d]%s".format(
                Locale.ROOT,
                ms / 60_000L,
                ms / 1_000L % 60L,
                ms % 1_000L,
                text
            )
        }.joinToString("\n")
    }

    private fun standaloneSongId(value: String): Long {
        val match = Regex("""(?<![A-Za-z0-9])(\d{4,})(?![A-Za-z0-9])""")
            .find(value.trim()) ?: return -1L
        return match.groupValues[1].toLongOrNull() ?: -1L
    }
}

object KugouLyricProvider : LyricProvider {
    override val name = "酷狗"
    private const val REFERER = "https://www.kugou.com/"

    private data class SongCandidate(
        val hash: String,
        val albumAudioId: String,
        val title: String,
        val artist: String,
        val durationMs: Long
    )

    private data class LyricCandidate(
        val id: String,
        val accessKey: String,
        val durationMs: Long
    )

    override fun load(context: Context, query: TrackQuery): LyricDocument? {
        val song = try {
            searchSong(query)
        } catch (error: Throwable) {
            throw LyricLookupException("song-search:${shortError(error)}", error)
        } ?: return null

        var krcError: Throwable? = null
        try {
            val candidates = searchLyricCandidates(song, query)
            for (candidate in candidates.take(5)) {
                try {
                    val krc = downloadKrc(candidate)
                    val lines = LyricParsers.parseKrc(krc)
                    if (lines.isNotEmpty()) {
                        return LyricDocument(lines, source = "酷狗 · KRC", resolvedArtist = song.artist)
                    }
                } catch (error: Throwable) {
                    krcError = error
                }
            }
        } catch (error: Throwable) {
            krcError = error
        }

        // Last resort for this provider: line-timed lyric. It is still useful if all
        // enhanced providers miss, but never outranks another provider's word timeline.
        try {
            val candidates = searchLyricCandidates(song, query)
            for (candidate in candidates.take(3)) {
                val lrc = downloadLrc(candidate)
                val doc = LyricParsers.parse(lrc).copy(source = "酷狗 · LRC", resolvedArtist = song.artist)
                if (doc.lines.isNotEmpty()) return doc
            }
        } catch (error: Throwable) {
            val prefix = krcError?.let(::shortError).orEmpty()
            throw LyricLookupException(
                "krc=${prefix.ifBlank { "MISS" }},lrc=${shortError(error)}",
                error
            )
        }

        if (krcError != null) {
            throw LyricLookupException("krc:${shortError(krcError)}", krcError)
        }
        return null
    }

    private fun searchSong(query: TrackQuery): SongCandidate? {
        val keyword = listOf(query.title, query.artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val root = JSONObject(
            http(
                "https://songsearch.kugou.com/song_search_v2" +
                    "?keyword=${enc(keyword)}&page=1&pagesize=20&userid=-1&platform=WebFilter",
                "GET",
                referer = REFERER,
                policy = WORD_HTTP
            )
        )
        val list = root.optJSONObject("data")?.optJSONArray("lists") ?: return null

        var best: SongCandidate? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until list.length()) {
            val song = list.optJSONObject(i) ?: continue
            val duration = normalizeKugouDuration(song.optLong("Duration", 0L))
            val title = stripEm(song.optString("SongName"))
            val artist = stripEm(song.optString("SingerName"))
            if (!plausibleMatch(query, title, artist, duration)) continue
            val score = matchScore(query, title, artist, duration)
            if (score > bestScore) {
                bestScore = score
                best = SongCandidate(
                    hash = song.optString("FileHash"),
                    albumAudioId = sequenceOf(
                        song.optString("ID"),
                        song.optJSONObject("AudioInfo")?.optString("ID").orEmpty(),
                        song.optString("SongID")
                    ).firstOrNull { it.isNotBlank() }.orEmpty(),
                    title = title,
                    artist = artist,
                    durationMs = duration
                )
            }
        }
        return best?.takeIf { bestScore >= 100 && it.hash.isNotBlank() }
    }

    private fun searchLyricCandidates(
        song: SongCandidate,
        query: TrackQuery
    ): List<LyricCandidate> {
        val keyword = listOf(query.artist, query.title)
            .filter { it.isNotBlank() }
            .joinToString(" - ")

        val signedParams = linkedMapOf(
            "album_audio_id" to song.albumAudioId.ifBlank { "0" },
            "duration" to song.durationMs.coerceAtLeast(0L).toString(),
            "hash" to song.hash.lowercase(Locale.ROOT),
            "keyword" to keyword,
            "lrctxt" to "1",
            "man" to "yes"
        )

        try {
            val root = kgSignedGet(
                "https://lyrics.kugou.com/v1/search",
                signedParams,
                WORD_HTTP
            )
            val found = parseKugouLyricCandidates(root.optJSONArray("candidates"), song.durationMs)
            if (found.isNotEmpty()) return found
        } catch (_: Throwable) {
        }

        // Older mobile endpoint is a useful independent fallback when the signed v1 route
        // is filtered by a particular network.
        val first = try {
            JSONObject(
                http(
                    "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi" +
                        "&keyword=${enc(keyword)}&hash=${enc(song.hash)}" +
                        "&timelength=${song.durationMs}",
                    "GET",
                    policy = WORD_HTTP
                )
            )
        } catch (_: Throwable) {
            null
        }
        parseKugouLyricCandidates(first?.optJSONArray("candidates"), song.durationMs)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        val second = JSONObject(
            http(
                "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi" +
                    "&keyword=${enc(keyword)}",
                "GET",
                policy = WORD_HTTP
            )
        )
        return parseKugouLyricCandidates(second.optJSONArray("candidates"), song.durationMs)
    }

    private fun downloadKrc(candidate: LyricCandidate): String {
        val params = linkedMapOf(
            "accesskey" to candidate.accessKey,
            "charset" to "utf8",
            "client" to "mobi",
            "fmt" to "krc",
            "id" to candidate.id,
            "ver" to "1"
        )

        val encoded = try {
            kgSignedGet(
                "https://lyrics.kugou.com/download",
                params,
                WORD_HTTP
            ).optString("content")
        } catch (_: Throwable) {
            val root = JSONObject(
                http(
                    "https://krcs.kugou.com/download?ver=1&client=mobi" +
                        "&id=${enc(candidate.id)}&accesskey=${enc(candidate.accessKey)}" +
                        "&fmt=krc&charset=utf8",
                    "GET",
                    policy = WORD_HTTP
                )
            )
            root.optString("content")
        }
        if (encoded.isBlank()) return ""
        return KrcCodec.decrypt(Base64.decode(encoded, Base64.DEFAULT))
    }

    private fun downloadLrc(candidate: LyricCandidate): String {
        val root = JSONObject(
            http(
                "https://lyrics.kugou.com/download?ver=1&client=pc" +
                    "&id=${enc(candidate.id)}&accesskey=${enc(candidate.accessKey)}" +
                    "&fmt=lrc&charset=utf8",
                "GET",
                referer = REFERER,
                policy = FALLBACK_HTTP
            )
        )
        val encoded = root.optString("content")
        if (encoded.isBlank()) return ""
        return String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            .replace("\uFEFF", "")
    }

    private fun parseKugouLyricCandidates(
        array: JSONArray?,
        wantedDurationMs: Long
    ): List<LyricCandidate> {
        if (array == null) return emptyList()
        val all = ArrayList<LyricCandidate>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val access = item.optString("accesskey")
            if (id.isBlank() || access.isBlank()) continue
            all += LyricCandidate(
                id = id,
                accessKey = access,
                durationMs = normalizeKugouDuration(item.optLong("duration", -1L))
            )
        }
        if (wantedDurationMs <= 0L) return all
        val close = all.filter {
            it.durationMs <= 0L ||
                kotlin.math.abs(it.durationMs - wantedDurationMs) <= 8_000L
        }
        return if (close.isNotEmpty()) close else all
    }

    private fun kgSignedGet(
        address: String,
        params: Map<String, String>,
        policy: HttpPolicy
    ): JSONObject {
        val all = LinkedHashMap<String, String>()
        all.putAll(params)
        all["appid"] = "3116"
        all["clientver"] = "11440"

        val source = all.toSortedMap()
            .entries
            .joinToString(separator = "") { "${it.key}=${it.value}" }
        val salt = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
        val signature = md5Hex(salt + source + salt)
        all["signature"] = signature

        val query = all.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }
        val now = System.currentTimeMillis()
        val headers = mapOf(
            "User-Agent" to "Android9-1070-11440-201-0-Lyric-wifi",
            "Connection" to "Keep-Alive",
            "KG-Rec" to "1",
            "KG-RC" to "1",
            "KG-CLIENTTIMEMS" to now.toString(),
            "mid" to md5Hex(now.toString())
        )
        return JSONObject(
            http(
                "$address?$query",
                "GET",
                referer = REFERER,
                headers = headers,
                policy = policy
            )
        )
    }

    private fun normalizeKugouDuration(value: Long): Long =
        if (value in 1..20_000L) value * 1000L else value

    private fun stripEm(value: String): String =
        value.replace("<em>", "").replace("</em>", "").trim()
}

private fun plausibleMatch(
    query: TrackQuery,
    title: String,
    artist: String,
    durationMs: Long
): Boolean {
    val wantedTitle = norm(query.title)
    val wantedArtist = norm(query.artist)
    val foundTitle = norm(title)
    val foundArtist = norm(artist)
    if (wantedTitle.isBlank() || foundTitle.isBlank()) return false

    if (
        wantedArtist.isNotBlank() &&
        foundArtist.isNotBlank() &&
        !artistCompatible(wantedArtist, foundArtist)
    ) return false

    val durationDifference = if (query.durationMs > 0L && durationMs > 0L) {
        kotlin.math.abs(query.durationMs - durationMs)
    } else -1L
    if (durationDifference > 15_000L) return false

    if (wantedTitle == foundTitle) return true
    return wantedArtist.isNotBlank() &&
        foundArtist.isNotBlank() &&
        durationDifference in 0..2_000L &&
        (wantedTitle.contains(foundTitle) || foundTitle.contains(wantedTitle)) &&
        hasVersionNoise(query.title) == hasVersionNoise(title)
}

private fun matchScore(
    query: TrackQuery,
    title: String,
    artist: String,
    durationMs: Long
): Int {
    val wantedTitle = norm(query.title)
    val wantedArtist = norm(query.artist)
    val foundTitle = norm(title)
    val foundArtist = norm(artist)
    if (wantedTitle.isBlank() || foundTitle.isBlank()) return Int.MIN_VALUE

    var score = when {
        wantedTitle == foundTitle -> 100
        wantedTitle.contains(foundTitle) || foundTitle.contains(wantedTitle) -> 55
        else -> -80
    }

    if (wantedArtist.isNotBlank() && foundArtist.isNotBlank()) {
        score += when {
            wantedArtist == foundArtist -> 70
            artistCompatible(wantedArtist, foundArtist) -> 45
            else -> -45
        }
    }

    if (query.durationMs > 0L && durationMs > 0L) {
        val diff = kotlin.math.abs(query.durationMs - durationMs)
        score += when {
            diff <= 2_000L -> 35
            diff <= 5_000L -> 25
            diff <= 15_000L -> 5
            else -> -20
        }
    }

    if (!hasVersionNoise(query.title) && hasVersionNoise(title)) score -= 60
    return score
}

private fun artistCompatible(left: String, right: String): Boolean {
    if (left.contains(right) || right.contains(left)) return true
    val leftParts = left.split('/', '&', '、', ',').filter { it.isNotBlank() }
    val rightParts = right.split('/', '&', '、', ',').filter { it.isNotBlank() }
    return leftParts.any { l -> rightParts.any { r -> l == r || l.contains(r) || r.contains(l) } }
}

private fun norm(value: String) = value.lowercase(Locale.ROOT)
    .replace(Regex("""[\p{P}\s·・]+"""), "")

private fun hasVersionNoise(value: String): Boolean =
    value.lowercase(Locale.ROOT).matches(
        Regex(""".*(伴奏|翻唱|现场|升调|降调|live|remix|dj|sped|slowed|ktv).*""")
    )

private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (b in bytes) append("%02X".format(Locale.ROOT, b.toInt() and 0xFF))
}

private fun md5Hex(value: String): String =
    MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

private fun decodeEntities(value: String): String =
    Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
        .replace("&#58;", ":")
        .replace("&#46;", ".")

private fun decodeMaybeBase64(value: String): String {
    val raw = decodeEntities(value.trim())
    if (raw.isBlank() || raw.startsWith("[")) return raw
    val compact = raw.replace(Regex("""\s+"""), "")
    if (
        compact.length < 8 ||
        compact.length % 4 != 0 ||
        !compact.matches(Regex("""[A-Za-z0-9+/]+={0,2}"""))
    ) return raw
    return try {
        val decoded = String(Base64.decode(compact, Base64.DEFAULT), Charsets.UTF_8)
            .replace("\uFEFF", "")
        if (decoded.contains('[')) decodeEntities(decoded).trim() else raw
    } catch (_: Throwable) {
        raw
    }
}

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

private fun shortError(error: Throwable): String {
    val message = error.message
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.take(80)
        ?.trim()
        .orEmpty()
    return if (message.isBlank()) error.javaClass.simpleName
    else "${error.javaClass.simpleName}:$message"
}

private fun http(
    address: String,
    method: String,
    body: String? = null,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
    contentType: String? = null,
    policy: HttpPolicy = FALLBACK_HTTP
): String {
    var last: Throwable? = null
    repeat(policy.attempts.coerceAtLeast(1)) { attempt ->
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("lyric request cancelled")
        }
        try {
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = policy.connectMs
                connection.readTimeout = policy.readMs
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 PSA-Lyric/0.1")
                connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
                if (!referer.isNullOrBlank()) connection.setRequestProperty("Referer", referer)
                for ((key, value) in headers) connection.setRequestProperty(key, value)

                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty(
                        "Content-Type",
                        contentType ?: "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val text = stream?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("lyric request cancelled")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            if (out.size() + count > 12 * 1024 * 1024) {
                                throw IllegalStateException("lyric response too large")
                            }
                            out.write(buffer, 0, count)
                        }
                    }
                    out.toString(Charsets.UTF_8.name())
                }.orEmpty()

                if (code !in 200..299) {
                    throw IllegalStateException(
                        "HTTP $code ${URL(address).host}${text.take(60).replace('\n', ' ')}"
                    )
                }
                return text
            } finally {
                connection.disconnect()
            }
        } catch (error: Throwable) {
            last = error
            if (error is InterruptedException) throw error
            if (attempt + 1 < policy.attempts) Thread.sleep(180L)
        }
    }
    throw last ?: IllegalStateException("HTTP failed")
}
