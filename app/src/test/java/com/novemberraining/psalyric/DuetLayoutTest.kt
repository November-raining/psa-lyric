package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuetLayoutTest {
    @Test fun inlineGenderPrefixesAlternateAndCenter() {
        val lines = listOf(
            LyricLine(0, 1000, "男：第一句"),
            LyricLine(1000, 1000, "女：第二句"),
            LyricLine(2000, 1000, "合：一起唱")
        )

        val out = DuetLayout.process(lines)

        assertEquals(listOf("第一句", "第二句", "一起唱"), out.map { it.text })
        assertEquals(
            listOf(DuetAlignment.LEFT, DuetAlignment.RIGHT, DuetAlignment.CENTER),
            out.map { it.alignment }
        )
    }

    @Test fun bracketGenderPrefixesAreRecognized() {
        val lines = listOf(
            LyricLine(0, 1000, "（男）第一句"),
            LyricLine(1000, 1000, "(女)第二句"),
            LyricLine(2000, 1000, "[合]一起唱")
        )

        val out = DuetLayout.process(lines)

        assertEquals(listOf("第一句", "第二句", "一起唱"), out.map { it.text })
        assertEquals(
            listOf(DuetAlignment.LEFT, DuetAlignment.RIGHT, DuetAlignment.CENTER),
            out.map { it.alignment }
        )
    }

    @Test fun standaloneGenderMarkerAppliesToFollowingLines() {
        val lines = listOf(
            LyricLine(0, 100, "女："),
            LyricLine(100, 1000, "女声第一句"),
            LyricLine(1100, 100, "男："),
            LyricLine(1200, 1000, "男声第一句"),
            LyricLine(2200, 100, "合："),
            LyricLine(2300, 1000, "合唱")
        )

        val out = DuetLayout.process(lines)

        assertEquals(3, out.size)
        assertEquals(DuetAlignment.LEFT, out[0].alignment)
        assertEquals(DuetAlignment.RIGHT, out[1].alignment)
        assertEquals(DuetAlignment.CENTER, out[2].alignment)
    }

    @Test fun singerMarkerRowsSplitTwoVoicesAndGroup() {
        val lines = listOf(
            LyricLine(0, 100, "周杰伦："),
            LyricLine(100, 1000, "海平面远方开始阴霾"),
            LyricLine(1100, 100, "Lara梁心颐："),
            LyricLine(1200, 1000, "悲伤要怎么平静纯白"),
            LyricLine(2200, 100, "周杰伦/Lara梁心颐："),
            LyricLine(2300, 1000, "转身离开分手说不出来")
        )

        val out = DuetLayout.process(lines)

        assertEquals(3, out.size)
        assertEquals(DuetAlignment.LEFT, out[0].alignment)
        assertEquals(DuetAlignment.RIGHT, out[1].alignment)
        assertEquals(DuetAlignment.CENTER, out[2].alignment)
    }

    @Test fun inlineSingerAliasesSupportXiaoJiuWoStyle() {
        val lines = listOf(
            LyricLine(0, 1000, "杰：我还在寻找一个依靠"),
            LyricLine(1000, 1000, "谁替我祈祷替我烦恼"),
            LyricLine(2000, 1000, "SA：幸福开始有预兆"),
            LyricLine(3000, 1000, "缘分让我们慢慢紧靠"),
            LyricLine(4000, 1000, "合：小酒窝长睫毛是你最美的记号"),
            LyricLine(5000, 1000, "终于找到心有灵犀的美好")
        )

        val out = DuetLayout.process(lines)

        assertEquals(
            listOf(
                "我还在寻找一个依靠",
                "谁替我祈祷替我烦恼",
                "幸福开始有预兆",
                "缘分让我们慢慢紧靠",
                "小酒窝长睫毛是你最美的记号",
                "终于找到心有灵犀的美好"
            ),
            out.map { it.text }
        )
        assertEquals(
            listOf(
                DuetAlignment.LEFT,
                DuetAlignment.LEFT,
                DuetAlignment.RIGHT,
                DuetAlignment.RIGHT,
                DuetAlignment.CENTER,
                DuetAlignment.CENTER
            ),
            out.map { it.alignment }
        )
    }

    @Test fun singleColonSentenceDoesNotEnableSingerMode() {
        val lines = listOf(
            LyricLine(0, 1000, "旁白：这不是第二位歌手标签"),
            LyricLine(1000, 1000, "普通歌词")
        )

        val out = DuetLayout.process(lines)

        assertEquals(2, out.size)
        assertEquals("旁白：这不是第二位歌手标签", out[0].text)
        assertEquals(DuetAlignment.LEFT, out[0].alignment)
        assertEquals(DuetAlignment.LEFT, out[1].alignment)
    }

    @Test fun creditsAreNotTreatedAsSingerMarkers() {
        val lines = listOf(
            LyricLine(0, 1000, "作词：方文山"),
            LyricLine(1000, 1000, "作曲：周杰伦")
        )
        val out = DuetLayout.process(lines)
        assertEquals(2, out.size)
        assertEquals("作词：方文山", out[0].text)
        assertEquals("作曲：周杰伦", out[1].text)
    }

    @Test fun strippingInlinePrefixPreservesRemainingWordTiming() {
        val line = LyricLine(
            0,
            1000,
            "女：你好",
            words = listOf(
                WordTiming(0, 100, "女："),
                WordTiming(100, 300, "你"),
                WordTiming(400, 300, "好")
            )
        )

        val out = DuetLayout.process(listOf(line)).single()

        assertEquals("你好", out.text)
        assertEquals("你好", out.words.joinToString("") { it.text })
        assertTrue(out.words.first().startMs >= 100L)
    }

    @Test fun inlineAliasPrefixPreservesRemainingWordTiming() {
        val lines = listOf(
            LyricLine(
                0,
                1000,
                "杰：你好",
                words = listOf(
                    WordTiming(0, 100, "杰："),
                    WordTiming(100, 300, "你"),
                    WordTiming(400, 300, "好")
                )
            ),
            LyricLine(1000, 1000, "SA：你好啊")
        )

        val out = DuetLayout.process(lines)

        assertEquals("你好", out[0].text)
        assertEquals("你好", out[0].words.joinToString("") { it.text })
        assertEquals(DuetAlignment.LEFT, out[0].alignment)
        assertEquals(DuetAlignment.RIGHT, out[1].alignment)
    }
}
