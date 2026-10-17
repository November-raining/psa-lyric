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
        private const val LEGACY_VISIBILITY_POLL_MS = 500L
        private const val BRIDGE_WATCHDOG_MS = 3000L
        private const val BRIDGE_STALE_MS = 6500L

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
    private lateinit var carLifeBridge: CarLifeStateBridge
    private var homes: Set<String> = emptySet()

    private val visibilityTick = object : Runnable {
        override fun run() {
            if (Prefs.carLifeOnlyCard(this@OverlayService)) {
                // Event broadcasts are the primary path. This low-rate request is
                // only a watchdog for a lost process/broadcast or service restart.
                carLifeBridge.requestState()
            }
            syncVisibility()
            handler.postDelayed(
                this,
                if (Prefs.carLifeOnlyCard(this@OverlayService)) {
                    BRIDGE_WATCHDOG_MS
                } else {
                    LEGACY_VISIBILITY_POLL_MS
                }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIFICATION, notification())
        homes = ForegroundAppDetector.homePackages(this)
        LyricsEngine.start(this)
        carLifeBridge = CarLifeStateBridge(this) {
            // Normal CarLife foreground/connection transitions arrive here
            // immediately, so CARD <-> TOP_STRIP no longer waits for polling.
            syncVisibility()
        }
        carLifeBridge.start()
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
        if (::carLifeBridge.isInitialized && Prefs.carLifeOnlyCard(this)) {
            carLifeBridge.requestState()
        }
        syncVisibility()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(visibilityTick)
        if (::carLifeBridge.isInitialized) carLifeBridge.stop()
        remove(main)
        remove(strip)
        main = null
        strip = null
        ScreenCaptureController.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureWindows(foregroundPackage: String = "") {
        if (main == null) {
            val displaySize = overlayDisplaySize(foregroundPackage)
            val g = PsaPreset.scale(
                Prefs.x(this), Prefs.y(this), Prefs.width(this), Prefs.height(this),
                displaySize.width, displaySize.height
            )
            val view = AppleLyricView(this)
            val p = WindowManager.LayoutParams(
                g.width, g.height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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

    private fun applyMainGeometry(foregroundPackage: String = "") {
        val p = mainParams ?: return
        val displaySize = overlayDisplaySize(foregroundPackage)
        val g = PsaPreset.scale(
            Prefs.x(this), Prefs.y(this), Prefs.width(this), Prefs.height(this),
            displaySize.width, displaySize.height
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

        val carLifeOnly = Prefs.carLifeOnlyCard(this)
        val now = android.os.SystemClock.elapsedRealtime()

        val foreground: String
        val connected: Boolean

        if (carLifeOnly) {
            val bridge = if (::carLifeBridge.isInitialized) {
                carLifeBridge.snapshot()
            } else {
                CarLifeBridgeSnapshot()
            }
            val fresh = bridge.isFresh(now, BRIDGE_STALE_MS)

            // Fail closed when the bridge is stale: the large card is allowed
            // only when CarLife has positively reported both foreground=true
            // and connected=true. The watchdog will request a fresh state.
            foreground = if (fresh && bridge.foreground) {
                OverlayVisibilityPolicy.CARLIFE_HU_PACKAGE
            } else {
                ""
            }
            connected = fresh && bridge.connected
        } else {
            // Compatibility mode keeps the exact pre-bridge behavior.
            foreground = ForegroundAppDetector.foregroundPackage(this)
            connected = true
        }

        ensureWindows(foreground)

        // On this PSA ROM, WindowManager.getSize() keeps reporting the
        // application/usable width that excludes the OEM left navigation rail.
        // When the event bridge says connected CarLife is really foreground,
        // getRealSize() maps the preset against the physical 1920×720 panel.
        if (!carLifeOnly || (foreground == OverlayVisibilityPolicy.CARLIFE_HU_PACKAGE && connected)) {
            applyMainGeometry(foreground)
        }
        syncFlags()

        val presentation = OverlayVisibilityPolicy.presentation(
            carLifeOnlyCard = carLifeOnly,
            foregroundPackage = foreground,
            carLifeConnected = connected,
            homePackages = homes
        )
        val showCard = presentation == OverlayPresentation.CARD
        main?.visibility = if (showCard) android.view.View.VISIBLE else android.view.View.GONE
        strip?.visibility = if (showCard) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun overlayDisplaySize(foregroundPackage: String): OverlayDisplaySize {
        val app = Point().also { wm.defaultDisplay.getSize(it) }
        val real = Point().also {
            try {
                wm.defaultDisplay.getRealSize(it)
            } catch (_: Throwable) {
                it.set(app.x, app.y)
            }
        }
        return OverlayDisplaySpace.choose(
            appWidth = app.x,
            appHeight = app.y,
            realWidth = real.x,
            realHeight = real.y,
            carLifeForeground =
                foregroundPackage == OverlayVisibilityPolicy.CARLIFE_HU_PACKAGE
        )
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
