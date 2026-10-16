package com.novemberraining.psalyric

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    companion object {
        private const val REQ_CAPTURE = 41
        private const val REQ_STORAGE = 42
    }

    private lateinit var root: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        LyricsEngine.start(this)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFF07111F.toInt())
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(44))
        }
        scroll.addView(root)
        setContentView(scroll)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) rebuild()
    }

    private fun rebuild() {
        root.removeAllViews()
        title("PSA 歌词")
        note("Android 9 / ARM64 车机。CarLife 只负责导航，本应用读取当前活跃本地播放器并覆盖右侧歌词卡片。")

        section("权限")
        button(if (Settings.canDrawOverlays(this)) "悬浮窗权限：已授权" else "授权悬浮窗") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("音乐读取权限（通知使用权）") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        button(if (ForegroundAppDetector.hasUsageAccess(this)) "使用情况访问：已授权" else "授权使用情况访问") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        button("授权本地歌词目录读取") {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
        }

        section("悬浮歌词")
        toggle("启用 PSA 歌词服务", Prefs.enabled(this)) {
            Prefs.setEnabled(this, it)
            if (it && Settings.canDrawOverlays(this)) OverlayService.start(this)
            else if (!it) stopService(Intent(this, OverlayService::class.java))
        }
        toggle("回到车机 Launcher 自动切顶部细歌词", Prefs.launcherStrip(this)) {
            Prefs.setLauncherStrip(this, it); refreshService()
        }
        toggle("悬浮窗触摸穿透", Prefs.touchThrough(this)) {
            Prefs.setTouchThrough(this, it); refreshService()
        }
        button("重置 CarLife 右侧全高卡片位置（1373,10 / 535×700）") {
            Prefs.resetGeometry(this); refreshService()
            rebuild()
        }
        seek("横坐标 X", 0, 1920, Prefs.x(this), "") {
            Prefs.setGeometry(this, it, Prefs.y(this), Prefs.width(this), Prefs.height(this))
        }
        seek("纵坐标 Y", 0, 720, Prefs.y(this), "") {
            Prefs.setGeometry(this, Prefs.x(this), it, Prefs.width(this), Prefs.height(this))
        }
        seek("卡片宽度", 260, 900, Prefs.width(this), " px") {
            Prefs.setGeometry(this, Prefs.x(this), Prefs.y(this), it, Prefs.height(this))
        }
        seek("卡片高度", 300, 720, Prefs.height(this), " px") {
            Prefs.setGeometry(this, Prefs.x(this), Prefs.y(this), Prefs.width(this), it)
        }
        button("应用位置 / 尺寸") { refreshService() }
        note("以上为 1920×720 设计坐标；其它分辨率运行时按比例映射。")

        section("Apple 歌词动效")
        seek("字号", 12, 50, Prefs.fontSize(this), "") { Prefs.setFontSize(this, it) }
        seek("字重", 300, 900, Prefs.fontWeight(this), "") { Prefs.setFontWeight(this, (it / 100) * 100) }
        seekFloat("行间距", 50, 200, (Prefs.lineSpacing(this) * 100).toInt(), "%") {
            Prefs.setLineSpacing(this, it / 100f)
        }
        toggle("对唱优化", Prefs.duet(this)) { Prefs.setDuet(this, it) }
        toggle("动态歌词颜色", Prefs.dynamicColor(this)) { Prefs.setDynamicColor(this, it) }
        toggle("高斯模糊", Prefs.gaussianBlur(this)) { Prefs.setGaussianBlur(this, it) }
        toggle("光辉效果", Prefs.glow(this)) { Prefs.setGlow(this, it) }
        toggle("平衡换行", Prefs.balancedWrap(this)) { Prefs.setBalancedWrap(this, it) }
        note("当前行锚位 0.35、非当前行缩放 0.85、缩放弹簧 2/100/25；滚动弹簧按相邻歌词间隔动态取 110–140，阻尼 √k×2.2。拖动浏览后 5 秒回弹；双击歌词行 seek。")

        section("背景")
        toggle("使用所在屏幕背景高斯模糊（MediaProjection，高级）", Prefs.backgroundMode(this) == "screen") { checked ->
            if (checked) {
                Prefs.setBackgroundMode(this, "screen")
                requestScreenCapture()
            } else {
                Prefs.setBackgroundMode(this, "cover")
                Prefs.setScreenCaptureEnabled(this, false)
                ScreenCaptureController.stop()
                refreshService()
            }
        }
        note("默认使用当前专辑封面模糊。屏幕捕获模式在 Android 9 需要一次系统录屏授权，默认关闭。")

        section("歌词匹配")
        seek("全局歌词校准", -3000, 3000, Prefs.lyricOffsetMs(this).toInt(), " ms") {
            Prefs.setLyricOffsetMs(this, it.toLong())
        }
        val path = EditText(this).apply {
            hint = "本地歌词目录，例如 /sdcard/Lyrics"
            setText(Prefs.localLyricDir(this@MainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF718096.toInt())
        }
        root.addView(path, LinearLayout.LayoutParams(-1, dp(52)))
        button("保存本地歌词目录") { Prefs.setLocalLyricDir(this, path.text.toString()) }
        note("优先级：本地 LRC/KRC 可转写内容 → 网易云 YRC/LRC → QQ → 酷狗。播放器来源与歌词来源解耦。")

        section("诊断")
        val s = PlaybackStore.snapshot
        note("播放器：${s.packageName.ifBlank { "未检测" }}\n歌曲：${s.title} / ${s.artist}\n歌词源：${LyricStore.document.source.ifBlank { "未匹配" }}\n前台应用：${ForegroundAppDetector.foregroundPackage(this).ifBlank { "未知/未授权" }}")
        button("刷新服务") { refreshService(recreate = true) }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Prefs.setScreenCaptureEnabled(this, true)
                Prefs.setBackgroundMode(this, "screen")
                Prefs.setEnabled(this, true)
                val i = Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_PROJECTION)
                    .putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(OverlayService.EXTRA_DATA, data)
                startForegroundService(i)
            } else {
                Prefs.setScreenCaptureEnabled(this, false)
                Prefs.setBackgroundMode(this, "cover")
            }
            rebuild()
        }
    }

    private fun refreshService(recreate: Boolean = false) {
        if (!Prefs.enabled(this)) return
        if (recreate) stopService(Intent(this, OverlayService::class.java))
        OverlayService.start(this)
    }

    private fun title(value: String) {
        root.addView(TextView(this).apply {
            text = value; textSize = 27f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    private fun section(value: String) {
        root.addView(TextView(this).apply {
            text = value; textSize = 16f; setTextColor(0xFF78C7FF.toInt())
            setPadding(0, dp(24), 0, dp(8))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    private fun note(value: String) {
        root.addView(TextView(this).apply {
            text = value; textSize = 13f; setTextColor(0xFFB6C2D2.toInt())
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(7), 0, dp(7))
        })
    }

    private fun button(label: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
    }

    private fun toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
        root.addView(Switch(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = checked
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, v -> change(v) }
        }, LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun seek(label: String, min: Int, max: Int, value: Int, suffix: String, change: (Int) -> Unit) {
        seekFloat(label, min, max, value, suffix, change)
    }

    private fun seekFloat(label: String, min: Int, max: Int, value: Int, suffix: String, change: (Int) -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val name = TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 14f }
        val current = TextView(this).apply { text = "$value$suffix"; setTextColor(0xFF78C7FF.toInt()); textSize = 13f }
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(current)
        root.addView(row)
        root.addView(SeekBar(this).apply {
            this.max = max - min
            progress = value.coerceIn(min, max) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p
                    current.text = "$v$suffix"
                    if (fromUser) change(v)
                }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(-1, dp(38)))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
