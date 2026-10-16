package com.novemberraining.psalyric

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService

class MediaBridgeListener : NotificationListenerService() {
    private lateinit var sessionManager: MediaSessionManager
    private val handler = Handler(Looper.getMainLooper())
    private var bound: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish(bound)
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish(bound)
        override fun onSessionDestroyed() {
            bound = null
            handler.post { scan() }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            scan()
            handler.postDelayed(this, 750L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(MediaSessionManager::class.java)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(poll)
        bind(null)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        bind(null)
        super.onDestroy()
    }

    private fun scan() {
        val component = ComponentName(this, MediaBridgeListener::class.java)
        val sessions = try { sessionManager.getActiveSessions(component) } catch (_: Throwable) { emptyList() }
        val best = sessions
            .filter { it.metadata != null }
            .maxByOrNull { score(it) }
        if (best?.sessionToken != bound?.sessionToken) bind(best)
        publish(best)
    }

    private fun score(c: MediaController): Int {
        val state = c.playbackState?.state ?: PlaybackState.STATE_NONE
        val md = c.metadata
        var score = when (state) {
            PlaybackState.STATE_PLAYING -> 100
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> 70
            PlaybackState.STATE_PAUSED -> 40
            else -> 0
        }
        if (!md?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()) score += 20
        if (!md?.getString(MediaMetadata.METADATA_KEY_ARTIST).isNullOrBlank()) score += 5
        return score
    }

    private fun bind(c: MediaController?) {
        try { bound?.unregisterCallback(callback) } catch (_: Throwable) {}
        bound = c
        PlaybackStore.controller = c
        if (c != null) {
            try { c.registerCallback(callback, handler) } catch (_: Throwable) {}
        }
    }

    private fun publish(c: MediaController?) {
        if (c == null) return
        val md = c.metadata
        val st = c.playbackState
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        PlaybackStore.snapshot = PlaybackSnapshot(
            packageName = c.packageName ?: "",
            title = title,
            artist = artist,
            mediaId = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: "",
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            basePositionMs = st?.position ?: 0L,
            updateElapsedMs = if (st != null && st.lastPositionUpdateTime > 0) st.lastPositionUpdateTime else SystemClock.elapsedRealtime(),
            speed = st?.playbackSpeed ?: 0f,
            state = st?.state ?: PlaybackState.STATE_NONE,
            artwork = art
        )
        PlaybackStore.controller = c
    }
}
