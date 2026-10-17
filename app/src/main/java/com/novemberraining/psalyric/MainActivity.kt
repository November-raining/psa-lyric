package com.novemberraining.psalyric

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    companion object {
        private const val REQ_CAPTURE = 41
    }

    private lateinit var root: LinearLayout
    private var diagnosticView: TextView? = null
    private val diagnosticHandler = Handler(Looper.getMainLooper())
    private val diagnosticTick = object : Runnable {
        override fun run() {
            updateDiagnostics()
            diagnosticHandler.postDelayed(this, 500L)
        }
    }

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
        diagnosticHandler.removeCallbacks(diagnosticTick)
        diagnosticHandler.post(diagnosticTick)
    }

    override fun onPause() {
        diagnosticHandler.removeCallbacks(diagnosticTick)
        super.onPause()
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

        section("悬浮歌词")
        toggle("启用 PSA 歌词服务", Prefs.enabled(this)) {
            Prefs.setEnabled(this, it)
            if (it && Settings.canDrawOverlays(this)) OverlayService.start(this)
            else if (!it) stopService(Intent(this, OverlayService::class.java))
        }
        toggle(
            "仅 CarLife 显示大卡片（其他界面顶部细歌词）",
            Prefs.carLifeOnlyCard(this)
        ) {
            Prefs.setCarLifeOnlyCard(this, it)
            refreshService()
        }
        note(
            "开启：仅 CarLife 已连接手机且处于前台时显示右侧大卡片；" +
                "CarLife 未连接、Launcher、设置及其它车机界面都显示顶部细歌词。" +
                "切换由 CarLife 状态桥即时驱动，并有低频 watchdog 自动校正；" +
                "关闭：保持旧逻辑，仅回到车机 Launcher 时切顶部细歌词。"
        )
        toggle("悬浮窗触摸穿透", Prefs.touchThrough(this)) {
            Prefs.setTouchThrough(this, it); refreshService()
        }

        positionPresetChooser()

        seekWithReset(
            "横坐标 X", 0, 1920, Prefs.x(this), "",
            resetValue = Prefs.DEFAULT_X,
            resetLabel = "重置"
        ) {
            Prefs.setGeometry(this, it, Prefs.y(this), Prefs.width(this), Prefs.height(this))
        }
        seekWithReset(
            "纵坐标 Y", 0, 720, Prefs.y(this), "",
            resetValue = Prefs.DEFAULT_Y,
            resetLabel = "重置"
        ) {
            Prefs.setGeometry(this, Prefs.x(this), it, Prefs.width(this), Prefs.height(this))
        }
        seekWithReset(
            "卡片宽度", 260, 900, Prefs.width(this), " px",
            resetValue = Prefs.DEFAULT_WIDTH,
            resetLabel = "重置"
        ) {
            Prefs.setGeometry(this, Prefs.x(this), Prefs.y(this), it, Prefs.height(this))
        }
        seekWithReset(
            "卡片高度", 300, 720, Prefs.height(this), " px",
            resetValue = Prefs.DEFAULT_HEIGHT,
            resetLabel = "重置"
        ) {
            Prefs.setGeometry(this, Prefs.x(this), Prefs.y(this), Prefs.width(this), it)
        }
        button("应用位置 / 尺寸") { refreshService() }
        note(
            "以上为 1920×720 设计坐标；其它分辨率运行时按比例映射。" +
                "右下预设允许 700 px 卡片从 Y=249 向屏幕下方自然溢出，" +
                "与 CarLife 双卡界面的原音乐卡片裁切方式一致。"
        )

        section("Apple 歌词动效")
        seekWithReset(
            "字号", 12, 50, Prefs.fontSize(this), "",
            resetValue = Prefs.DEFAULT_FONT_SIZE,
            resetLabel = "重置"
        ) { Prefs.setFontSize(this, it) }
        seekWithReset(
            "字重", 300, 900, Prefs.fontWeight(this), "",
            resetValue = Prefs.DEFAULT_FONT_WEIGHT,
            resetLabel = "重置"
        ) { Prefs.setFontWeight(this, (it / 100) * 100) }
        seekWithReset(
            "行间距", 50, 200, (Prefs.lineSpacing(this) * 100).toInt(), "%",
            resetValue = (Prefs.DEFAULT_LINE_SPACING * 100).toInt(),
            resetLabel = "重置"
        ) {
            Prefs.setLineSpacing(this, it / 100f)
        }
        toggle("对唱优化", Prefs.duet(this)) { Prefs.setDuet(this, it) }
        toggle("动态歌词颜色", Prefs.dynamicColor(this)) { Prefs.setDynamicColor(this, it) }
        toggle("高斯模糊", Prefs.gaussianBlur(this)) { Prefs.setGaussianBlur(this, it) }
        seekWithReset(
            "高斯模糊强度",
            50,
            300,
            Prefs.gaussianBlurStrength(this),
            "%",
            resetValue = Prefs.DEFAULT_GAUSSIAN_BLUR_STRENGTH,
            resetLabel = "重置"
        ) { Prefs.setGaussianBlurStrength(this, it) }
        seekWithReset(
            "远行模糊增幅",
            0,
            250,
            Prefs.gaussianBlurDistanceBoost(this),
            "%",
            resetValue = Prefs.DEFAULT_GAUSSIAN_BLUR_DISTANCE_BOOST,
            resetLabel = "重置"
        ) { Prefs.setGaussianBlurDistanceBoost(this, it) }
        toggle("光辉效果", Prefs.glow(this)) { Prefs.setGlow(this, it) }
        seekWithReset(
            "光辉强度",
            80,
            400,
            Prefs.glowIntensity(this),
            "",
            resetValue = Prefs.DEFAULT_GLOW_INTENSITY,
            resetLabel = "重置"
        ) { Prefs.setGlowIntensity(this, it) }
        toggle("平衡换行", Prefs.balancedWrap(this)) { Prefs.setBalancedWrap(this, it) }
        note(
            "动画主体保持 0.1.11：整 word 上浮 / alpha easing / 原光辉；仅叠加已确认的行级移动渐变遮罩。" +
                "当前默认调校：光辉强度 260，高斯模糊强度 200%，远行模糊增幅 100%；" +
                "重置会回到这组已确认的舒适值。当前歌词本身保持清晰。" +
                "非当前行缩到 85%，已播字最大上浮约 3 px；拖动浏览后 5 秒回弹；双击歌词行 seek。"
        )

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
        seekWithReset(
            "全局歌词校准",
            -3000,
            3000,
            Prefs.lyricOffsetMs(this).toInt(),
            " ms",
            resetValue = 0
        ) {
            Prefs.setLyricOffsetMs(this, it.toLong())
        }

        val cacheStats = LyricDiskCache.stats(this)
        note(
            "自动本地缓存：默认启用，无需存储权限。\n" +
                "路径：${LyricDiskCache.directory(this).absolutePath}\n" +
                "当前：${cacheStats.entries} 首 / " +
                String.format(java.util.Locale.ROOT, "%.2f MB", cacheStats.megabytes) +
                "；上限 300 首 / 32 MB，超限按最久未使用自动清理到 240 首 / 24 MB。\n" +
                "任何成功匹配的逐字/逐行歌词都会自动保存；切歌再回来优先直接读本地缓存。"
        )

        toggle("显示歌词副行（翻译 / 罗马音）", Prefs.secondaryLyricEnabled(this)) {
            Prefs.setSecondaryLyricEnabled(this, it)
        }
        toggle(
            "副行显示罗马音 / 发音（关闭 = 翻译）",
            Prefs.secondaryLyricMode(this) == LyricSecondaryRules.MODE_ROMAJI
        ) {
            Prefs.setSecondaryLyricMode(
                this,
                if (it) LyricSecondaryRules.MODE_ROMAJI
                else LyricSecondaryRules.MODE_TRANSLATION
            )
        }
        note(
            "副行采用固定模式，不再自动互相顶替：翻译模式只显示翻译；罗马音模式只显示罗马音/发音；" +
                "所选内容缺失时该句不显示副行。默认开启副行并使用翻译。"
        )
        note("优先级：自动本地缓存 → QQ QRC（优先窗口）→ 酷狗 KRC / 网易云 YRC 并发备份；都没有逐字时才回退 LRC。")

        section("诊断")
        diagnosticView = note("")
        updateDiagnostics()
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

    private fun note(value: String): TextView {
        val view = TextView(this).apply {
            text = value; textSize = 13f; setTextColor(0xFFB6C2D2.toInt())
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(7), 0, dp(7))
        }
        root.addView(view)
        return view
    }

    private fun updateDiagnostics() {
        val s = PlaybackStore.snapshot
        val media = PlaybackStore.diagnostics
        val lyric = LyricsEngine.diagnostics
        val foreground = ForegroundAppDetector.info(this)
        val position = s.estimatedPosition()
        diagnosticView?.text = buildString {
            append("播放器：").append(s.packageName.ifBlank { "未检测" })
            val resolvedDisplayArtist = ArtistSelection.chooseResolved(
                s.artist,
                LyricStore.document.resolvedArtist
            )
            append("\n歌曲：").append(s.title).append(" / ").append(resolvedDisplayArtist)
            if (
                resolvedDisplayArtist.isNotBlank() &&
                s.artist.isNotBlank() &&
                resolvedDisplayArtist != s.artist
            ) {
                append(" [媒体=").append(s.artist).append("]")
            }
            append("\n媒体状态：state=").append(s.state)
                .append(" pos=").append(position)
                .append("ms sessions=").append(media.activeSessionCount)
                .append(" [").append(media.reason).append("]")
            append("\n歌词源：").append(LyricStore.document.source.ifBlank { "未匹配" })
            append("；").append(if (lyric.wordTimed) "逐字" else "逐行")
            append(" lines=").append(lyric.lineCount)
                .append(" words=").append(lyric.wordCount)
            val currentDoc = LyricStore.document
            val duetLeft = currentDoc.lines.count { it.alignment == DuetAlignment.LEFT }
            val duetRight = currentDoc.lines.count { it.alignment == DuetAlignment.RIGHT }
            val duetCenter = currentDoc.lines.count { it.alignment == DuetAlignment.CENTER }
            append("\n对唱布局：左=").append(duetLeft)
                .append(" 右=").append(duetRight)
                .append(" 中=").append(duetCenter)
                .append(" 开关=").append(if (Prefs.duet(this@MainActivity)) "ON" else "OFF")
            append("\n副行数据：翻译=").append(lyric.translationLineCount)
                .append("/").append(lyric.lineCount)
                .append(" 罗马音=").append(lyric.romajiLineCount)
                .append("/").append(lyric.lineCount)
                .append(" 最低完整=").append(
                    LyricSecondaryRules.minimumUsefulLineCount(currentDoc)
                )
                .append(" 当前=")
                .append(
                    if (!Prefs.secondaryLyricEnabled(this@MainActivity)) "关闭"
                    else Prefs.secondaryLyricMode(this@MainActivity)
                )
            append("\n匹配状态：").append(lyric.state)
                .append(" elapsed=").append(lyric.elapsedMs).append("ms")
            if (lyric.attempts.isNotBlank()) {
                append("\n匹配链：").append(lyric.attempts)
            }
            val bridge = CarLifeBridgeStore.snapshot
            val bridgeAge = if (bridge.receivedElapsedMs > 0L) {
                (android.os.SystemClock.elapsedRealtime() - bridge.receivedElapsedMs)
                    .coerceAtLeast(0L)
            } else {
                -1L
            }
            append("\nCarLife桥：foreground=").append(bridge.foreground)
                .append(" connected=").append(bridge.connected)
                .append(" age=").append(bridgeAge).append("ms")
            append("\n前台应用：")
            when {
                !foreground.usageAccess -> append("未授权 [usage=NO]")
                foreground.packageName.isNotBlank() -> {
                    append(foreground.packageName)
                    append(" [usage=YES source=").append(foreground.source)
                    if (foreground.ageMs >= 0L) append(" age=").append(foreground.ageMs).append("ms")
                    append("]")
                }
                else -> append("已授权但暂无事件 [usage=YES source=")
                    .append(foreground.source).append("]")
            }
        }
    }

    private fun positionPresetChooser() {
        val selected = Prefs.positionPreset(this)
        val label = TextView(this).apply {
            text = "初始位置预设"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(label)

        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val lowerRight = RadioButton(this).apply {
            id = View.generateViewId()
            text = "右下双卡（默认）"
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        val fullHeight = RadioButton(this).apply {
            id = View.generateViewId()
            text = "右侧全高（旧）"
            textSize = 14f
            setTextColor(Color.WHITE)
        }

        group.addView(
            lowerRight,
            RadioGroup.LayoutParams(0, dp(48), 1f)
        )
        group.addView(
            fullHeight,
            RadioGroup.LayoutParams(0, dp(48), 1f)
        )

        when (selected) {
            PsaPreset.PRESET_LOWER_RIGHT -> group.check(lowerRight.id)
            PsaPreset.PRESET_FULL_HEIGHT -> group.check(fullHeight.id)
        }

        group.setOnCheckedChangeListener { _, checkedId ->
            val preset = when (checkedId) {
                lowerRight.id -> PsaPreset.PRESET_LOWER_RIGHT
                fullHeight.id -> PsaPreset.PRESET_FULL_HEIGHT
                else -> return@setOnCheckedChangeListener
            }
            if (Prefs.positionPreset(this) == preset) return@setOnCheckedChangeListener
            Prefs.setPositionPreset(this, preset)
            refreshService()
            rebuild()
        }

        root.addView(group)
        val current = when (selected) {
            PsaPreset.PRESET_FULL_HEIGHT -> "右侧全高：1373 / 10 / 535×700"
            PsaPreset.PRESET_CUSTOM -> "自定义：${Prefs.x(this)} / ${Prefs.y(this)} / ${Prefs.width(this)}×${Prefs.height(this)}"
            else -> "右下双卡：1373 / 249 / 535×700（下方允许溢出）"
        }
        note(
            "当前：$current。手动调整下方 X/Y/宽高后会进入自定义；" +
                "再次选择任一预设即可恢复对应的像素基准。"
        )
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

    private fun seekWithReset(
        label: String,
        min: Int,
        max: Int,
        value: Int,
        suffix: String,
        resetValue: Int,
        resetLabel: String = "归零",
        change: (Int) -> Unit
    ) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val name = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val current = TextView(this).apply {
            text = "$value$suffix"
            setTextColor(0xFF78C7FF.toInt())
            textSize = 13f
        }
        val reset = TextView(this).apply {
            text = resetLabel
            setTextColor(0xFF78C7FF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(4), 0, dp(4))
        }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = value.coerceIn(min, max) - min
        }

        reset.setOnClickListener {
            val target = resetValue.coerceIn(min, max)
            bar.progress = target - min
            current.text = "$target$suffix"
            change(target)
        }

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {
                val v = min + p
                current.text = "$v$suffix"
                if (fromUser) change(v)
            }
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) {}
        })

        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(current)
        row.addView(reset)
        root.addView(row)
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(38)))
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
