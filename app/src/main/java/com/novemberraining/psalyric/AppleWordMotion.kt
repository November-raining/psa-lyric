package com.novemberraining.psalyric

import kotlin.math.abs
import kotlin.math.exp

class AppleWordMotion {
    private var boundStartMs = Long.MIN_VALUE
    private var boundText = ""
    private var alphas = FloatArray(0)
    private var lifts = FloatArray(0)

    var currentWordIndex: Int = -1
        private set
    var intraWordProgress: Float = 0f
        private set

    fun bind(line: LyricLine, scale: Float) {
        if (boundStartMs == line.startMs && boundText == line.text && alphas.size == line.words.size) return
        boundStartMs = line.startMs
        boundText = line.text
        val dark = dynamicDark(scale)
        alphas = FloatArray(line.words.size) { dark }
        lifts = FloatArray(line.words.size)
        currentWordIndex = if (line.words.isEmpty()) -1 else 0
        intraWordProgress = 0f
    }

    fun tick(line: LyricLine, positionMs: Long, scale: Float, active: Boolean, dtSeconds: Float) {
        bind(line, scale)
        if (line.words.isEmpty() || dtSeconds <= 0f) return

        val dt = dtSeconds.coerceIn(0f, 1f / 30f)
        val dark = dynamicDark(scale)
        val bright = dynamicBright(scale)
        val words = line.words

        if (!active) {
            currentWordIndex = -1
            intraWordProgress = 0f
            val alphaAttack = decay(50f, dt)
            val alphaRelease = decay(7f, dt)
            val liftAttack = decay(30f, dt)
            val liftRelease = decay(10f, dt)
            for (i in words.indices) {
                alphas[i] = approach(alphas[i], dark, alphaAttack, alphaRelease, 0.001f)
                lifts[i] = approach(lifts[i], 0f, liftAttack, liftRelease, 0.01f)
            }
            return
        }

        var current = -1
        var progress = 0f
        for (i in words.indices) {
            val w = words[i]
            if (positionMs >= w.startMs && positionMs < w.endMs) {
                current = i
                progress = if (w.durationMs > 0L) {
                    ((positionMs - w.startMs).toFloat() / w.durationMs).coerceIn(0f, 1f)
                } else 0f
                break
            } else if (positionMs >= w.endMs &&
                (i == words.lastIndex || positionMs < words[i + 1].startMs)) {
                current = i
                progress = 1f
            }
        }
        if (current == -1 && positionMs < words.first().startMs) {
            current = 0
            progress = 0f
        }

        currentWordIndex = current
        intraWordProgress = progress

        val alphaAttack = decay(50f, dt)
        val alphaRelease = decay(7f, dt)
        val liftAttack = decay(30f, dt)
        val liftRelease = decay(10f, dt)
        val maxLift = -3f

        for (i in words.indices) {
            val targetAlpha = when {
                current < 0 -> dark
                i < current -> bright
                i > current -> dark
                else -> dark + (bright - dark) * progress
            }
            alphas[i] = approach(alphas[i], targetAlpha, alphaAttack, alphaRelease, 0.001f)

            val targetLift = when {
                current < 0 -> 0f
                i < current -> maxLift
                i > current -> 0f
                else -> maxLift * smoothstep(progress)
            }
            lifts[i] = approach(lifts[i], targetLift, liftAttack, liftRelease, 0.01f)
        }
    }

    fun alphaAt(index: Int): Float = alphas.getOrElse(index) { 1f }
    fun liftAt(index: Int): Float = lifts.getOrElse(index) { 0f }

    fun maskFraction(line: LyricLine): Float {
        if (line.words.isEmpty()) return 1f
        if (currentWordIndex < 0) return 0f
        var done = 0f
        var total = 0f
        for ((i, word) in line.words.withIndex()) {
            val weight = word.text.codePointCount(0, word.text.length).coerceAtLeast(1).toFloat()
            total += weight
            done += when {
                i < currentWordIndex -> weight
                i == currentWordIndex -> weight * intraWordProgress
                else -> 0f
            }
        }
        return if (total <= 0f) 0f else (done / total).coerceIn(0f, 1f)
    }

    companion object {
        fun dynamicDark(scale: Float): Float {
            val factor = ((scale - AppleMotion.INACTIVE_SCALE) /
                (AppleMotion.ACTIVE_SCALE - AppleMotion.INACTIVE_SCALE)).coerceIn(0f, 1f)
            return factor * 0.2f + 0.2f
        }

        fun dynamicBright(scale: Float): Float {
            val factor = ((scale - AppleMotion.INACTIVE_SCALE) /
                (AppleMotion.ACTIVE_SCALE - AppleMotion.INACTIVE_SCALE)).coerceIn(0f, 1f)
            return factor * 0.8f + 0.2f
        }

        private fun decay(speed: Float, dt: Float): Float =
            (1.0 - exp((-speed * dt).toDouble())).toFloat()

        private fun smoothstep(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }

        private fun approach(
            current: Float,
            target: Float,
            attackDecay: Float,
            releaseDecay: Float,
            epsilon: Float
        ): Float {
            if (abs(current - target) < epsilon) return target
            val decay = if (target >= current) attackDecay else releaseDecay
            val next = current + (target - current) * decay
            return if (abs(next - target) < epsilon) target else next
        }
    }
}
