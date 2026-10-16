package com.novemberraining.psalyric

import org.junit.Assert.*
import org.junit.Test

class EnhancedLyricTest {
    @Test fun qrcConvertsToAbsoluteWordTimeline() {
        val enhanced = QrcLyricCodec.toEnhancedTimeline(
            "[1000,2000]你(1000,500)好(1500,500)世界(2000,1000)"
        )
        val lines = LyricParsers.parseYrc(enhanced)
        assertEquals(1, lines.size)
        assertEquals("你好世界", lines[0].text)
        assertEquals(3, lines[0].words.size)
        assertEquals(1000L, lines[0].words[0].startMs)
        assertEquals(1500L, lines[0].words[1].startMs)
        assertEquals(2000L, lines[0].words[2].startMs)
        assertEquals(1000L, lines[0].words[2].durationMs)
    }

    @Test fun wordTimedDocumentReportsQuality() {
        val doc = LyricDocument(
            listOf(
                LyricLine(
                    startMs = 1000,
                    durationMs = 1000,
                    text = "你好",
                    words = listOf(
                        WordTiming(1000, 500, "你"),
                        WordTiming(1500, 500, "好")
                    )
                )
            ),
            source = "test"
        )
        assertTrue(doc.hasWordTimings)
        assertEquals(2, doc.timedWordCount)
    }
}
