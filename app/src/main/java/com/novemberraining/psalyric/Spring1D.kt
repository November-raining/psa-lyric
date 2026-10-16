package com.novemberraining.psalyric

import kotlin.math.abs

class Spring1D(
    var position: Float = 0f,
    var velocity: Float = 0f,
    var target: Float = 0f,
    var mass: Float = 1f,
    var stiffness: Float = 120f,
    var damping: Float = 24f
) {
    fun snap(value: Float) {
        position = value
        target = value
        velocity = 0f
    }

    fun setTarget(value: Float) {
        target = value
    }

    fun step(dtSeconds: Float): Boolean {
        val dt = dtSeconds.coerceIn(0f, 1f / 30f)
        if (dt <= 0f) return !isSettled()
        val displacement = position - target
        val acceleration = (-stiffness * displacement - damping * velocity) / mass
        velocity += acceleration * dt
        position += velocity * dt
        if (isSettled()) {
            position = target
            velocity = 0f
            return false
        }
        return true
    }

    fun isSettled(): Boolean =
        abs(position - target) < 0.05f && abs(velocity) < 0.05f
}

object AppleMotion {
    const val ACTIVE_SCALE = 1f
    const val INACTIVE_SCALE = 0.85f
    const val SCALE_MASS = 2f
    const val SCALE_STIFFNESS = 100f
    const val SCALE_DAMPING = 25f

    fun scrollStiffness(intervalMs: Long): Float {
        val clamped = intervalMs.coerceIn(100L, 800L).toDouble()
        val ratio = Math.pow(1.0 - (clamped - 100.0) / 700.0, 0.2)
        return (110.0 + ratio * 30.0).toFloat()
    }

    fun scrollDamping(stiffness: Float): Float =
        (kotlin.math.sqrt(stiffness.toDouble()) * 2.2).toFloat()
}
