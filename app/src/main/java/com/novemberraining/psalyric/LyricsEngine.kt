package com.novemberraining.psalyric

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class LyricDiagnostics(
    val trackKey: String = "",
    val state: String = "waiting",
    val attempts: String = "",
    val source: String = "",
    val wordTimed: Boolean = false,
    val lineCount: Int = 0,
    val wordCount: Int = 0
)

object LyricsEngine {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val cache = LinkedHashMap<String, LyricDocument>()
    @Volatile private var started = false
    @Volatile var diagnostics: LyricDiagnostics = LyricDiagnostics()
        private set
    private var lastKey = ""

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        worker.scheduleWithFixedDelay({ tick(app) }, 0, 700, TimeUnit.MILLISECONDS)
    }

    private fun tick(context: Context) {
        val s = PlaybackStore.snapshot
        if (!s.active) {
            diagnostics = LyricDiagnostics(state = "waiting-media")
            return
        }

        val key = "${s.packageName}|${s.mediaId}|${s.title}|${s.artist}|${s.durationMs}"
        if (key == lastKey && LyricStore.document.lines.isNotEmpty()) return

        if (key != lastKey) {
            lastKey = key
            LyricStore.document = LyricDocument.EMPTY
        }

        cache[key]?.let {
            LyricStore.document = it
            diagnostics = diagnosticFor(key, "cache-hit", "cache", it)
            return
        }

        val query = TrackQuery(s.packageName, s.mediaId, s.title, s.artist, s.durationMs)
        val providers = listOf(
            LocalLyricProvider,
            QQLyricProvider,
            NetEaseLyricProvider,
            KugouLyricProvider
        )

        var fallback: LyricDocument? = null
        val attemptLog = ArrayList<String>()

        for (provider in providers) {
            diagnostics = LyricDiagnostics(
                trackKey = key,
                state = "matching",
                attempts = attemptLog.plus("${provider.name}:…").joinToString(" | ")
            )

            val result = try {
                provider.load(context, query)
            } catch (error: Throwable) {
                attemptLog += "${provider.name}:ERR(${error.javaClass.simpleName})"
                null
            }

            if (result == null || result.lines.isEmpty()) {
                if (attemptLog.none { it.startsWith("${provider.name}:ERR") }) {
                    attemptLog += "${provider.name}:MISS"
                }
                continue
            }

            attemptLog += "${provider.name}:" + if (result.hasWordTimings) "WORD" else "LRC"

            if (result.hasWordTimings) {
                store(key, result)
                diagnostics = diagnosticFor(
                    key,
                    "matched-word",
                    attemptLog.joinToString(" | "),
                    result
                )
                return
            }

            if (fallback == null) fallback = result
        }

        val chosen = fallback
        if (chosen != null) {
            store(key, chosen)
            diagnostics = diagnosticFor(
                key,
                "matched-line",
                attemptLog.joinToString(" | "),
                chosen
            )
        } else {
            LyricStore.document = LyricDocument.EMPTY
            diagnostics = LyricDiagnostics(
                trackKey = key,
                state = "no-match",
                attempts = attemptLog.joinToString(" | ")
            )
        }
    }

    private fun store(key: String, document: LyricDocument) {
        cache[key] = document
        while (cache.size > 64) cache.remove(cache.keys.first())
        LyricStore.document = document
    }

    private fun diagnosticFor(
        key: String,
        state: String,
        attempts: String,
        document: LyricDocument
    ) = LyricDiagnostics(
        trackKey = key,
        state = state,
        attempts = attempts,
        source = document.source,
        wordTimed = document.hasWordTimings,
        lineCount = document.lines.size,
        wordCount = document.timedWordCount
    )
}
