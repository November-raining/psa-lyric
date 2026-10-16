package com.novemberraining.psalyric

import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService

class MediaBridgeListener : NotificationListenerService() {
    private lateinit var sessionManager: MediaSessionManager
    private val handler = Handler(Looper.getMainLooper())
    private val observed = LinkedHashMap<MediaSession.Token, MediaController>()
    private var bound: MediaController? = null
    private var sessionsListenerAdded = false

    private val sessionCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = scheduleScan()
        override fun onPlaybackStateChanged(state: PlaybackState?) = scheduleScan()
        override fun onSessionDestroyed() = scheduleScan()
    }

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { scheduleScan() }

    private val poll = object : Runnable {
        override fun run() {
            scan()
            handler.postDelayed(this, 600L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(MediaSessionManager::class.java)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val component = ComponentName(this, MediaBridgeListener::class.java)
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                activeSessionsListener, component, handler
            )
            sessionsListenerAdded = true
        } catch (_: Throwable) {
            sessionsListenerAdded = false
        }
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(poll)
        removeSessionsListener()
        clearObserved()
        bound = null
        PlaybackStore.clear(0, "listener-disconnected")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        removeSessionsListener()
        clearObserved()
        bound = null
        super.onDestroy()
    }

    private fun scheduleScan() {
        handler.removeCallbacks(scanRunnable)
        handler.post(scanRunnable)
    }

    private val scanRunnable = Runnable { scan() }

    private fun scan() {
        val component = ComponentName(this, MediaBridgeListener::class.java)
        val sessions = try {
            sessionManager.getActiveSessions(component) ?: emptyList()
        } catch (_: Throwable) {
            PlaybackStore.diagnostics = PlaybackStore.diagnostics.copy(
                reason = "getActiveSessions-failed",
                updatedElapsedMs = SystemClock.elapsedRealtime()
            )
            return
        }

        syncObserved(sessions)

        val previous = bound
        var best: MediaController? = null
        var retained: MediaController? = null
        var bestScore = Int.MIN_VALUE

        for (candidate in sessions) {
            if (candidate.packageName == packageName) continue

            val metadata = candidate.metadata
            val state = candidate.playbackState
            val stateValue = state?.state ?: PlaybackState.STATE_NONE
            val label = applicationLabel(candidate.packageName)
            val title = firstNonEmpty(
                metadata,
                MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE
            )
            val same = sameSession(previous, candidate)
            val usableTitle = MediaSelection.usableTitle(title, label)

            if (!usableTitle) {
                if (
                    MediaSelection.retainMissingTitle(
                        sameSession = same,
                        hadTitle = PlaybackStore.snapshot.title.isNotBlank() &&
                            PlaybackStore.snapshot.packageName == candidate.packageName,
                        titleNowBlank = title.isBlank(),
                        state = stateValue
                    )
                ) {
                    retained = candidate
                }
                continue
            }

            if (!isUsableSession(stateValue, metadata, label)) continue

            val score = MediaSelection.score(
                state = stateValue,
                hasUsableMetadata = true,
                supportsControls = supportsControls(state),
                currentSession = same
            )
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }

        if (best == null && retained != null) {
            bound = retained
            publishRetained(retained, sessions.size)
            return
        }

        if (best == null) {
            bound = null
            PlaybackStore.clear(sessions.size, "no-usable-session")
            return
        }

        bound = best
        publish(best, sessions.size, "score=$bestScore")
    }

    private fun syncObserved(sessions: List<MediaController>) {
        val next = LinkedHashMap<MediaSession.Token, MediaController>()
        for (candidate in sessions) {
            if (candidate.packageName == packageName) continue
            val token = candidate.sessionToken
            next[token] = candidate
            if (!observed.containsKey(token)) {
                try { candidate.registerCallback(sessionCallback, handler) } catch (_: Throwable) {}
            }
        }
        for ((token, controller) in observed) {
            if (!next.containsKey(token)) {
                try { controller.unregisterCallback(sessionCallback) } catch (_: Throwable) {}
            }
        }
        observed.clear()
        observed.putAll(next)
    }

    private fun clearObserved() {
        for (controller in observed.values) {
            try { controller.unregisterCallback(sessionCallback) } catch (_: Throwable) {}
        }
        observed.clear()
    }

    private fun removeSessionsListener() {
        if (!sessionsListenerAdded) return
        try { sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        catch (_: Throwable) {}
        sessionsListenerAdded = false
    }

    private fun publish(c: MediaController, count: Int, reason: String) {
        val md = c.metadata
        val st = c.playbackState
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val title = firstNonEmpty(
            md,
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE
        )
        val artist = firstNonEmpty(
            md,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE
        )
        val snapshot = PlaybackSnapshot(
            packageName = c.packageName,
            title = title,
            artist = artist,
            mediaId = firstNonEmpty(md, MediaMetadata.METADATA_KEY_MEDIA_ID),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            basePositionMs = st?.position?.coerceAtLeast(0L) ?: 0L,
            updateElapsedMs = if (st != null && st.lastPositionUpdateTime > 0L) {
                st.lastPositionUpdateTime
            } else {
                SystemClock.elapsedRealtime()
            },
            speed = st?.playbackSpeed ?: 0f,
            state = st?.state ?: PlaybackState.STATE_NONE,
            artwork = art
        )
        PlaybackStore.publish(c, snapshot, count, reason)
    }

    private fun publishRetained(c: MediaController, count: Int) {
        val previous = PlaybackStore.snapshot
        val state = c.playbackState
        val snapshot = previous.copy(
            basePositionMs = state?.position?.coerceAtLeast(0L) ?: previous.basePositionMs,
            updateElapsedMs = if (state != null && state.lastPositionUpdateTime > 0L) {
                state.lastPositionUpdateTime
            } else {
                SystemClock.elapsedRealtime()
            },
            speed = state?.playbackSpeed ?: previous.speed,
            state = state?.state ?: previous.state
        )
        PlaybackStore.publish(c, snapshot, count, "retained-transient-metadata")
    }

    private fun isUsableSession(
        state: Int,
        metadata: MediaMetadata?,
        label: String
    ): Boolean {
        if (state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_ERROR) return false
        if (MediaSelection.playbackRank(state) > 0) return true
        val title = firstNonEmpty(
            metadata,
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE
        )
        return MediaSelection.usableTitle(title, label)
    }

    private fun supportsControls(state: PlaybackState?): Boolean {
        if (state == null) return false
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS
        return state.actions and actions != 0L
    }

    private fun applicationLabel(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info)?.toString()?.trim().orEmpty()
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    } catch (_: SecurityException) {
        ""
    }

    private fun firstNonEmpty(metadata: MediaMetadata?, vararg keys: String): String {
        if (metadata == null) return ""
        for (key in keys) {
            val value = metadata.getString(key)?.trim().orEmpty()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun sameSession(left: MediaController?, right: MediaController?): Boolean =
        left === right || (
            left != null && right != null && left.sessionToken == right.sessionToken
        )
}
