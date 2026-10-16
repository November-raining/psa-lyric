package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricSecondaryMergeTest {
    @Test fun translationNearQrcTimestampIsMerged() {
        val doc = LyricParsers.parse(
            lrc = "",
            translationLrc = "[00:01.20]你好",
            yrc = "[1000,1000](1000,1000,0)Hello"
        )
        assertEquals(1, doc.lines.size)
        assertEquals("你好", doc.lines[0].translation)
        assertTrue(doc.hasTranslation)
        assertEquals(1, doc.translationLineCount)
    }

    @Test fun originalTextCanBridgeLargerQrcTimestampDifference() {
        val doc = LyricParsers.parse(
            lrc = "[00:08.00]Hello",
            translationLrc = "[00:08.00]你好",
            yrc = "[10000,1000](10000,1000,0)Hello"
        )
        assertEquals("你好", doc.lines[0].translation)
    }

    @Test fun romajiUsesNearestTimestampWithinTolerance() {
        val doc = LyricParsers.parse(
            lrc = "",
            yrc = "[2000,1000](2000,1000,0)こんにちは",
            romajiLrc = "[00:02.30]konnichiwa"
        )
        assertEquals("konnichiwa", doc.lines[0].romaji)
        assertTrue(doc.hasRomaji)
        assertEquals(1, doc.romajiLineCount)
    }
}
