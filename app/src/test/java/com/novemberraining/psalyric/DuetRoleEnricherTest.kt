package com.novemberraining.psalyric

import org.junit.Assert.assertEquals
import org.junit.Test

class DuetRoleEnricherTest {
    @Test fun backupProviderCanSupplyRolesWithoutReplacingWordTiming() {
        val primary = LyricDocument(
            lines = listOf(
                wordLine(10_000, "我还在寻找 一个依靠", DuetAlignment.LEFT),
                wordLine(20_000, "谁替我祈祷 替我烦恼", DuetAlignment.LEFT),
                wordLine(30_000, "幸福开始有预兆", DuetAlignment.LEFT),
                wordLine(40_000, "缘分让我们慢慢紧靠", DuetAlignment.LEFT),
                wordLine(50_000, "小酒窝 长睫毛", DuetAlignment.LEFT),
                wordLine(60_000, "是你最美的记号", DuetAlignment.LEFT)
            ),
            source = "QQ · QRC"
        )
        val donor = LyricDocument(
            lines = listOf(
                LyricLine(10_100, 1000, "我还在寻找一个依靠", alignment = DuetAlignment.LEFT),
                LyricLine(20_100, 1000, "谁替我祈祷替我烦恼", alignment = DuetAlignment.LEFT),
                LyricLine(30_100, 1000, "幸福开始有预兆", alignment = DuetAlignment.RIGHT),
                LyricLine(40_100, 1000, "缘分让我们慢慢紧靠", alignment = DuetAlignment.RIGHT),
                LyricLine(50_100, 1000, "小酒窝长睫毛", alignment = DuetAlignment.CENTER),
                LyricLine(60_100, 1000, "是你最美的记号", alignment = DuetAlignment.CENTER)
            ),
            source = "酷狗 · KRC"
        )

        val out = DuetRoleEnricher.enrich(primary, listOf(donor))

        assertEquals("QQ · QRC", out.source)
        assertEquals(
            listOf(
                DuetAlignment.LEFT,
                DuetAlignment.LEFT,
                DuetAlignment.RIGHT,
                DuetAlignment.RIGHT,
                DuetAlignment.CENTER,
                DuetAlignment.CENTER
            ),
            out.lines.map { it.alignment }
        )
        assertEquals(
            primary.lines.flatMap { it.words }.map { it.startMs },
            out.lines.flatMap { it.words }.map { it.startMs }
        )
    }

    @Test fun primarySplitIsNeverOverwrittenByHeuristicDonor() {
        val primary = LyricDocument(
            listOf(
                LyricLine(0, 1000, "A", alignment = DuetAlignment.LEFT),
                LyricLine(1000, 1000, "B", alignment = DuetAlignment.RIGHT),
                LyricLine(2000, 1000, "C", alignment = DuetAlignment.CENTER)
            ),
            source = "QQ · QRC"
        )
        val donor = LyricDocument(
            listOf(
                LyricLine(0, 1000, "A", alignment = DuetAlignment.CENTER),
                LyricLine(1000, 1000, "B", alignment = DuetAlignment.CENTER),
                LyricLine(2000, 1000, "C", alignment = DuetAlignment.CENTER)
            )
        )

        assertEquals(primary, DuetRoleEnricher.enrich(primary, listOf(donor)))
    }

    private fun wordLine(
        start: Long,
        text: String,
        alignment: DuetAlignment
    ) = LyricLine(
        startMs = start,
        durationMs = 1000,
        text = text,
        words = listOf(WordTiming(start, 1000, text)),
        alignment = alignment
    )
}
