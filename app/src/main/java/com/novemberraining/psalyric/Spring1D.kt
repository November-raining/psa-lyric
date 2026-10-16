package com.novemberraining.psalyric

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AMLL/md3Music-style analytic spring.
 *
 * Unlike explicit Euler integration, this solves the damped spring equation from
 * the last retarget point. The result is stable across uneven Android frame times
 * and preserves the same motion when a frame is delayed.
 */
class Spring1D(
    position: Float = 0f,
    velocity: Float = 0f,
    target: Float = 0f,
    mass: Float = 1f,
    stiffness: Float = 120f,
    damping: Float = 24f
) {
    private var _position = position
    private var _velocity = velocity
    private var _target = target
    private var _mass = mass
    private var _stiffness = stiffness
    private var _damping = damping

    private var fromPosition = position
    private var fromVelocity = velocity
    private var elapsed = 0f
    private var settled = abs(position - target) < SETTLE_THRESHOLD &&
        abs(velocity) < SETTLE_THRESHOLD

    var position: Float
        get() = _position
        set(value) {
            _position = value
            _target = value
            _velocity = 0f
            fromPosition = value
            fromVelocity = 0f
            elapsed = 0f
            settled = true
        }

    var velocity: Float
        get() = _velocity
        set(value) {
            if (abs(value - _velocity) < 1e-6f) return
            _velocity = value
            resetSolver()
        }

    var target: Float
        get() = _target
        set(value) = retarget(value)

    var mass: Float
        get() = _mass
        set(value) {
            val next = value.coerceAtLeast(0.0001f)
            if (abs(next - _mass) < 1e-6f) return
            _mass = next
            if (!settled) resetSolver()
        }

    var stiffness: Float
        get() = _stiffness
        set(value) {
            val next = value.coerceAtLeast(0f)
            if (abs(next - _stiffness) < 1e-6f) return
            _stiffness = next
            if (!settled) resetSolver()
        }

    var damping: Float
        get() = _damping
        set(value) {
            val next = value.coerceAtLeast(0f)
            if (abs(next - _damping) < 1e-6f) return
            _damping = next
            if (!settled) resetSolver()
        }

    fun snap(value: Float) {
        _position = value
        _target = value
        _velocity = 0f
        fromPosition = value
        fromVelocity = 0f
        elapsed = 0f
        settled = true
    }

    fun retarget(value: Float) {
        if (abs(value - _target) < 1e-6f && settled) return
        if (abs(value - _target) < 1e-6f && !settled) return
        _target = value
        resetSolver()
    }

    fun step(dtSeconds: Float): Boolean {
        if (settled) return false
        if (dtSeconds <= 0f) return true

        var remaining = dtSeconds.coerceAtMost(MAX_FRAME_DT)
        while (remaining > 0f && !settled) {
            val step = minOf(remaining, MAX_STEP)
            elapsed += step
            solveAt(elapsed)
            remaining -= step

            if (abs(_position - _target) < SETTLE_THRESHOLD &&
                abs(_velocity) < SETTLE_THRESHOLD &&
                abs(acceleration()) < SETTLE_THRESHOLD
            ) {
                _position = _target
                _velocity = 0f
                settled = true
            }
        }
        return !settled
    }

    fun isSettled(): Boolean =
        settled &&
            abs(_position - _target) < SETTLE_THRESHOLD &&
            abs(_velocity) < SETTLE_THRESHOLD &&
            abs(acceleration()) < SETTLE_THRESHOLD

    private fun resetSolver() {
        fromPosition = _position
        fromVelocity = _velocity
        elapsed = 0f
        settled = false
    }

    private fun acceleration(): Float {
        val displacement = _position - _target
        return (-_stiffness * displacement - _damping * _velocity) / _mass
    }

    private fun solveAt(t: Float) {
        val from = fromPosition.toDouble()
        val vel = fromVelocity.toDouble()
        val to = _target.toDouble()
        val m = _mass.toDouble()
        val c = _damping.toDouble()
        val k = _stiffness.toDouble()
        val delta = to - from

        if (abs(delta) < 1e-12 && abs(vel) < 1e-12) {
            _position = _target
            _velocity = 0f
            return
        }

        val discriminant = 4.0 * m * k - c * c
        val td = t.toDouble()

        when {
            abs(discriminant) < 1e-9 -> {
                val af = sqrt(k / m)
                val dm = -af
                val leftover = -dm * delta - vel
                val e = exp(dm * td)
                _position = (to - (delta + td * leftover) * e).toFloat()
                _velocity = (-e * (leftover + (delta + td * leftover) * dm)).toFloat()
            }

            discriminant > 0.0 -> {
                val dampingFrequency = sqrt(discriminant)
                val dfm = 0.5 * dampingFrequency / m
                val dm = -0.5 * c / m
                val leftover = (c * delta - 2.0 * m * vel) / dampingFrequency
                val e = exp(dm * td)
                val cosT = cos(dfm * td)
                val sinT = sin(dfm * td)
                _position = (to - e * (delta * cosT + leftover * sinT)).toFloat()
                _velocity = (-e * (
                    dm * (delta * cosT + leftover * sinT) +
                        dfm * (-delta * sinT + leftover * cosT)
                    )).toFloat()
            }

            else -> {
                val dampingFrequency = sqrt(-discriminant)
                val dfm = 0.5 * dampingFrequency / m
                val dm = -0.5 * c / m
                val r1 = dm + dfm
                val r2 = dm - dfm
                val a = (vel + r2 * delta) / (2.0 * dfm)
                val b = -delta - a
                val er1 = exp(r1 * td)
                val er2 = exp(r2 * td)
                _position = (to + a * er1 + b * er2).toFloat()
                _velocity = (a * r1 * er1 + b * r2 * er2).toFloat()
            }
        }
    }

    companion object {
        private const val SETTLE_THRESHOLD = 0.01f
        private const val MAX_STEP = 0.016f
        private const val MAX_FRAME_DT = 1f / 15f
    }
}

object AppleMotion {
    const val ACTIVE_SCALE = 1f
    const val INACTIVE_SCALE = 0.85f
    const val SCALE_MASS = 2f
    const val SCALE_STIFFNESS = 100f
    const val SCALE_DAMPING = 25f

    fun scrollStiffness(intervalMs: Long): Float {
        val clamped = intervalMs.coerceIn(100L, 800L).toDouble()
        val ratio = (1.0 - (clamped - 100.0) / 700.0).pow(0.2)
        return (110.0 + ratio * 30.0).toFloat()
    }

    fun scrollDamping(stiffness: Float): Float =
        (sqrt(stiffness.toDouble()) * 2.2).toFloat()
}
