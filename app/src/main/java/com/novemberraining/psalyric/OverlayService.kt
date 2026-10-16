package com.novemberraining.psalyric

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

class OverlayService : Service() {
    companion object {
        const val ACTION_REFRESH = "com.novemberraining.psalyric.REFRESH"
        const val ACTION_PROJECTION = "com.novemberraining.psalyric.PROJECTION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        private const val CHANNEL = "psa_lyric"
        private const val NOTIFICATION = 81

        fun start(context: Context) {
            val i = Intent(context, OverlayService::class.java).setAction(ACTION_REFRESH)
            context.startForegroundService(i)
        }
    }

    private lateinit var wm: WindowManager
    private var main: AppleLyricView? = null
    private var mainParams: WindowManager.LayoutParams? = null
    private var strip: TopLyricStripView? = null
    private var stripParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var homes: Set<String> = emptySet()

    private val visibilityTick = object : Runnable {
        override fun run() {
            syncVisibility()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIFICATION, notification())
        homes = ForegroundAppDetector.homePackages(this)
        LyricsEngine.start(this)
        handler.post(visibilityTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PROJECTION) {
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
            if (data != null) {
                ScreenCaptureController.start(this, intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED), data)
            }
        }
        ensureWindows()
        applyMainGeometry()
        syncFlags()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(visibilityTick)
        remove(main)
        remove(strip)
        main = null
        strip = null
        ScreenCaptureController.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureWindows() {
        if (main == null) {
            val displaySize = Point().also { wm.defaultDisplay.getSize(it) }
            val g = PsaPreset.scale(
                Prefs.x(this), Prefs.y(this), Prefs.width(this), Prefs.height(this),
                displaySize.x, displaySize.y
            )
            val view = AppleLyricView(this)
            val p = WindowManager.LayoutParams(
                g.width, g.height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.START
            p.x = g.x; p.y = g.y
            wm.addView(view, p)
            main = view
            mainParams = p
        }
        if (strip == null) {
            val h = statusBarHeight()
            val view = TopLyricStripView(this)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.START
            wm.addView(view, p)
            strip = view
            stripParams = p
        }
    }

    private fun applyMainGeometry() {
        val p = mainParams ?: return
        val displaySize = Point().also { wm.defaultDisplay.getSize(it) }
        val g = PsaPreset.scale(
            Prefs.x(this), Prefs.y(this), Prefs.width(this), Prefs.height(this),
            displaySize.x, displaySize.y
        )
        if (p.x == g.x && p.y == g.y && p.width == g.width && p.height == g.height) return
        p.x = g.x
        p.y = g.y
        p.width = g.width
        p.height = g.height
        try { main?.let { wm.updateViewLayout(it, p) } } catch (_: Throwable) {}
    }

    private fun syncVisibility() {
        if (!Prefs.enabled(this)) return
        ensureWindows()
        syncFlags()
        val foreground = ForegroundAppDetector.foregroundPackage(this)
        val home = Prefs.launcherStrip(this) && foreground.isNotBlank() && foreground in homes
        main?.visibility = if (home) android.view.View.GONE else android.view.View.VISIBLE
        strip?.visibility = if (home) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun syncFlags() {
        val p = mainParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val wanted = if (Prefs.touchThrough(this)) p.flags or flag else p.flags and flag.inv()
        if (wanted != p.flags) {
            p.flags = wanted
            try { main?.let { wm.updateViewLayout(it, p) } } catch (_: Throwable) {}
        }
    }

    private fun remove(view: android.view.View?) {
        if (view?.parent != null) try { wm.removeViewImmediate(view) } catch (_: Throwable) {}
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else (32 * resources.displayMetrics.density).toInt()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "PSA 歌词", NotificationManager.IMPORTANCE_LOW)
        channel.setSound(null, null)
        nm.createNotificationChannel(channel)
    }

    private fun notification(): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PSA 歌词运行中")
            .setContentText("读取当前车机播放器并渲染悬浮歌词")
            .setOngoing(true)
            .build()
}
