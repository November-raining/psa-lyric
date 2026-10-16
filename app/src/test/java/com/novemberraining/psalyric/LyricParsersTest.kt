package com.novemberraining.psalyric

import org.junit.Assert.*
import org.junit.Test

class LyricParsersTest {
    @Test fun lrcParsesMilliseconds() {
        val lines = LyricParsers.parseLrc("[00:01.20]A\n[00:02.345]B")
        assertEquals(1200L, lines[0].startMs)
        assertEquals(2345L, lines[1].startMs)
    }

    @Test fun yrcKeepsAbsoluteWordTiming() {
        val doc = LyricParsers.parse(
            lrc = "",
            yrc = "[1000,2000](1000,500,0)你(1500,500,0)好"
        )
        assertEquals("你好", doc.lines[0].text)
        assertEquals(1500L, doc.lines[0].words[1].startMs)
    }

    @Test fun krcConvertsRelativeWordOffsetsToAbsoluteTime() {
        val lines = LyricParsers.parseKrc("[1000,2000]<0,500,0>你<500,600,0>好")
        assertEquals("你好", lines[0].text)
        assertEquals(1000L, lines[0].words[0].startMs)
        assertEquals(1500L, lines[0].words[1].startMs)
        assertEquals(600L, lines[0].words[1].durationMs)
    }

    @Test fun duetPrefixChoosesAlignment() {
        val doc = LyricParsers.parse(
            "[00:01.000]女：你好\n[00:02.000]男：你好啊"
        )
        // md3Music-style grouping: the first encountered primary voice owns left.
        assertEquals(DuetAlignment.LEFT, doc.lines[0].alignment)
        assertEquals(DuetAlignment.RIGHT, doc.lines[1].alignment)
        assertEquals("你好", doc.lines[0].text)
        assertEquals("你好啊", doc.lines[1].text)
    }
}
