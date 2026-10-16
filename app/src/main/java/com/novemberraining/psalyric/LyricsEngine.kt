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
    val translationLineCount: Int = 0,
    val romajiLineCount: Int = 0,
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
    private const val SECONDARY_RETRY_MS = 60_000L

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val onlinePool = Executors.newFixedThreadPool(3)
    private val cache = LinkedHashMap<String, LyricDocument>()

    @Volatile private var started = false
    @Volatile var diagnostics: LyricDiagnostics = LyricDiagnostics()
        private set

    private var lastKey = ""
    private var lastSecondarySignature = ""
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
        val secondarySignature = secondarySignature(context)
        val currentDocument = LyricStore.document
        if (
            key == lastKey &&
            lastSecondarySignature == secondarySignature &&
            currentDocument.lines.isNotEmpty() &&
            documentSatisfiesSecondary(context, currentDocument)
        ) return
        if (
            key == lastKey &&
            lastSecondarySignature == secondarySignature &&
            now < retryAfterElapsed
        ) return

        if (key != lastKey) {
            lastKey = key
            lastSecondarySignature = secondarySignature
            retryAfterElapsed = 0L
            LyricStore.document = LyricDocument.EMPTY
        } else if (lastSecondarySignature != secondarySignature) {
            lastSecondarySignature = secondarySignature
            retryAfterElapsed = 0L
        }

        var wordFallback: LyricDocument? = null
        var lineFallback: LyricDocument? = null

        cache[key]?.let { cached ->
            if (trackKey(PlaybackStore.snapshot) == key) {
                LyricStore.document = cached
                if (documentSatisfiesSecondary(context, cached)) {
                    diagnostics = diagnosticFor(
                        key = key,
                        state = "cache-hit",
                        attempts = "cache",
                        document = cached,
                        elapsedMs = 0L
                    )
                    return
                }
                if (cached.hasWordTimings) wordFallback = cached
                else if (cached.lines.isNotEmpty()) lineFallback = cached
                diagnostics = diagnosticFor(
                    key = key,
                    state = "cache-enriching-secondary",
                    attempts = "cache:SECONDARY_MISS",
                    document = cached,
                    elapsedMs = 0L
                )
            }
        }

        val query = TrackQuery(
            snapshot.packageName,
            snapshot.mediaId,
            snapshot.title,
            snapshot.artist,
            snapshot.durationMs
        )

        val startedAt = SystemClock.elapsedRealtime()
        val status = LinkedHashMap<String, ProviderOutcome?>()

        // Local I/O has no network latency, so evaluate it before launching the online race.
        val localOutcome = runProvider(context, LocalLyricProvider, query)
        status["本地"] = localOutcome
        val localDocument = localOutcome.document
        if (localDocument?.hasWordTimings == true) {
            LyricStore.document = localDocument
            if (documentSatisfiesSecondary(context, localDocument)) {
                finishMatch(context, key, localDocument, "matched-word", status, startedAt)
                return
            }
            wordFallback = localDocument
        } else if (localDocument?.lines?.isNotEmpty() == true) {
            lineFallback = localDocument
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
                if (
                    qq?.document?.hasWordTimings == true &&
                    documentSatisfiesSecondary(context, qq.document)
                ) {
                    finishMatch(context, key, qq.document, "matched-word", status, startedAt)
                    return
                }

                val backupWord = preferredBackupWord(context, status, requireSecondary = true)
                val qqFinished = qq != null
                if (backupWord != null && (qqFinished || elapsed >= QQ_GRACE_MS)) {
                    finishMatch(context, key, backupWord, "matched-word", status, startedAt)
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

            val completeWord = preferredWord(context, status, requireSecondary = true)
            if (completeWord != null) {
                finishMatch(context, key, completeWord, "matched-word", status, startedAt)
                return
            }

            val anyWord = wordFallback ?: preferredWord(
                context,
                status,
                requireSecondary = false
            )
            if (anyWord != null) {
                finishMatch(
                    context,
                    key,
                    anyWord,
                    "matched-word-secondary-unavailable",
                    status,
                    startedAt
                )
                return
            }

            if (lineFallback == null) {
                lineFallback = preferredLine(context, status, requireSecondary = true)
                    ?: preferredLine(context, status, requireSecondary = false)
            }
            val line = lineFallback
            if (line != null) {
                finishMatch(context, key, line, "matched-line", status, startedAt)
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

    private fun preferredWord(
        context: Context,
        status: Map<String, ProviderOutcome?>,
        requireSecondary: Boolean
    ): LyricDocument? {
        for (name in listOf("QQ", "酷狗", "网易云")) {
            val doc = status[name]?.document ?: continue
            if (!doc.hasWordTimings) continue
            if (!requireSecondary || documentSatisfiesSecondary(context, doc)) return doc
        }
        return null
    }

    private fun preferredBackupWord(
        context: Context,
        status: Map<String, ProviderOutcome?>,
        requireSecondary: Boolean
    ): LyricDocument? {
        for (name in listOf("酷狗", "网易云")) {
            val doc = status[name]?.document ?: continue
            if (!doc.hasWordTimings) continue
            if (!requireSecondary || documentSatisfiesSecondary(context, doc)) return doc
        }
        return null
    }

    private fun preferredLine(
        context: Context,
        status: Map<String, ProviderOutcome?>,
        requireSecondary: Boolean
    ): LyricDocument? {
        for (name in listOf("QQ", "酷狗", "网易云")) {
            val doc = status[name]?.document ?: continue
            if (doc.lines.isEmpty() || doc.hasWordTimings) continue
            if (!requireSecondary || documentSatisfiesSecondary(context, doc)) return doc
        }
        return null
    }

    private fun documentSatisfiesSecondary(
        context: Context,
        document: LyricDocument
    ): Boolean {
        if (!Prefs.secondaryLyricEnabled(context)) return true
        return when (Prefs.secondaryLyricMode(context)) {
            LyricSecondaryRules.MODE_ROMAJI -> document.hasRomaji
            else -> document.hasTranslation
        }
    }

    private fun secondarySignature(context: Context): String =
        if (!Prefs.secondaryLyricEnabled(context)) {
            "off"
        } else {
            "on:${Prefs.secondaryLyricMode(context)}"
        }

    private fun allFinished(status: Map<String, ProviderOutcome?>): Boolean =
        listOf("QQ", "酷狗", "网易云").all { status[it] != null }

    private fun finishMatch(
        context: Context,
        key: String,
        document: LyricDocument,
        state: String,
        status: Map<String, ProviderOutcome?>,
        startedAt: Long
    ) {
        val current = PlaybackStore.snapshot
        if (trackKey(current) != key) return
        store(key, document)
        if (state == "matched-word-secondary-unavailable") {
            retryAfterElapsed = SystemClock.elapsedRealtime() + SECONDARY_RETRY_MS
        }

        // Persist every successful match in the app-private cache. This makes a later
        // next/previous round trip independent of volatile mediaId/session fields.
        LyricDiskCache.save(
            context,
            TrackQuery(
                current.packageName,
                current.mediaId,
                current.title,
                current.artist,
                current.durationMs
            ),
            document
        )

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
                outcome.document.hasWordTimings -> buildString {
                    append("WORD")
                    if (outcome.document.translationLineCount > 0) {
                        append("+T").append(outcome.document.translationLineCount)
                    }
                    if (outcome.document.romajiLineCount > 0) {
                        append("+R").append(outcome.document.romajiLineCount)
                    }
                }
                else -> buildString {
                    append("LRC")
                    if (outcome.document.translationLineCount > 0) {
                        append("+T").append(outcome.document.translationLineCount)
                    }
                    if (outcome.document.romajiLineCount > 0) {
                        append("+R").append(outcome.document.romajiLineCount)
                    }
                }
            }
            "$name:$suffix[${outcome.elapsedMs}ms]"
        }.joinToString(" | ")

    private fun shortDiagnostic(error: Throwable): String {
        // Provider wrappers carry stage information such as musicu=... / legacy=....
        // Preserve that top-level message instead of collapsing to the deepest HTTP cause.
        val message = error.message
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.trim()
            ?.take(96)
            .orEmpty()
        return if (message.isBlank()) error.javaClass.simpleName
        else "${error.javaClass.simpleName}:$message"
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
        translationLineCount = document.translationLineCount,
        romajiLineCount = document.romajiLineCount,
        elapsedMs = elapsedMs
    )

    private fun trackKey(snapshot: PlaybackSnapshot): String =
        "${snapshot.packageName}|${snapshot.mediaId}|${snapshot.title}|${snapshot.artist}|${snapshot.durationMs}"
}
