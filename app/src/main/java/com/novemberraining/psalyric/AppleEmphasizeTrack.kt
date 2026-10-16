package com.novemberraining.psalyric

import kotlin.math.abs

/**
 * Frame-driven per-word emphasis phase tracker.
 *
 * md3Music v6/v8 no longer recomputes the long-syllable wave directly from the
 * media position every paint. Playback time is used to anchor the phase, then
 * Choreographer dt advances it every frame. Each timing word owns its own state,
 * so a previous word can finish its staggered tail after the next word starts.
 */
class AppleEmphasizeTrack {
    companion object {
        const val REANCHOR_TOLERANCE_MS = 150.0
        const val SEEK_RESET_MS = 500.0
    }

    private var boundStartMs = Long.MIN_VALUE
    private var boundText = ""
    private var anchors = DoubleArray(0)
    private var advances = DoubleArray(0)
    private var bumpPhases = emptyArray<DoubleArray>()
    private var floatPhases = emptyArray<DoubleArray>()
    private var states = emptyArray<Array<AppleEmphasizeState>>()
    private var lastPositionMs = Long.MIN_VALUE

    fun reset() {
        boundStartMs = Long.MIN_VALUE
        boundText = ""
        anchors = DoubleArray(0)
        advances = DoubleArray(0)
        bumpPhases = emptyArray()
        floatPhases = emptyArray()
        states = emptyArray()
        lastPositionMs = Long.MIN_VALUE
    }

    fun tick(
        line: LyricLine,
        emphasizeFlags: BooleanArray,
        positionMs: Long,
        playing: Boolean,
        dtSeconds: Float
    ) {
        bind(line, emphasizeFlags)
        if (line.words.isEmpty()) return

        val dt = dtSeconds.coerceIn(0f, 1f / 30f)
        val expected = if (lastPositionMs == Long.MIN_VALUE) {
            positionMs.toDouble()
        } else {
            lastPositionMs + if (playing) dt * 1000.0 else 0.0
        }
        if (
            lastPositionMs != Long.MIN_VALUE &&
            abs(positionMs - expected) > SEEK_RESET_MS
        ) {
            resetWaveState()
        }
        lastPositionMs = positionMs

        val currentWord = currentWordIndex(line, positionMs)

        for (i in line.words.indices) {
            if (states[i].isEmpty()) continue
            val word = line.words[i]
            val charCount = states[i].size
            val isCurrent = i == currentWord

            if (isCurrent) {
                val wasAnchored = anchors[i] >= 0.0
                val expectedPosition = anchors[i] + advances[i]
                val needReanchor =
                    !wasAnchored ||
                        abs(positionMs - expectedPosition) > REANCHOR_TOLERANCE_MS
                if (needReanchor) {
                    anchors[i] = positionMs.toDouble()
                    advances[i] = 0.0
                    for (k in 0 until charCount) {
                        val phases = AppleEmphasizeMotion.phasesAt(
                            word = word,
                            positionMs = positionMs,
                            charIndex = k,
                            charCount = charCount
                        )
                        bumpPhases[i][k] = phases.bumpPhase
                        floatPhases[i][k] = phases.floatPhase
                    }
                }
            }

            if (anchors[i] < 0.0) {
                for (k in 0 until charCount) states[i][k] = AppleEmphasizeState()
                continue
            }

            if (playing) {
                advances[i] += dt * 1000.0
                val bumpStep = dt * 1000.0 / AppleEmphasizeMotion.duMs(word)
                val floatStep =
                    dt * 1000.0 /
                        (AppleEmphasizeMotion.duMs(word) *
                            AppleEmphasizeMotion.FLOAT_DURATION_FACTOR)
                for (k in 0 until charCount) {
                    bumpPhases[i][k] = (bumpPhases[i][k] + bumpStep).coerceAtMost(1.0)
                    floatPhases[i][k] = (floatPhases[i][k] + floatStep).coerceAtMost(1.0)
                }
            }

            var animating = false
            for (k in 0 until charCount) {
                if (bumpPhases[i][k] < 1.0 || floatPhases[i][k] < 1.0) {
                    animating = true
                }
                states[i][k] = AppleEmphasizeMotion.stateFromPhases(
                    word = word,
                    bumpPhase = bumpPhases[i][k],
                    floatPhase = floatPhases[i][k],
                    charIndex = k,
                    charCount = charCount,
                    isLastWord = i == line.words.lastIndex
                )
            }

            if (!animating && !isCurrent) {
                anchors[i] = -1.0
                advances[i] = 0.0
                for (k in 0 until charCount) states[i][k] = AppleEmphasizeState()
            }
        }
    }

    fun statesFor(wordIndex: Int): List<AppleEmphasizeState> =
        states.getOrNull(wordIndex)?.asList() ?: emptyList()

    private fun bind(line: LyricLine, emphasizeFlags: BooleanArray) {
        if (
            boundStartMs == line.startMs &&
            boundText == line.text &&
            states.size == line.words.size
        ) return

        boundStartMs = line.startMs
        boundText = line.text
        anchors = DoubleArray(line.words.size) { -1.0 }
        advances = DoubleArray(line.words.size)
        bumpPhases = Array(line.words.size) { i ->
            val count = if (emphasizeFlags.getOrElse(i) { false }) {
                line.words[i].text.codePointCount(0, line.words[i].text.length)
            } else 0
            DoubleArray(count) { 1.0 }
        }
        floatPhases = Array(line.words.size) { i ->
            DoubleArray(bumpPhases[i].size) { 1.0 }
        }
        states = Array(line.words.size) { i ->
            Array(bumpPhases[i].size) { AppleEmphasizeState() }
        }
        lastPositionMs = Long.MIN_VALUE
    }

    private fun resetWaveState() {
        for (i in anchors.indices) {
            anchors[i] = -1.0
            advances[i] = 0.0
            for (k in bumpPhases[i].indices) {
                bumpPhases[i][k] = 1.0
                floatPhases[i][k] = 1.0
                states[i][k] = AppleEmphasizeState()
            }
        }
    }

    private fun currentWordIndex(line: LyricLine, positionMs: Long): Int {
        for (i in line.words.indices) {
            val word = line.words[i]
            if (positionMs >= word.startMs && positionMs < word.endMs) return i
            if (
                positionMs >= word.endMs &&
                (i == line.words.lastIndex || positionMs < line.words[i + 1].startMs)
            ) return i
        }
        return if (positionMs < line.words.first().startMs) 0 else line.words.lastIndex
    }
}
