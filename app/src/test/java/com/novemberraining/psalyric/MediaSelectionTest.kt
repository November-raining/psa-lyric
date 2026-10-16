package com.novemberraining.psalyric

import android.media.session.PlaybackState
import org.junit.Assert.*
import org.junit.Test

class MediaSelectionTest {
    @Test fun playingTrackBeatsPausedTrack() {
        val playing = MediaSelection.score(
            PlaybackState.STATE_PLAYING, true, true, false
        )
        val paused = MediaSelection.score(
            PlaybackState.STATE_PAUSED, true, true, false
        )
        assertTrue(playing > paused)
    }

    @Test fun metadataBeatsPlayingShell() {
        val shell = MediaSelection.score(
            PlaybackState.STATE_PLAYING, false, true, false
        )
        val pausedTrack = MediaSelection.score(
            PlaybackState.STATE_PAUSED, true, true, false
        )
        assertTrue(pausedTrack > shell)
    }

    @Test fun serviceTitleIsRejected() {
        assertTrue(MediaSelection.isServiceTitle("QQ音乐 服务", "QQ音乐"))
        assertFalse(MediaSelection.usableTitle("QQ音乐 服务", "QQ音乐"))
        assertTrue(MediaSelection.usableTitle("后来", "QQ音乐"))
    }

    @Test fun transientBlankTitleCanRetainCurrentTrack() {
        assertTrue(
            MediaSelection.retainMissingTitle(
                sameSession = true,
                hadTitle = true,
                titleNowBlank = true,
                state = PlaybackState.STATE_PLAYING
            )
        )
    }
}
