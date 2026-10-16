package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistSelectionTest {
    @Test fun richerCollaboratorSubtitleBeatsPrimaryArtist() {
        assertEquals(
            "林俊杰&蔡卓妍",
            ArtistSelection.choose(
                artist = "林俊杰",
                albumArtist = "",
                displaySubtitle = "林俊杰&蔡卓妍",
                album = "JJ陆",
                title = "小酒窝"
            )
        )
    }

    @Test fun unrelatedSubtitleDoesNotReplaceArtist() {
        assertEquals(
            "林俊杰",
            ArtistSelection.choose(
                artist = "林俊杰",
                albumArtist = "",
                displaySubtitle = "JJ陆",
                album = "JJ陆",
                title = "小酒窝"
            )
        )
    }

    @Test fun collaborationDetectionIsConservative() {
        assertTrue(ArtistSelection.looksCollaborative("银临 / 司南"))
        assertTrue(ArtistSelection.looksCollaborative("林俊杰&蔡卓妍"))
        assertFalse(ArtistSelection.looksCollaborative("林俊杰"))
    }

    @Test fun lyricCandidateCanRestoreFullQqSingerCredit() {
        assertEquals(
            "银临&司南",
            ArtistSelection.chooseResolved("银临", "银临/司南")
        )
        assertEquals(
            "周杰伦&Lara梁心颐",
            ArtistSelection.chooseResolved("周杰伦", "周杰伦/Lara梁心颐")
        )
    }

    @Test fun unrelatedResolvedArtistNeverReplacesMediaArtist() {
        assertEquals(
            "周杰伦",
            ArtistSelection.chooseResolved("周杰伦", "林俊杰/蔡卓妍")
        )
    }
}
