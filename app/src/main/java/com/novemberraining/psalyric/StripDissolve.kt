package com.novemberraining.psalyric

import kotlin.math.max
import kotlin.math.min

class StripDissolve {
    data class Particle(
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        val radius: Float,
        val bornMs: Long,
        val color: Int
    )

    private var previous = ""
    private var startedAtMs = 0L
    private var frozenAtMs = -1L
    private val emitted = HashSet<Int>()
    private val particles = ArrayList<Particle>()

    val previousText: String get() = previous
    fun active(nowMs: Long): Boolean =
        previous.isNotEmpty() && clock(nowMs) - startedAtMs < DURATION_MS + DUST_LIFE_MS

    fun begin(text: String, nowMs: Long) {
        previous = text
        startedAtMs = nowMs
        emitted.clear()
        particles.clear()
        frozenAtMs = -1L
    }

    fun setFrozen(frozen: Boolean, nowMs: Long) {
        if (frozen) {
            if (frozenAtMs < 0L) frozenAtMs = nowMs
        } else if (frozenAtMs >= 0L) {
            val delta = nowMs - frozenAtMs
            startedAtMs += delta
            for (i in particles.indices) {
                val p = particles[i]
                particles[i] = p.copy(bornMs = p.bornMs + delta)
            }
            frozenAtMs = -1L
        }
    }

    private fun clock(nowMs: Long): Long = if (frozenAtMs >= 0L) frozenAtMs else nowMs

    fun clear() {
        previous = ""
        emitted.clear()
        particles.clear()
        frozenAtMs = -1L
    }

    fun characterAlpha(index: Int, count: Int, nowMs: Long): Float {
        if (previous.isEmpty() || count <= 0) return 0f
        val p = ((clock(nowMs) - startedAtMs).toFloat() / DURATION_MS).coerceIn(0f, 1f)
        val step = 1f / (count + OVERLAP - 1f)
        val fade = OVERLAP * step
        val local = ((p - index * step) / fade).coerceIn(0f, 1f)
        return 1f - local
    }

    fun hasVisibleCharacter(count: Int, nowMs: Long): Boolean {
        for (i in 0 until count) if (characterAlpha(i, count, nowMs) > VISIBLE_ALPHA) return true
        return false
    }

    fun emit(
        charIndex: Int,
        charCount: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        textSize: Float,
        color: Int,
        nowMs: Long
    ) {
        if (!emitted.add(charIndex)) return
        if (characterAlpha(charIndex, charCount, nowMs) >= 0.999f) {
            emitted.remove(charIndex)
            return
        }
        val perChar = (192 / max(1, charCount)).coerceIn(2, 10)
        var seed = (charIndex + 1L) * 0x9E3779B97F4A7C15UL.toLong()
        repeat(perChar) { n ->
            seed = seed xor (seed ushr 30)
            seed *= 0xBF58476D1CE4E5B9UL.toLong()
            seed = seed xor (seed ushr 27)
            seed *= 0x94D049BB133111EBUL.toLong()
            seed = seed xor (seed ushr 31)
            fun unit(shift: Int): Float = ((seed ushr shift) and 0xFFFF).toFloat() / 65535f
            val ux = unit(0)
            val uy = unit(16)
            val ud = unit(32)
            val ur = unit(8)
            val gx = left + width * ux
            val gy = top + height * uy
            particles += Particle(
                x = gx,
                y = gy,
                vx = (ud - 0.5f) * textSize * 0.55f,
                vy = -textSize * (0.08f + unit(24) * 0.28f),
                radius = max(0.6f, textSize * (0.025f + ur * 0.035f)),
                bornMs = nowMs + n * 7L,
                color = color
            )
        }
        if (particles.size > 384) particles.subList(0, particles.size - 384).clear()
    }

    fun liveParticles(nowMs: Long): List<Particle> {
        val clock = clock(nowMs)
        particles.removeAll { clock - it.bornMs >= DUST_LIFE_MS }
        return particles
    }

    fun particleProgress(particle: Particle, nowMs: Long): Float =
        ((clock(nowMs) - particle.bornMs).toFloat() / DUST_LIFE_MS).coerceIn(0f, 1f)

    fun particleX(p: Particle, nowMs: Long): Float {
        val t = particleProgress(p, nowMs)
        return p.x + p.vx * t
    }

    fun particleY(p: Particle, nowMs: Long): Float {
        val t = particleProgress(p, nowMs)
        return p.y + p.vy * t + 0.30f * t * t * 24f
    }

    fun particleRadius(p: Particle, nowMs: Long): Float =
        p.radius * (1f - particleProgress(p, nowMs) * 0.55f)

    fun particleAlpha(p: Particle, nowMs: Long): Int =
        (210f * (1f - particleProgress(p, nowMs))).toInt().coerceIn(0, 210)

    companion object {
        const val DURATION_MS = 600L
        const val DUST_LIFE_MS = 520L
        const val VISIBLE_ALPHA = 0.006f
        private const val OVERLAP = 2.5f
    }
}
