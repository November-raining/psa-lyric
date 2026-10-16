package com.novemberraining.psalyric

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class LyricDiagnostics(
    val trackKey: String = "",
    val state: String = "waiting",
    val attempts: String = "",
    val source: String = "",
    val wordTimed: Boolean = false,
    val lineCount: Int = 0,
    val wordCount: Int = 0,
    val elapsedMs: Long = 0L
)

private data class ProviderOutcome(
    val name: String,
    val document: LyricDocument?,
    val error: Throwable?,
    val elapsedMs: Long
)

object LyricsEngine {
    private const val QQ_GRACE_MS = 3_200L
    private const val TOTAL_MATCH_BUDGET_MS = 6_500L
    private const val POLL_MS = 40L
    private const val NEGATIVE_RETRY_MS = 10_000L

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val onlinePool = Executors.newFixedThreadPool(3)
    private val cache = LinkedHashMap<String, LyricDocument>()

    @Volatile private var started = false
    @Volatile var diagnostics: LyricDiagnostics = LyricDiagnostics()
        private set

    private var lastKey = ""
    private var retryAfterElapsed = 0L

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scheduler.scheduleWithFixedDelay({ tick(app) }, 0, 350, TimeUnit.MILLISECONDS)
    }

    private fun tick(context: Context) {
        val snapshot = PlaybackStore.snapshot
        if (!snapshot.active) {
            diagnostics = LyricDiagnostics(state = "waiting-media")
            return
        }

        val key = trackKey(snapshot)
        val now = SystemClock.elapsedRealtime()
        if (key == lastKey && LyricStore.document.lines.isNotEmpty()) return
        if (key == lastKey && now < retryAfterElapsed) return

        if (key != lastKey) {
            lastKey = key
            retryAfterElapsed = 0L
            LyricStore.document = LyricDocument.EMPTY
        }

        cache[key]?.let {
            if (trackKey(PlaybackStore.snapshot) == key) {
                LyricStore.document = it
                diagnostics = diagnosticFor(
                    key = key,
                    state = "cache-hit",
                    attempts = "cache",
                    document = it,
                    elapsedMs = 0L
                )
            }
            return
        }

        val query = TrackQuery(
            snapshot.packageName,
            snapshot.mediaId,
            snapshot.title,
            snapshot.artist,
            snapshot.durationMs
        )

        val startedAt = SystemClock.elapsedRealtime()
        var lineFallback: LyricDocument? = null
        val status = LinkedHashMap<String, ProviderOutcome?>()

        // Local I/O has no network latency, so evaluate it before launching the online race.
        val localOutcome = runProvider(context, LocalLyricProvider, query)
        status["本地"] = localOutcome
        if (localOutcome.document?.hasWordTimings == true) {
            finishMatch(key, localOutcome.document, "matched-word", status, startedAt)
            return
        }
        if (localOutcome.document?.lines?.isNotEmpty() == true) {
            lineFallback = localOutcome.document
        }

        val providers = listOf(
            QQLyricProvider,
            KugouLyricProvider,
            NetEaseLyricProvider
        )
        val futures = LinkedHashMap<String, Future<ProviderOutcome>>()
        for (provider in providers) {
            status[provider.name] = null
            futures[provider.name] = onlinePool.submit<ProviderOutcome> {
                runProvider(context, provider, query)
            }
        }

        try {
            while (true) {
                if (trackKey(PlaybackStore.snapshot) != key) {
                    diagnostics = LyricDiagnostics(
                        trackKey = key,
                        state = "cancelled-track-change",
                        attempts = formatAttempts(status),
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    )
                    return
                }

                collectFinished(futures, status)

                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val qq = status["QQ"]
                if (qq?.document?.hasWordTimings == true) {
                    finishMatch(key, qq.document, "matched-word", status, startedAt)
                    return
                }

                val backupWord = preferredBackupWord(status)
                val qqFinished = qq != null
                if (backupWord != null && (qqFinished || elapsed >= QQ_GRACE_MS)) {
                    finishMatch(key, backupWord, "matched-word", status, startedAt)
                    return
                }

                if (allFinished(status)) break
                if (elapsed >= TOTAL_MATCH_BUDGET_MS) break

                diagnostics = LyricDiagnostics(
                    trackKey = key,
                    state = if (elapsed < QQ_GRACE_MS) "matching-qq-priority" else "matching-backup",
                    attempts = formatAttempts(status),
                    elapsedMs = elapsed
                )
                Thread.sleep(POLL_MS)
            }

            collectFinished(futures, status)

            val finalWord = preferredWord(status)
            if (finalWord != null) {
                finishMatch(key, finalWord, "matched-word", status, startedAt)
                return
            }

            if (lineFallback == null) {
                lineFallback = preferredLine(status)
            }
            val line = lineFallback
            if (line != null) {
                finishMatch(key, line, "matched-line", status, startedAt)
                return
            }

            retryAfterElapsed = SystemClock.elapsedRealtime() + NEGATIVE_RETRY_MS
            if (trackKey(PlaybackStore.snapshot) == key) {
                LyricStore.document = LyricDocument.EMPTY
                diagnostics = LyricDiagnostics(
                    trackKey = key,
                    state = "no-match",
                    attempts = formatAttempts(status),
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt
                )
            }
        } finally {
            for (future in futures.values) {
                if (!future.isDone) future.cancel(true)
            }
        }
    }

    private fun runProvider(
        context: Context,
        provider: LyricProvider,
        query: TrackQuery
    ): ProviderOutcome {
        val started = SystemClock.elapsedRealtime()
        return try {
            ProviderOutcome(
                name = provider.name,
                document = provider.load(context, query),
                error = null,
                elapsedMs = SystemClock.elapsedRealtime() - started
            )
        } catch (error: Throwable) {
            ProviderOutcome(
                name = provider.name,
                document = null,
                error = error,
                elapsedMs = SystemClock.elapsedRealtime() - started
            )
        }
    }

    private fun collectFinished(
        futures: Map<String, Future<ProviderOutcome>>,
        status: MutableMap<String, ProviderOutcome?>
    ) {
        for ((name, future) in futures) {
            if (status[name] != null || !future.isDone) continue
            status[name] = try {
                future.get()
            } catch (error: Throwable) {
                ProviderOutcome(name, null, error, 0L)
            }
        }
    }

    private fun preferredWord(status: Map<String, ProviderOutcome?>): LyricDocument? {
        // User-facing priority is explicit: QQ first, then KuGou, then NetEase.
        for (name in listOf("QQ", "酷狗", "网易云")) {
            val doc = status[name]?.document
            if (doc?.hasWordTimings == true) return doc
        }
        return null
    }

    private fun preferredBackupWord(status: Map<String, ProviderOutcome?>): LyricDocument? {
        for (name in listOf("酷狗", "网易云")) {
            val doc = status[name]?.document
            if (doc?.hasWordTimings == true) return doc
        }
        return null
    }

    private fun preferredLine(status: Map<String, ProviderOutcome?>): LyricDocument? {
        // If no enhanced timeline exists anywhere, prefer QQ's same-catalog LRC,
        // then KuGou, then NetEase. Local LRC is preserved separately and wins first.
        for (name in listOf("QQ", "酷狗", "网易云")) {
            val doc = status[name]?.document
            if (doc != null && doc.lines.isNotEmpty() && !doc.hasWordTimings) return doc
        }
        return null
    }

    private fun allFinished(status: Map<String, ProviderOutcome?>): Boolean =
        listOf("QQ", "酷狗", "网易云").all { status[it] != null }

    private fun finishMatch(
        key: String,
        document: LyricDocument,
        state: String,
        status: Map<String, ProviderOutcome?>,
        startedAt: Long
    ) {
        if (trackKey(PlaybackStore.snapshot) != key) return
        store(key, document)
        diagnostics = diagnosticFor(
            key = key,
            state = state,
            attempts = formatAttempts(status),
            document = document,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
        )
    }

    private fun formatAttempts(status: Map<String, ProviderOutcome?>): String =
        listOf("本地", "QQ", "酷狗", "网易云").mapNotNull { name ->
            if (!status.containsKey(name)) return@mapNotNull null
            val outcome = status[name] ?: return@mapNotNull "$name:…"
            val suffix = when {
                outcome.error != null -> "ERR(${shortDiagnostic(outcome.error)})"
                outcome.document == null || outcome.document.lines.isEmpty() -> "MISS"
                outcome.document.hasWordTimings -> "WORD"
                else -> "LRC"
            }
            "$name:$suffix[${outcome.elapsedMs}ms]"
        }.joinToString(" | ")

    private fun shortDiagnostic(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val message = root.message
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.trim()
            ?.take(72)
            .orEmpty()
        return if (message.isBlank()) root.javaClass.simpleName
        else "${root.javaClass.simpleName}:$message"
    }

    private fun store(key: String, document: LyricDocument) {
        cache[key] = document
        while (cache.size > 64) cache.remove(cache.keys.first())
        LyricStore.document = document
        retryAfterElapsed = 0L
    }

    private fun diagnosticFor(
        key: String,
        state: String,
        attempts: String,
        document: LyricDocument,
        elapsedMs: Long
    ) = LyricDiagnostics(
        trackKey = key,
        state = state,
        attempts = attempts,
        source = document.source,
        wordTimed = document.hasWordTimings,
        lineCount = document.lines.size,
        wordCount = document.timedWordCount,
        elapsedMs = elapsedMs
    )

    private fun trackKey(snapshot: PlaybackSnapshot): String =
        "${snapshot.packageName}|${snapshot.mediaId}|${snapshot.title}|${snapshot.artist}|${snapshot.durationMs}"
}
