package app.podsbattery

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object Palette {
    val INK = 0xFF1D1C1A.toInt()
    val ON_DARK = 0xFFF5F3EE.toInt()
    val MUTED_DARK = 0xFF8C877D.toInt()
    val LINE_DARK = 0xFF34312D.toInt()
    val TRACK_DARK = 0xFF3A3733.toInt()
    val ACCENT = 0xFFE8896A.toInt()
    val LIVE = 0xFF6CD49A.toInt()
    val RED = 0xFFE5484D.toInt()
    val MATRIX = ACCENT

    var dark = false
        private set
    var BG = 0
        private set
    var TEXT = 0
        private set
    var MUTED = 0
        private set
    var LINE = 0
        private set
    var BAR = 0
        private set
    var PILL_BG = 0
        private set
    var PILL_TEXT = 0
        private set
    var CARD_STROKE = 0
        private set
    var PATTERN = 0
        private set
    var PATTERN_HI = 0
        private set

    init {
        apply(false)
    }

    fun apply(isDark: Boolean) {
        dark = isDark
        if (isDark) {
            BG = 0xFF0F0E0D.toInt()
            TEXT = 0xFFF2F0EA.toInt()
            MUTED = 0xFF8E8A80.toInt()
            LINE = 0xFF2A2825.toInt()
            BAR = 0xFF181715.toInt()
            PILL_BG = 0xFFF2F0EA.toInt()
            PILL_TEXT = 0xFF1D1C1A.toInt()
            CARD_STROKE = 0xFF2E2B28.toInt()
            PATTERN = 0xFF2B2926.toInt()
            PATTERN_HI = 0xFF050505.toInt()
        } else {
            BG = 0xFFFAF9F5.toInt()
            TEXT = 0xFF1D1C1A.toInt()
            MUTED = 0xFF8A867C.toInt()
            LINE = 0xFFE7E4DA.toInt()
            BAR = 0xFFFDFCFA.toInt()
            PILL_BG = 0xFF1D1C1A.toInt()
            PILL_TEXT = 0xFFF5F3EE.toInt()
            CARD_STROKE = 0x00000000
            PATTERN = 0xFFDDDACE.toInt()
            PATTERN_HI = 0xFFFFFFFF.toInt()
        }
    }

    fun forLevel(p: Int) = if (p <= 20) RED else ACCENT
}

class BatteryRow(context: Context, title: String) : View(context) {
    private val d = resources.displayMetrics.density
    var title: String = title
        set(value) { field = value; invalidate() }
    var chargingText: String = context.getString(R.string.charging)
        set(value) { field = value; invalidate() }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = -0.03f
        color = Palette.ON_DARK
        textSize = 32 * d
    }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = Palette.MUTED_DARK
        textSize = 15 * d
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.NORMAL)
        color = Palette.MUTED_DARK
        letterSpacing = 0.14f
        textAlign = Paint.Align.RIGHT
        textSize = 10 * d
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.NORMAL)
        color = Palette.LIVE
        letterSpacing = 0.14f
        textAlign = Paint.Align.RIGHT
        textSize = 10 * d
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TRACK_DARK }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var shown = 0f
    private var level = -1
    private var charging = false
    private var anim: ValueAnimator? = null

    fun setLevel(pct: Int?, isCharging: Boolean, animate: Boolean = true) {
        val newLevel = pct ?: -1
        if (newLevel == level && isCharging == charging) return
        level = newLevel
        charging = isCharging
        anim?.cancel()
        if (!animate) {
            shown = (pct ?: 0).toFloat()
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(shown, (pct ?: 0).toFloat()).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (66 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val x0 = paddingLeft.toFloat()
        val x1 = (width - paddingRight).toFloat()

        if (level >= 0) {
            val num = shown.roundToInt().toString()
            numPaint.color = Palette.ON_DARK
            canvas.drawText(num, x0, 32 * d, numPaint)
            canvas.drawText("%", x0 + numPaint.measureText(num) + 3 * d, 32 * d, pctPaint)
        } else {
            numPaint.color = Palette.MUTED_DARK
            canvas.drawText("—", x0, 32 * d, numPaint)
        }

        canvas.drawText(title, x1, 14 * d, labelPaint)
        if (charging) {
            canvas.drawText(chargingText, x1, 30 * d, subPaint)
        }

        val n = 10
        val gap = 3 * d
        val segW = (x1 - x0 - gap * (n - 1)) / n
        val top = 46 * d
        val segH = 11 * d
        for (i in 0 until n) {
            val left = x0 + i * (segW + gap)
            rect.set(left, top, left + segW, top + segH)
            canvas.drawRoundRect(rect, 2.5f * d, 2.5f * d, track)
            val a = (shown / 10f - i).coerceIn(0f, 1f)
            if (level >= 0 && a > 0f) {
                fill.color = Palette.forLevel(level)
                fill.alpha = (a * 255).toInt()
                canvas.drawRoundRect(rect, 2.5f * d, 2.5f * d, fill)
            }
        }
    }
}

class MatrixRainView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val glyphs = "01ABCDEFGHJKLMNPQRSTUVWXYZ\$#@%&*+=<>/"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = Palette.MATRIX
        textSize = 13 * d
    }
    private val glyphStrings = Array(glyphs.length) { glyphs[it].toString() }
    private val rnd = java.util.Random(7)
    private val shifts = FloatArray(64) { rnd.nextFloat() }
    private val trails = IntArray(64) { 5 + rnd.nextInt(7) }
    private var progress = -1f
    private var frame = 0
    private var startedAt = 0L
    private var length = 1L
    private var playing = false
    private var direction = 1
    private var startPos = 0f
    private var currentPos = 0f

    private val ticker = object : Runnable {
        override fun run() {
            val f = (SystemClock.uptimeMillis() - startedAt).toFloat() / length
            if (f >= 1f) {
                progress = -1f
                playing = false
                invalidate()
                return
            }
            progress = f
            frame++
            invalidate()
            postOnAnimation(this)
        }
    }

    fun contactDelay(anchor: View): Long {
        if (height <= 0 || width <= 0) return 0L
        val here = IntArray(2)
        val there = IntArray(2)
        getLocationOnScreen(here)
        anchor.getLocationOnScreen(there)
        val x = there[0] - here[0] + anchor.width / 2f
        val y = there[1] - here[1] + anchor.height / 2f
        val cell = 15 * d
        val cols = minOf((width / cell).toInt(), shifts.size)
        val startX = (width - cols * cell) / 2f + cell / 2f
        val c = ((x - startX) / cell).roundToInt().coerceIn(0, cols - 1)
        val end = if (direction > 0) height + 14 * cell else -14 * cell
        val denominator = end - startPos
        if (denominator == 0f) return 0L
        val target = y + shifts[c] * 4 * cell
        val p = ((target - startPos) / denominator).coerceIn(0f, 1f)
        return (p * length).toLong()
    }

    fun play(duration: Long) {
        if (playing) {
            direction = -direction
            startPos = currentPos
        } else {
            direction = 1
            startPos = 0f
        }
        removeCallbacks(ticker)
        playing = true
        startedAt = SystemClock.uptimeMillis()
        length = duration
        post(ticker)
    }

    override fun onDraw(canvas: Canvas) {
        if (progress < 0f) return
        val cell = 15 * d
        val cols = minOf((width / cell).toInt(), shifts.size)
        val startX = (width - cols * cell) / 2f + cell / 2f
        val end = if (direction > 0) height + 14 * cell else -14 * cell
        val pos = startPos + (end - startPos) * progress
        currentPos = pos
        for (c in 0 until cols) {
            val head = pos - shifts[c] * 4 * cell
            val trail = trails[c]
            for (k in 0 until trail) {
                val y = head - direction * k * cell
                if (y < 0f || y > height + cell) continue
                paint.alpha = if (k == 0) 220 else (140 * (1f - k / trail.toFloat())).toInt()
                val g = glyphStrings[(c * 31 + k * 17 + (frame / 2) * 7) % glyphStrings.size]
                canvas.drawText(g, startX + c * cell, y, paint)
            }
        }
    }
}

class ThemeButton(context: Context, startDark: Boolean = Palette.dark) : View(context) {
    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rays = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.6f * d
    }
    private val moon = Path()
    private val clip = Path()
    private var iconDark = startDark
    private var fromDark = startDark
    private var toDark = startDark
    private var progress = 1f
    private var animator: ValueAnimator? = null

    fun playTo(dark: Boolean) {
        animator?.cancel()
        if (dark == iconDark) {
            progress = 1f
            invalidate()
            return
        }
        fromDark = iconDark
        toDark = dark
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            addUpdateListener {
                progress = it.animatedValue as Float
                if (progress >= 1f) iconDark = toDark
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((34 * d).toInt(), (34 * d).toInt())
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, sun: Boolean, dy: Float, alpha: Float) {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        val color = (Palette.TEXT and 0x00FFFFFF) or (a shl 24)
        fill.color = color
        rays.color = color
        canvas.save()
        canvas.translate(0f, dy)
        if (sun) {
            canvas.drawCircle(cx, cy, 3.8f * d, fill)
            for (i in 0 until 8) {
                val angle = Math.toRadians(i * 45.0)
                val c = cos(angle).toFloat()
                val s = sin(angle).toFloat()
                canvas.drawLine(cx + c * 6.4f * d, cy + s * 6.4f * d, cx + c * 8.2f * d, cy + s * 8.2f * d, rays)
            }
        } else {
            val full = Path().apply { addCircle(cx, cy, 7f * d, Path.Direction.CW) }
            val cut = Path().apply { addCircle(cx + 3.6f * d, cy - 2.8f * d, 6f * d, Path.Direction.CW) }
            moon.reset()
            moon.op(full, cut, Path.Op.DIFFERENCE)
            canvas.drawPath(moon, fill)
        }
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        fill.color = Palette.BAR
        canvas.drawCircle(cx, cy, cx - d, fill)

        canvas.save()
        clip.reset()
        clip.addCircle(cx, cy, cx - d, Path.Direction.CW)
        canvas.clipPath(clip)
        if (progress >= 1f) {
            drawIcon(canvas, cx, cy, iconDark, 0f, 1f)
        } else if (progress < 0.5f) {
            val u = progress / 0.5f
            drawIcon(canvas, cx, cy, fromDark, u * u * 14f * d, 1f - u)
        } else {
            val u = (progress - 0.5f) / 0.5f
            val e = 1f - (1f - u) * (1f - u)
            drawIcon(canvas, cx, cy, toDark, (1f - e) * 14f * d, u)
        }
        canvas.restore()

        ring.color = Palette.LINE
        canvas.drawCircle(cx, cy, cx - d, ring)
    }
}

class JellyPill(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var left = 0f
    private var right = 0f
    private var restWidth = 0f
    private var ready = false
    private var leftAnim: ValueAnimator? = null
    private var rightAnim: ValueAnimator? = null

    val animating: Boolean
        get() = leftAnim?.isRunning == true || rightAnim?.isRunning == true

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    fun snapTo(l: Float, r: Float) {
        leftAnim?.cancel()
        rightAnim?.cancel()
        left = l
        right = r
        restWidth = r - l
        ready = true
        invalidate()
    }

    fun flowTo(l: Float, r: Float) {
        if (!ready) {
            snapTo(l, r)
            return
        }
        leftAnim?.cancel()
        rightAnim?.cancel()
        restWidth = r - l
        val movingRight = r > right
        leftAnim = animateEdge(left, l, if (movingRight) 90L else 0L, if (movingRight) 2.2f else 1.3f) { left = it }
        rightAnim = animateEdge(right, r, if (movingRight) 0L else 90L, if (movingRight) 1.3f else 2.2f) { right = it }
    }

    private fun animateEdge(from: Float, to: Float, delay: Long, tension: Float, setEdge: (Float) -> Unit): ValueAnimator =
        ValueAnimator.ofFloat(from, to).apply {
            startDelay = delay
            duration = 420
            interpolator = OvershootInterpolator(tension)
            addUpdateListener {
                setEdge(it.animatedValue as Float)
                invalidate()
            }
            start()
        }

    override fun onDetachedFromWindow() {
        leftAnim?.cancel()
        rightAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (!ready) return
        val w = right - left
        if (w <= 0f) return
        val stretch = if (restWidth > 0f) w / restWidth else 1f
        val squash = (1f / sqrt(stretch)).coerceIn(0.75f, 1.12f)
        val h = height * squash
        val top = (height - h) / 2f
        rect.set(left, top, right, top + h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
    }
}

class PatternView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val local: FloatArray

    private val cosY = cos(Math.toRadians(32.0)).toFloat()
    private val sinY = sin(Math.toRadians(32.0)).toFloat()
    private val cosX = cos(Math.toRadians(10.0)).toFloat()
    private val sinX = sin(Math.toRadians(10.0)).toFloat()
    private val focal = 400f * d
    private var centerX = 0f
    private var centerY = 0f
    private val mid = FloatArray(3)
    private val pt = FloatArray(3)

    private val colsN = 13
    private val phases = FloatArray(19 * colsN).also {
        val rnd = Random(7)
        for (i in it.indices) it[i] = rnd.nextFloat() * 2f * Math.PI.toFloat()
    }
    private val startMs = SystemClock.uptimeMillis()

    private val meshN = 6
    private val verts = FloatArray((meshN + 1) * (meshN + 1) * 2)
    private val texScale = 2f
    private val texSize = Math.ceil((58f * d * texScale).toDouble()).toInt()
    private val halfB = texSize / (2f * texScale)
    private val texHi: Bitmap
    private val texMain: Bitmap

    private var touching = false
    private var targetX = 0f
    private var targetY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var strength = 0f
    private var running = false

    init {
        val half = 25f * d
        val r = 14f * d
        val inner = half - r
        val centers = arrayOf(
            floatArrayOf(inner, inner), floatArrayOf(-inner, inner),
            floatArrayOf(-inner, -inner), floatArrayOf(inner, -inner)
        )
        val pts = ArrayList<Float>()
        for (k in 0 until 4) {
            for (i in 0..5) {
                val a = Math.toRadians(90.0 * k + 90.0 * i / 5)
                pts.add(centers[k][0] + r * cos(a).toFloat())
                pts.add(centers[k][1] + r * sin(a).toFloat())
            }
        }
        local = pts.toFloatArray()
        texHi = bake(Palette.PATTERN_HI)
        texMain = bake(Palette.PATTERN)
    }

    private fun bake(color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(texSize, texSize, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2f * d * texScale
            this.color = color
        }
        val shape = Path()
        var i = 0
        while (i < local.size) {
            val x = texSize / 2f + local[i] * texScale
            val y = texSize / 2f + local[i + 1] * texScale
            if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
            i += 2
        }
        shape.close()
        c.drawPath(shape, p)
        return bmp
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            fingerX += (targetX - fingerX) * 0.25f
            fingerY += (targetY - fingerY) * 0.25f
            strength += ((if (touching) 1f else 0f) - strength) * 0.16f
            if (!touching && strength <= 0.01f) strength = 0f
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun startTicking() {
        if (running) return
        running = true
        postOnAnimation(ticker)
    }

    private fun stopTicking() {
        running = false
        removeCallbacks(ticker)
    }

    fun touch(x: Float, y: Float, action: Int) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                targetX = x
                targetY = y
                fingerX = x
                fingerY = y
            }
            MotionEvent.ACTION_MOVE -> {
                targetX = x
                targetY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        startTicking()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTicking()
    }

    override fun onDetachedFromWindow() {
        stopTicking()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            if (isAttachedToWindow) startTicking()
        } else {
            stopTicking()
        }
    }

    private fun project(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val x1 = x * cosY + z * sinY
        val z1 = -x * sinY + z * cosY
        val y2 = y * cosX - z1 * sinX
        val z2 = y * sinX + z1 * cosX
        val depth = focal - z2
        if (depth < 60f * d) return false
        val k = focal / depth
        out[0] = centerX + x1 * k
        out[1] = centerY + y2 * k
        out[2] = k
        return true
    }

    private fun fillMesh(wx: Float, wy: Float, lift: Float, ca: Float, sa: Float, grow: Float): Boolean {
        var n = 0
        for (j in 0..meshN) {
            val ly = -halfB + j * 2f * halfB / meshN
            for (i in 0..meshN) {
                val lx = -halfB + i * 2f * halfB / meshN
                val px = wx + (lx * ca - ly * sa) * grow
                val py = wy + (lx * sa + ly * ca) * grow
                if (!project(px, py, lift, pt)) return false
                verts[n++] = pt[0]
                verts[n++] = pt[1]
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        centerX = width / 2f
        centerY = height / 2f
        val pitch = 76f * d
        val margin = 90f * d
        val reach = 160f * d
        val t = (drawingTime - startMs) / 1000.0
        val swayPhase = 2.0 * Math.PI * t / SWAY_PERIOD
        val swayX = (0.14 * pitch * sin(swayPhase - 0.61)).toFloat()
        val swayY = (0.15 * pitch * sin(swayPhase + 1.5)).toFloat()
        val pulsePhase = 2.0 * Math.PI * t / PULSE_PERIOD

        for (r in -9..9) {
            for (c in -6..6) {
                val idx = (r + 9) * colsN + (c + 6)
                val wx = c * pitch + swayX
                val wy = r * pitch + swayY
                if (!project(wx, wy, 0f, mid)) continue
                if (mid[0] < -margin || mid[0] > width + margin || mid[1] < -margin || mid[1] > height + margin) continue
                val scale = mid[2]
                val grow = 1f + 0.05f * sin(pulsePhase + phases[idx]).toFloat()

                var lift = 0f
                var ca = 1f
                var sa = 0f
                if (strength > 0f) {
                    val dx = mid[0] - fingerX
                    val dy = mid[1] - fingerY
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < reach) {
                        val k = 1f - dist / reach
                        val angle = 20f * k * k * strength
                        lift = 44f * d * k * k * strength
                        val rad = Math.toRadians(angle.toDouble())
                        ca = cos(rad).toFloat()
                        sa = sin(rad).toFloat()
                    }
                }
                if (!fillMesh(wx, wy, lift, ca, sa, grow)) continue

                val fade = (((scale - 0.55f) / 0.6f).coerceIn(0.35f, 1f) * (1f + 0.5f * (grow - 1f))).coerceAtMost(1f)
                bitmapPaint.alpha = (fade * 255f).roundToInt()

                canvas.save()
                canvas.translate(-1.5f * d * scale, -1.5f * d * scale)
                canvas.drawBitmapMesh(texHi, meshN, meshN, verts, 0, null, 0, bitmapPaint)
                canvas.restore()

                canvas.drawBitmapMesh(texMain, meshN, meshN, verts, 0, null, 0, bitmapPaint)
            }
        }
    }
}

private const val PULSE_PERIOD = 3.23
private const val SWAY_PERIOD = 9.8

class SparkView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.ACCENT
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    var busy = true
    private var phase = 0f
    private var angle = 0f
    private var last = 0L
    private var running = false

    private val strips: List<Bitmap> = listOf(
        R.drawable.spark_code_in, R.drawable.spark_code_loop, R.drawable.spark_code_out,
        R.drawable.spark_read_in, R.drawable.spark_read_loop, R.drawable.spark_read_out
    ).mapNotNull { id ->
        try {
            BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })
        } catch (e: Exception) {
            null
        }
    }
    private val frameCounts = strips.map { maxOf(1, it.height / it.width) }
    private val totalFrames = maxOf(1, frameCounts.sum())
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = Rect()
    private var stripMs = 0L
    private var shownFrame = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = if (last == 0L) 16L else minOf(now - last, 64L)
            last = now
            if (strips.isNotEmpty()) {
                stripMs += dt
                val frame = ((stripMs * STRIP_FPS / 1000) % totalFrames).toInt()
                if (frame != shownFrame) {
                    shownFrame = frame
                    invalidate()
                }
            } else {
                phase = (phase + dt / 1000f * (2f * Math.PI.toFloat() / 1.6f)) % (2f * Math.PI.toFloat())
                angle = (angle + dt / 1000f * 40f) % 360f
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    private fun startTicking() {
        if (running) return
        running = true
        last = 0L
        postOnAnimation(ticker)
    }

    private fun stopTicking() {
        running = false
        removeCallbacks(ticker)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTicking()
    }

    override fun onDetachedFromWindow() {
        stopTicking()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            if (isAttachedToWindow) startTicking()
        } else {
            stopTicking()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((30 * d).toInt(), (30 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (strips.isNotEmpty()) {
            drawStrip(canvas)
            return
        }
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - d
        paint.strokeWidth = radius * 0.2f
        val rays = 10
        canvas.save()
        canvas.rotate(angle, cx, cy)
        for (i in 0 until rays) {
            val a = i * 2f * Math.PI.toFloat() / rays
            val pulse = 0.5f + 0.5f * sin(phase + i * 0.9f)
            val length = radius * (0.45f + 0.55f * pulse)
            val inner = radius * 0.2f
            canvas.drawLine(
                cx + cos(a) * inner, cy + sin(a) * inner,
                cx + cos(a) * length, cy + sin(a) * length, paint
            )
        }
        canvas.restore()
    }

    private fun drawStrip(canvas: Canvas) {
        var local = shownFrame
        var k = 0
        while (k < frameCounts.size - 1 && local >= frameCounts[k]) {
            local -= frameCounts[k]
            k++
        }
        val bmp = strips[k]
        val size = bmp.width
        src.set(0, local * size, size, (local + 1) * size)
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bmp, src, dst, bitmapPaint)
    }
}

private const val STRIP_FPS = 30L
