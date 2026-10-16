package com.novemberraining.psalyric

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.min

class TopLyricStripView(context: Context) : View(context), Choreographer.FrameCallback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastIndex = -1
    private var changeAt = 0L

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        invalidate()
        if (isAttachedToWindow) Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        val doc = LyricStore.document
        val pos = PlaybackStore.snapshot.estimatedPosition() + Prefs.lyricOffsetMs(context)
        val index = doc.currentIndex(pos)
        if (index < 0) return
        if (index != lastIndex) {
            lastIndex = index
            changeAt = SystemClock.elapsedRealtime()
        }
        val line = doc.lines[index]
        val elapsed = (SystemClock.elapsedRealtime() - changeAt).coerceAtLeast(0L)
        val t = min(1f, elapsed / 260f)
        val eased = 1f - (1f - t) * (1f - t)
        val y = height * 0.70f + (1f - eased) * height * 0.28f

        paint.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        paint.textSize = height * 0.52f
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.alpha = (255 * eased).toInt()
        paint.setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())

        val text = line.text
        val x = width / 2f
        if (line.words.isEmpty()) {
            canvas.drawText(text, x, y, paint)
        } else {
            paint.alpha = (255 * eased * 0.25f).toInt()
            canvas.drawText(text, x, y, paint)
            val ratio = timedProgress(line, pos)
            val w = paint.measureText(text)
            canvas.save()
            canvas.clipRect(x - w/2f, 0f, x - w/2f + w * ratio, height.toFloat())
            paint.alpha = (255 * eased).toInt()
            canvas.drawText(text, x, y, paint)
            canvas.restore()
        }
        paint.clearShadowLayer()
    }

    private fun timedProgress(line: LyricLine, pos: Long): Float {
        var done = 0f
        var total = 0f
        for (w in line.words) {
            val weight = w.text.codePointCount(0, w.text.length).coerceAtLeast(1).toFloat()
            total += weight
            done += when {
                pos >= w.endMs -> weight
                pos <= w.startMs -> 0f
                else -> weight * (pos - w.startMs).toFloat() / w.durationMs.coerceAtLeast(1L)
            }
        }
        return if (total <= 0f) 0f else (done / total).coerceIn(0f, 1f)
    }
}
