package com.novemberraining.psalyric

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object LyricsEngine {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val cache = LinkedHashMap<String, LyricDocument>()
    @Volatile private var started = false
    private var lastKey = ""

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        worker.scheduleWithFixedDelay({ tick(app) }, 0, 700, TimeUnit.MILLISECONDS)
    }

    private fun tick(context: Context) {
        val s = PlaybackStore.snapshot
        if (!s.active) return
        val key = "${s.packageName}|${s.mediaId}|${s.title}|${s.artist}|${s.durationMs}"
        if (key == lastKey && LyricStore.document.lines.isNotEmpty()) return
        lastKey = key
        cache[key]?.let {
            LyricStore.document = it
            return
        }
        val q = TrackQuery(s.mediaId, s.title, s.artist, s.durationMs)
        val providers = listOf(LocalLyricProvider, NetEaseLyricProvider, QQLyricProvider, KugouLyricProvider)
        for (provider in providers) {
            val doc = try { provider.load(context, q) } catch (_: Throwable) { null }
            if (doc != null && doc.lines.isNotEmpty()) {
                cache[key] = doc
                while (cache.size > 64) cache.remove(cache.keys.first())
                LyricStore.document = doc
                return
            }
        }
        LyricStore.document = LyricDocument.EMPTY
    }
}
