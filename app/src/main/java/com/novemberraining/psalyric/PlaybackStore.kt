package com.novemberraining.psalyric

import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock

data class PlaybackSnapshot(
    val packageName: String = "",
    val title: String = "",
    val artist: String = "",
    val mediaId: String = "",
    val durationMs: Long = 0L,
    val basePositionMs: Long = 0L,
    val updateElapsedMs: Long = 0L,
    val speed: Float = 0f,
    val state: Int = PlaybackState.STATE_NONE,
    val artwork: Bitmap? = null
) {
    val active: Boolean get() = title.isNotBlank()
    val playing: Boolean get() = state == PlaybackState.STATE_PLAYING

    fun estimatedPosition(nowElapsed: Long = SystemClock.elapsedRealtime()): Long {
        if (!playing || speed == 0f || updateElapsedMs <= 0L) return basePositionMs.coerceAtLeast(0L)
        val delta = ((nowElapsed - updateElapsedMs).coerceAtLeast(0L) * speed).toLong()
        return (basePositionMs + delta).coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
    }
}

data class MediaDiagnostics(
    val activeSessionCount: Int = 0,
    val selectedPackage: String = "",
    val selectedTitle: String = "",
    val selectedState: Int = PlaybackState.STATE_NONE,
    val reason: String = "waiting",
    val updatedElapsedMs: Long = 0L
)

object PlaybackStore {
    @Volatile var snapshot: PlaybackSnapshot = PlaybackSnapshot()
    @Volatile var controller: MediaController? = null
    @Volatile var diagnostics: MediaDiagnostics = MediaDiagnostics()

    fun publish(
        selectedController: MediaController?,
        selectedSnapshot: PlaybackSnapshot,
        activeSessionCount: Int,
        reason: String
    ) {
        controller = selectedController
        snapshot = selectedSnapshot
        diagnostics = MediaDiagnostics(
            activeSessionCount = activeSessionCount,
            selectedPackage = selectedSnapshot.packageName,
            selectedTitle = selectedSnapshot.title,
            selectedState = selectedSnapshot.state,
            reason = reason,
            updatedElapsedMs = SystemClock.elapsedRealtime()
        )
    }

    fun clear(activeSessionCount: Int, reason: String) {
        controller = null
        snapshot = PlaybackSnapshot()
        diagnostics = MediaDiagnostics(
            activeSessionCount = activeSessionCount,
            reason = reason,
            updatedElapsedMs = SystemClock.elapsedRealtime()
        )
    }

    fun playPause() {
        val c = controller ?: return
        if (snapshot.playing) c.transportControls.pause() else c.transportControls.play()
    }

    fun previous() { controller?.transportControls?.skipToPrevious() }
    fun next() { controller?.transportControls?.skipToNext() }
    fun seekTo(positionMs: Long) { controller?.transportControls?.seekTo(positionMs.coerceAtLeast(0L)) }
}
