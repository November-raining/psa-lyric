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

    @Test fun duetPrefixChoosesAlignment() {
        val doc = LyricParsers.parse("[00:01.000]女：你好")
        assertEquals(DuetAlignment.RIGHT, doc.lines[0].alignment)
        assertEquals("你好", doc.lines[0].text)
    }
}
