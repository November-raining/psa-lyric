package com.novemberraining.psalyric

import android.media.session.PlaybackState
import java.util.Locale

object MediaSelection {
    fun playbackRank(state: Int): Int = when (state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING -> 10_000
        PlaybackState.STATE_BUFFERING -> 9_000
        PlaybackState.STATE_CONNECTING -> 8_000
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> 7_000
        PlaybackState.STATE_PAUSED -> 5_000
        else -> 0
    }

    fun isServiceTitle(title: String, appLabel: String): Boolean {
        val value = normalize(title)
        val app = normalize(appLabel)
        if (value.isBlank() || app.isBlank()) return false
        if (value == app) return true
        if (!value.startsWith(app)) return false
        val suffix = value.removePrefix(app)
        return suffix.matches(
            Regex("(?:服务|音乐服务|运行中|已启动|正在运行|后台|后台运行|service|musicservice|running)")
        )
    }

    fun usableTitle(title: String, appLabel: String): Boolean {
        val value = normalize(title)
        return value.isNotBlank() &&
            !isServiceTitle(title, appLabel) &&
            !value.matches(
                Regex("(?:未知歌曲|未知歌名|unknown|unknownsong|unknowntrack|notprovided|unavailable)")
            )
    }

    fun score(
        state: Int,
        hasUsableMetadata: Boolean,
        supportsControls: Boolean,
        currentSession: Boolean
    ): Int {
        var score = playbackRank(state)
        // A real track identity outranks a playing shell session.
        if (hasUsableMetadata) score += 10_000
        if (supportsControls) score += 100
        if (currentSession) score += 25
        return score
    }

    fun retainMissingTitle(
        sameSession: Boolean,
        hadTitle: Boolean,
        titleNowBlank: Boolean,
        state: Int
    ): Boolean = sameSession &&
        hadTitle &&
        titleNowBlank &&
        state != PlaybackState.STATE_STOPPED &&
        state != PlaybackState.STATE_ERROR

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
}
