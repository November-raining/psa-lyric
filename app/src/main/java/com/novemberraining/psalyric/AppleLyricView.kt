package com.novemberraining.psalyric

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class AppleLyricView(context: Context) : View(context), Choreographer.FrameCallback {
    private data class VisualLine(
        val index: Int,
        val rows: List<String>,
        val top: Float,
        val height: Float
    )

    private data class RowSlice(
        val text: String,
        val start: Int,
        val end: Int,
        val x: Float,
        val baseline: Float
    )

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scroll = Spring1D()
    private val scales = HashMap<Int, Spring1D>()
    private val wordMotions = HashMap<Int, AppleWordMotion>()
    private val cascadeSprings = HashMap<Int, Spring1D>()
    private val cascadeStartTimes = HashMap<Int, Long>()
    private var cascadePosY = 0f
    private var frameDt = 1f / 60f
    private var blurFade = 1f
    private var glowThresholdMs = 500
    private val buttonScales = Array(3) {
        Spring1D(position = 1f, target = 1f, mass = 1f, stiffness = 180f, damping = 18f)
    }

    private var visual = emptyList<VisualLine>()
    private var cachedDoc: LyricDocument? = null
    private var cachedWidth = -1
    private var cachedFont = -1f
    private var currentIndex = -1
    private var lastFrameNs = 0L
    private var firstLayout = true
    private var browsing = false
    private var autoReturnAt = 0L
    private var lastY = 0f
    private var downY = 0f
    private var downX = 0f
    private var lastTapAt = 0L
    private var velocity: VelocityTracker? = null

    private val lyricTop get() = height * 0.19f
    private val lyricBottom get() = height * 0.82f
    private val lyricHeight get() = lyricBottom - lyricTop

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        val dt = if (lastFrameNs == 0L) 1f / 60f
        else ((frameTimeNanos - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 1f / 30f)
        lastFrameNs = frameTimeNanos
        stepAnimation(dt)
        invalidate()
        if (isAttachedToWindow) Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stepAnimation(dt: Float) {
        frameDt = dt
        scroll.step(dt)
        scales.values.forEach { it.step(dt) }
        stepCascade(dt)
        buttonScales.forEach { it.step(dt) }

        if (browsing && autoReturnAt > 0L && SystemClock.elapsedRealtime() >= autoReturnAt) {
            browsing = false
            autoReturnAt = 0L
        }

        val blurTarget = if (browsing) 0f else 1f
        val blurSpeed = if (browsing) 15f else 12f
        blurFade += (blurTarget - blurFade) *
            (1f - exp((-blurSpeed * dt).toDouble()).toFloat())
        if (abs(blurFade - blurTarget) < 0.01f) blurFade = blurTarget
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snapshot = PlaybackStore.snapshot
        ArtworkBackdrop.request(context, snapshot.artwork)
        drawBackground(canvas, snapshot)
        drawHeader(canvas, snapshot)

        val doc = LyricStore.document
        val effective = snapshot.estimatedPosition() + Prefs.lyricOffsetMs(context)
        val idx = doc.currentIndex(effective)
        ensureLayout(doc)
        updateCurrent(idx, doc)
        drawLyrics(canvas, doc, effective)
        drawControls(canvas, snapshot)
    }

    private fun drawBackground(canvas: Canvas, snapshot: PlaybackSnapshot) {
        val radius = 22f * height / 700f
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val path = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)
        val chosen = if (Prefs.backgroundMode(context) == "screen") ScreenBackdrop.blurred else ArtworkBackdrop.blurred
        if (chosen != null) {
            val src = Rect(0, 0, chosen.width, chosen.height)
            val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
            shapePaint.alpha = 255
            canvas.drawBitmap(chosen, src, dst, shapePaint)
        } else {
            canvas.drawColor(Color.rgb(30, 43, 65))
        }
        shapePaint.color = 0x60071420
        canvas.drawRect(rect, shapePaint)
        canvas.restore()
    }

    private fun drawHeader(canvas: Canvas, s: PlaybackSnapshot) {
        val scale = height / 700f
        val left = 22f * scale
        metaPaint.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        metaPaint.color = Color.WHITE
        metaPaint.textSize = 22f * scale
        metaPaint.alpha = 245
        canvas.drawText(if (s.title.isBlank()) "等待播放器…" else s.title, left, 40f * scale, metaPaint)
        metaPaint.textSize = 14f * scale
        metaPaint.alpha = 170
        canvas.drawText(s.artist, left, 64f * scale, metaPaint)

        s.artwork?.let { art ->
            val size = 78f * scale
            val dst = RectF(width - 20f * scale - size, 18f * scale, width - 20f * scale, 18f * scale + size)
            val path = Path().apply { addRoundRect(dst, 10f * scale, 10f * scale, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(art, null, dst, shapePaint)
            canvas.restore()
        }
    }

    private fun ensureLayout(doc: LyricDocument) {
        val font = designFontPx()
        if (cachedDoc === doc && cachedWidth == width && abs(cachedFont - font) < 0.1f) return
        cachedDoc = doc
        cachedWidth = width
        cachedFont = font
        visual = buildVisual(doc, font)
        firstLayout = true
        currentIndex = -1
        scales.clear()
        wordMotions.clear()
        cascadeSprings.clear()
        cascadeStartTimes.clear()
        cascadePosY = scroll.position
        glowThresholdMs = resolveGlowThreshold(doc)
    }

    private fun buildVisual(doc: LyricDocument, font: Float): List<VisualLine> {
        if (width <= 0) return emptyList()
        configureTextPaint(font)
        val maxWidth = width - 44f * height / 700f
        var top = 0f
        val out = ArrayList<VisualLine>(doc.lines.size)
        for ((i, line) in doc.lines.withIndex()) {
            val rows = if (Prefs.balancedWrap(context)) balancedRows(line.text, maxWidth) else greedyRows(line.text, maxWidth)
            val textHeight = rows.size * font * 1.08f
            val sub = if (line.translation.isNotBlank() || line.romaji.isNotBlank()) font * 0.72f else 0f
            val gap = font * 0.62f * Prefs.lineSpacing(context)
            val h = textHeight + sub + gap
            out += VisualLine(i, rows, top, h)
            top += h
        }
        return out
    }

    private fun configureTextPaint(font: Float) {
        textPaint.textSize = font
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Prefs.fontWeight(context), false)
        textPaint.style = Paint.Style.FILL
    }

    private fun updateCurrent(index: Int, doc: LyricDocument) {
        if (index < 0 || visual.isEmpty()) return
        if (currentIndex != index) {
            val old = currentIndex
            currentIndex = index
            if (old >= 0 && !browsing) startCascade(index)
            scales.getOrPut(index) {
                Spring1D(
                    position = AppleMotion.INACTIVE_SCALE,
                    target = AppleMotion.ACTIVE_SCALE,
                    mass = AppleMotion.SCALE_MASS,
                    stiffness = AppleMotion.SCALE_STIFFNESS,
                    damping = AppleMotion.SCALE_DAMPING
                )
            }.retarget(AppleMotion.ACTIVE_SCALE)
            if (old >= 0) scales.getOrPut(old) {
                Spring1D(position = AppleMotion.ACTIVE_SCALE, target = AppleMotion.INACTIVE_SCALE,
                    mass = AppleMotion.SCALE_MASS, stiffness = AppleMotion.SCALE_STIFFNESS,
                    damping = AppleMotion.SCALE_DAMPING)
            }.retarget(AppleMotion.INACTIVE_SCALE)
        }
        for (i in max(0, index - 7)..min(visual.lastIndex, index + 9)) {
            val target = if (i == index) AppleMotion.ACTIVE_SCALE else AppleMotion.INACTIVE_SCALE
            scales.getOrPut(i) {
                Spring1D(position = target, target = target, mass = AppleMotion.SCALE_MASS,
                    stiffness = AppleMotion.SCALE_STIFFNESS, damping = AppleMotion.SCALE_DAMPING)
            }.retarget(target)
        }

        if (!browsing) {
            val line = visual[index]
            val anchor = lyricTop + lyricHeight * 0.35f
            val target = anchor - (line.top + line.height / 2f)
            val nextStart = doc.lines.getOrNull(index + 1)?.startMs ?: doc.lines[index].endMs + 800
            val interval = (nextStart - doc.lines[index].endMs).coerceAtLeast(100)
            val stiffness = AppleMotion.scrollStiffness(interval)
            scroll.stiffness = stiffness
            scroll.damping = AppleMotion.scrollDamping(stiffness)
            scroll.mass = 1f
            if (firstLayout) {
                scroll.snap(target)
                firstLayout = false
            } else scroll.retarget(target)
        }
    }

    private fun startCascade(index: Int) {
        val now = SystemClock.elapsedRealtime()
        val pendingHold = cascadePosY - scroll.position
        if (abs(pendingHold) > 0.01f) {
            for ((line, started) in cascadeStartTimes.toMap()) {
                if (started >= 0L) {
                    val spring = cascadeSpring(line)
                    spring.snap(spring.position + pendingHold)
                    spring.retarget(0f)
                    cascadeStartTimes[line] = -1L
                }
            }
        }

        cascadePosY = scroll.position
        val startLine = max(0, index - 5)
        val endLine = min(visual.size, index + 10)
        val staggerStart = max(0, index - 1)
        for (i in startLine until endLine) {
            cascadeStartTimes[i] = if (i < staggerStart) -1L else now
        }
    }

    private fun cascadeSpring(index: Int): Spring1D =
        cascadeSprings.getOrPut(index) {
            Spring1D(
                position = 0f,
                target = 0f,
                mass = 1f,
                stiffness = 100f,
                damping = 15f
            )
        }

    private fun stepCascade(dt: Float) {
        if (currentIndex < 0 || visual.isEmpty()) return
        if (browsing) {
            cascadePosY = scroll.position
            cascadeStartTimes.replaceAll { _, _ -> -1L }
        }

        val now = SystemClock.elapsedRealtime()
        val startLine = max(0, currentIndex - 5)
        val endLine = min(visual.size, currentIndex + 10)
        val staggerStart = max(0, currentIndex - 1)
        var delayMs = 0f
        var stepMs = 60f
        val holdOffset = cascadePosY - scroll.position

        for (i in startLine until endLine) {
            val lineDelay = min(delayMs, 750f)
            val started = cascadeStartTimes[i]
            val held = started != null && started >= 0L
            if (held && now - started!! >= lineDelay) {
                val spring = cascadeSpring(i)
                spring.snap(spring.position + holdOffset)
                spring.retarget(0f)
                cascadeStartTimes[i] = -1L
            } else if (!held) {
                cascadeSprings[i]?.step(dt)
            }

            if (i >= staggerStart) delayMs += stepMs
            if (i >= currentIndex) stepMs /= 1.1f
        }
    }

    private fun cascadeOffset(index: Int): Float {
        val base = cascadeSprings[index]?.position ?: 0f
        val started = cascadeStartTimes[index]
        return if (!browsing && started != null && started >= 0L) {
            base + (cascadePosY - scroll.position)
        } else base
    }

    private fun drawLyrics(canvas: Canvas, doc: LyricDocument, positionMs: Long) {
        if (visual.isEmpty()) {
            configureTextPaint(designFontPx())
            textPaint.color = Color.WHITE
            textPaint.alpha = 160
            canvas.drawText("暂无匹配歌词", 22f, lyricTop + 40f, textPaint)
            return
        }
        val font = designFontPx()
        configureTextPaint(font)
        val accent = if (Prefs.dynamicColor(context)) {
            blendWhite(ArtworkBackdrop.accent, 0.30f)
        } else Color.WHITE

        for (v in visual) {
            val cascade = cascadeOffset(v.index)
            val centerY = v.top + v.height / 2f + scroll.position + cascade
            if (centerY < lyricTop - 90f || centerY > lyricBottom + 90f) continue
            val line = doc.lines[v.index]
            val dist = abs(v.index - currentIndex)
            val active = v.index == currentIndex
            val scale = scales[v.index]?.position ?: if (active) 1f else AppleMotion.INACTIVE_SCALE
            val alpha = if (active) 1f else 0.20f
            val edge = edgeFade(centerY)
            val drawAlpha = (255 * alpha * edge).toInt().coerceIn(0, 255)
            val pivotX = when (if (Prefs.duet(context)) line.alignment else DuetAlignment.LEFT) {
                DuetAlignment.RIGHT -> width - 22f * height / 700f
                DuetAlignment.CENTER -> width / 2f
                else -> 22f * height / 700f
            }

            canvas.save()
            canvas.scale(scale, scale, pivotX, centerY)
            val blurRadius = dist.coerceIn(1, 5) * 0.85f * (height / 700f) * blurFade
            textPaint.maskFilter = if (!active && Prefs.gaussianBlur(context) && blurRadius > 0.05f) {
                BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
            } else null

            textPaint.color = if (active) accent else Color.WHITE
            textPaint.alpha = drawAlpha
            if (active && line.words.isEmpty() && Prefs.glow(context)) {
                textPaint.setShadowLayer(5f * height / 700f, 0f, 0f, accent)
            } else {
                textPaint.clearShadowLayer()
            }

            val rowHeight = font * 1.08f
            var y = v.top + scroll.position + cascade + font
            val timedDrawn = if (active && line.words.isNotEmpty()) {
                val motion = wordMotions.getOrPut(v.index) { AppleWordMotion() }
                motion.tick(line, positionMs, scale, true, frameDt)
                drawTimedRows(
                    canvas = canvas,
                    visualLine = v,
                    line = line,
                    motion = motion,
                    edge = edge,
                    accent = accent,
                    font = font,
                    cascade = cascade
                )
            } else false

            if (!timedDrawn) {
                for (row in v.rows) {
                    val x = rowX(row, line, textPaint)
                    canvas.drawText(row, x, y, textPaint)
                    y += rowHeight
                }
            } else {
                y += v.rows.size * rowHeight
            }

            textPaint.clearShadowLayer()
            textPaint.maskFilter = null

            val sub = if (active) line.translation.ifBlank { line.romaji } else ""
            if (sub.isNotBlank()) {
                textPaint.textSize = font * 0.58f
                textPaint.alpha = (255 * edge * 0.76f).toInt().coerceIn(0, 255)
                textPaint.color = accent
                val x = rowX(sub, line, textPaint)
                canvas.drawText(sub, x, y + font * 0.12f, textPaint)
            }
            canvas.restore()
        }
    }

    private fun drawTimedRows(
        canvas: Canvas,
        visualLine: VisualLine,
        line: LyricLine,
        motion: AppleWordMotion,
        edge: Float,
        accent: Int,
        font: Float,
        cascade: Float
    ): Boolean {
        val source = line.words.joinToString(separator = "") { it.text }
        if (source.isBlank()) return false
        val maxWidth = width - 44f * height / 700f
        val rows = if (source == line.text) {
            visualLine.rows
        } else if (Prefs.balancedWrap(context)) {
            balancedRows(source, maxWidth)
        } else {
            greedyRows(source, maxWidth)
        }
        if (rows.isEmpty()) return false

        val rowHeight = font * 1.08f
        val slices = ArrayList<RowSlice>(rows.size)
        var searchFrom = 0
        for ((rowIndex, row) in rows.withIndex()) {
            var rowStart = source.indexOf(row, searchFrom)
            if (rowStart < 0) rowStart = searchFrom
            val rowEnd = (rowStart + row.length).coerceAtMost(source.length)
            val baseline = visualLine.top + scroll.position + cascade + font + rowIndex * rowHeight
            slices += RowSlice(
                text = source.substring(rowStart, rowEnd),
                start = rowStart,
                end = rowEnd,
                x = rowX(source.substring(rowStart, rowEnd), line, textPaint),
                baseline = baseline
            )
            searchFrom = rowEnd
        }

        var wordStart = 0
        for ((wordIndex, word) in line.words.withIndex()) {
            val wordEnd = (wordStart + word.text.length).coerceAtMost(source.length)
            if (wordEnd <= wordStart) continue

            val alpha = (255f * edge * motion.alphaAt(wordIndex)).toInt().coerceIn(0, 255)
            val lift = motion.liftAt(wordIndex) * height / 700f
            val emphasize = Prefs.glow(context) &&
                wordIndex == motion.currentWordIndex &&
                shouldEmphasize(line, word)
            val glowProgress = if (emphasize) {
                kotlin.math.sin(Math.PI * motion.intraWordProgress.toDouble())
                    .toFloat().coerceAtLeast(0f)
            } else 0f

            for (row in slices) {
                val a = max(wordStart, row.start)
                val b = min(wordEnd, row.end)
                if (b <= a) continue
                val prefix = source.substring(row.start, a)
                val segment = source.substring(a, b)
                val x = row.x + textPaint.measureText(prefix)
                textPaint.textSize = font
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Prefs.fontWeight(context), false)
                textPaint.color = accent
                textPaint.alpha = alpha
                if (glowProgress > 0.001f) {
                    textPaint.setShadowLayer(
                        font * 0.24f * glowProgress,
                        0f,
                        0f,
                        withAlpha(accent, (220f * glowProgress).toInt())
                    )
                } else {
                    textPaint.clearShadowLayer()
                }
                canvas.drawText(segment, x, row.baseline + lift, textPaint)
            }
            wordStart = wordEnd
        }
        textPaint.clearShadowLayer()
        return true
    }

    private fun resolveGlowThreshold(doc: LyricDocument): Int {
        val durations = doc.lines.asSequence()
            .flatMap { it.words.asSequence() }
            .map { it.durationMs.toInt() }
            .filter { it > 0 }
            .toMutableList()
        if (durations.size < 20) return 500
        durations.sort()
        return (durations[durations.size / 2] * 1.4f).toInt().coerceAtLeast(1)
    }

    private fun shouldEmphasize(line: LyricLine, word: WordTiming): Boolean {
        if (word.durationMs < glowThresholdMs || word.text.isBlank()) return false
        val metadata = arrayOf(
            "作词", "作曲", "词：", "词:", "曲：", "曲:", "编曲", "制作人",
            "混音", "录音", "母带", "出品", "监制", "演唱", "原唱", "翻唱"
        )
        val trimmedLine = line.text.trim()
        if (metadata.any { trimmedLine.startsWith(it) }) return false

        var hasLetterOrNumber = false
        var hasCjk = false
        var count = 0
        var i = 0
        while (i < word.text.length) {
            val cp = Character.codePointAt(word.text, i)
            if (Character.isLetterOrDigit(cp)) hasLetterOrNumber = true
            if ((cp in 0x4E00..0x9FFF) || (cp in 0x3040..0x30FF) ||
                (cp in 0x3000..0x303F) || (cp in 0xAC00..0xD7AF)) {
                hasCjk = true
            }
            count++
            i += Character.charCount(cp)
        }
        return hasLetterOrNumber && (hasCjk || count in 1..7)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    private fun drawControls(canvas: Canvas, s: PlaybackSnapshot) {
        val scale = height / 700f
        val y = height - 54f * scale
        val centers = floatArrayOf(width * 0.30f, width * 0.50f, width * 0.70f)
        val progressY = height - 96f * scale
        shapePaint.strokeWidth = 3f * scale
        shapePaint.strokeCap = Paint.Cap.ROUND
        shapePaint.color = 0x55FFFFFF
        canvas.drawLine(22f * scale, progressY, width - 22f * scale, progressY, shapePaint)
        val duration = s.durationMs
        if (duration > 0) {
            val ratio = (s.estimatedPosition().toFloat() / duration).coerceIn(0f, 1f)
            shapePaint.color = 0xE8FFFFFF.toInt()
            canvas.drawLine(22f * scale, progressY, 22f * scale + (width - 44f * scale) * ratio, progressY, shapePaint)
        }
        for (i in centers.indices) {
            canvas.save()
            canvas.scale(buttonScales[i].position, buttonScales[i].position, centers[i], y)
            shapePaint.color = Color.WHITE
            shapePaint.style = Paint.Style.FILL
            when (i) {
                0 -> drawSkip(canvas, centers[i], y, -1f, scale)
                1 -> if (s.playing) drawPause(canvas, centers[i], y, scale) else drawPlay(canvas, centers[i], y, scale)
                2 -> drawSkip(canvas, centers[i], y, 1f, scale)
            }
            canvas.restore()
        }
    }

    private fun drawPlay(c: Canvas, x: Float, y: Float, s: Float) {
        val p = Path()
        p.moveTo(x - 8*s, y - 13*s); p.lineTo(x + 13*s, y); p.lineTo(x - 8*s, y + 13*s); p.close()
        c.drawPath(p, shapePaint)
    }

    private fun drawPause(c: Canvas, x: Float, y: Float, s: Float) {
        c.drawRoundRect(x - 11*s, y - 13*s, x - 4*s, y + 13*s, 2*s, 2*s, shapePaint)
        c.drawRoundRect(x + 4*s, y - 13*s, x + 11*s, y + 13*s, 2*s, 2*s, shapePaint)
    }

    private fun drawSkip(c: Canvas, x: Float, y: Float, dir: Float, s: Float) {
        val p = Path()
        p.moveTo(x - 10*s*dir, y - 12*s)
        p.lineTo(x + 8*s*dir, y)
        p.lineTo(x - 10*s*dir, y + 12*s)
        p.close()
        c.drawPath(p, shapePaint)
        c.drawRect(x + 10*s*dir - 2*s, y - 13*s, x + 10*s*dir + 2*s, y + 13*s, shapePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (Prefs.touchThrough(context)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x; downY = event.y; lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (event.y < lyricBottom && downY < lyricBottom) {
                    val dy = event.y - lastY
                    if (abs(event.y - downY) > 8f) browsing = true
                    if (browsing) {
                        cascadeStartTimes.clear()
                        cascadeSprings.clear()
                        cascadePosY = scroll.position
                        scroll.snap(scroll.position + dy)
                        cascadePosY = scroll.position
                        autoReturnAt = 0L
                    }
                }
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000)
                val moved = Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()
                if (event.y > lyricBottom) {
                    handleControlTap(event.x)
                } else if (browsing) {
                    val vy = velocity?.yVelocity ?: 0f
                    val inertia = (vy * 0.30f).coerceIn(-300f, 300f)
                    scroll.velocity = vy
                    scroll.retarget(scroll.position + inertia)
                    autoReturnAt = SystemClock.elapsedRealtime() + 5_000L
                } else if (moved < 10f) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTapAt <= 320L) seekLineAt(event.y)
                    lastTapAt = now
                }
                velocity?.recycle(); velocity = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle(); velocity = null
                browsing = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleControlTap(x: Float) {
        val index = when {
            x < width * 0.40f -> 0
            x < width * 0.60f -> 1
            else -> 2
        }
        buttonScales[index].snap(0.78f)
        buttonScales[index].retarget(1f)
        when (index) {
            0 -> PlaybackStore.previous()
            1 -> PlaybackStore.playPause()
            2 -> PlaybackStore.next()
        }
    }

    private fun seekLineAt(y: Float) {
        val contentY = y - scroll.position
        val line = visual.minByOrNull { abs((it.top + it.height / 2f) - contentY) } ?: return
        val raw = cachedDoc?.lines?.getOrNull(line.index)?.startMs ?: return
        PlaybackStore.seekTo(raw - Prefs.lyricOffsetMs(context))
        browsing = false
    }

    private fun timedProgress(line: LyricLine, pos: Long): Float {
        if (line.words.isEmpty()) return 1f
        var completed = 0f
        var total = 0f
        for (w in line.words) {
            val weight = w.text.codePointCount(0, w.text.length).coerceAtLeast(1).toFloat()
            total += weight
            completed += when {
                pos >= w.endMs -> weight
                pos <= w.startMs -> 0f
                else -> weight * ((pos - w.startMs).toFloat() / w.durationMs.coerceAtLeast(1L))
            }
        }
        return if (total <= 0f) 0f else (completed / total).coerceIn(0f, 1f)
    }

    private fun rowX(row: String, line: LyricLine, paint: Paint): Float {
        val margin = 22f * height / 700f
        return when (if (Prefs.duet(context)) line.alignment else DuetAlignment.LEFT) {
            DuetAlignment.RIGHT -> width - margin - paint.measureText(row)
            DuetAlignment.CENTER -> (width - paint.measureText(row)) / 2f
            else -> margin
        }
    }

    private fun designFontPx() = Prefs.fontSize(context) * (height / 700f)

    private fun edgeFade(centerY: Float): Float {
        val edge = 38f * height / 700f
        return when {
            centerY < lyricTop -> 0f
            centerY < lyricTop + edge -> (centerY - lyricTop) / edge
            centerY > lyricBottom -> 0f
            centerY > lyricBottom - edge -> (lyricBottom - centerY) / edge
            else -> 1f
        }.coerceIn(0f, 1f)
    }

    private fun blendWhite(color: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (255 * (1-a) + Color.red(color) * a).toInt(),
            (255 * (1-a) + Color.green(color) * a).toInt(),
            (255 * (1-a) + Color.blue(color) * a).toInt()
        )
    }

    private fun balancedRows(text: String, maxWidth: Float): List<String> {
        if (textPaint.measureText(text) <= maxWidth) return listOf(text)
        val boundaries = codePointBoundaries(text)
        var best = -1
        var bestScore = Float.MAX_VALUE
        for (b in boundaries.drop(1).dropLast(1)) {
            val a = text.substring(0, b).trim()
            val c = text.substring(b).trim()
            val wa = textPaint.measureText(a)
            val wc = textPaint.measureText(c)
            if (wa <= maxWidth && wc <= maxWidth) {
                val score = abs(wa - wc)
                if (score < bestScore) { bestScore = score; best = b }
            }
        }
        if (best > 0) return listOf(text.substring(0, best).trim(), text.substring(best).trim())
        return greedyRows(text, maxWidth)
    }

    private fun greedyRows(text: String, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        var start = 0
        val boundaries = codePointBoundaries(text)
        for (i in 1 until boundaries.size) {
            val end = boundaries[i]
            if (textPaint.measureText(text.substring(start, end)) > maxWidth && end > start) {
                val prev = boundaries[i - 1]
                if (prev > start) {
                    out += text.substring(start, prev).trim()
                    start = prev
                }
            }
        }
        if (start < text.length) out += text.substring(start).trim()
        return out.filter { it.isNotEmpty() }.ifEmpty { listOf(text) }
    }

    private fun codePointBoundaries(text: String): List<Int> {
        val out = ArrayList<Int>()
        out += 0
        var i = 0
        while (i < text.length) {
            i += Character.charCount(Character.codePointAt(text, i))
            out += i
        }
        return out
    }
}
