package com.novemberraining.psalyric

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

object ScreenCaptureController {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var lastSample = 0L

    @Synchronized
    fun start(context: Context, resultCode: Int, data: Intent) {
        stop()
        if (resultCode != Activity.RESULT_OK) return
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = manager.getMediaProjection(resultCode, data) ?: return
        val width = 480
        val height = 180
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val bg = HandlerThread("psa-screen-capture").also { it.start() }
        val handler = Handler(bg.looper)
        imageReader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSample < 1500L) return@setOnImageAvailableListener
                lastSample = now
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width
                val raw = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                raw.copyPixelsFromBuffer(plane.buffer)
                val crop = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
                if (raw !== crop) raw.recycle()
                ScreenBackdrop.blurred = try {
                    ArtworkBackdrop.blur(context.applicationContext, crop, 240, 90)
                } finally {
                    if (!crop.isRecycled) crop.recycle()
                }
            } catch (_: Throwable) {
            } finally {
                image.close()
            }
        }, handler)
        val vd = mp.createVirtualDisplay(
            "PSA Lyric backdrop",
            width, height,
            context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null, handler
        )
        projection = mp
        display = vd
        reader = imageReader
        thread = bg
    }

    @Synchronized
    fun stop() {
        try { display?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        try { thread?.quitSafely() } catch (_: Throwable) {}
        display = null
        reader = null
        projection = null
        thread = null
        ScreenBackdrop.blurred = null
    }
}
