package com.novemberraining.psalyric

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import java.util.concurrent.Executors

object ArtworkBackdrop {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile var blurred: Bitmap? = null
    @Volatile var accent: Int = Color.WHITE
    @Volatile private var source: Bitmap? = null

    fun request(context: Context, bitmap: Bitmap?) {
        if (bitmap == null || bitmap === source) return
        source = bitmap
        worker.execute {
            try {
                accent = extractAccent(bitmap)
                blurred = blur(context.applicationContext, bitmap, 160, 210)
            } catch (_: Throwable) {
                blurred = Bitmap.createScaledBitmap(bitmap, 80, 105, true)
            }
        }
    }

    fun blur(context: Context, bitmap: Bitmap, width: Int, height: Int): Bitmap {
        val input = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val rs = RenderScript.create(context)
        val inAlloc = Allocation.createFromBitmap(rs, input)
        val outAlloc = Allocation.createTyped(rs, inAlloc.type)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        script.setRadius(25f)
        script.setInput(inAlloc)
        script.forEach(outAlloc)
        outAlloc.copyTo(output)
        script.destroy()
        inAlloc.destroy()
        outAlloc.destroy()
        rs.destroy()
        if (input !== bitmap && !input.isRecycled) input.recycle()
        return output
    }

    private fun extractAccent(bitmap: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var weight = 0L
        for (y in 0 until sample.height) for (x in 0 until sample.width) {
            val c = sample.getPixel(x, y)
            val max = maxOf(Color.red(c), Color.green(c), Color.blue(c))
            val min = minOf(Color.red(c), Color.green(c), Color.blue(c))
            val sat = (max - min).coerceAtLeast(12)
            sr += Color.red(c) * sat
            sg += Color.green(c) * sat
            sb += Color.blue(c) * sat
            weight += sat
        }
        if (sample !== bitmap) sample.recycle()
        if (weight <= 0) return Color.WHITE
        return Color.rgb((sr / weight).toInt(), (sg / weight).toInt(), (sb / weight).toInt())
    }
}

object ScreenBackdrop {
    @Volatile var blurred: Bitmap? = null
}
