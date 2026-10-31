package app.podsbattery

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Palette {
    // Постоянные цвета: тёмная карточка, акценты и всё, что лежит на карточке
    val INK = 0xFF1D1C1A.toInt()
    val ON_DARK = 0xFFF5F3EE.toInt()
    val MUTED_DARK = 0xFF8C877D.toInt()
    val LINE_DARK = 0xFF34312D.toInt()
    val TRACK_DARK = 0xFF3A3733.toInt()
    val ACCENT = 0xFFE8896A.toInt()
    val LIVE = 0xFF6CD49A.toInt()
    val RED = 0xFFE5484D.toInt()
    val MATRIX = ACCENT

    // Цвета, которые зависят от темы
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

/** Строка заряда на тёмной карточке: число, подпись и 10 сегментов (как «пиксели» на сайте). */
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
    }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = Palette.MUTED_DARK
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.NORMAL)
        color = Palette.MUTED_DARK
        letterSpacing = 0.14f
        textAlign = Paint.Align.RIGHT
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.NORMAL)
        color = Palette.LIVE
        letterSpacing = 0.14f
        textAlign = Paint.Align.RIGHT
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

        numPaint.textSize = 32 * d
        if (level >= 0) {
            val num = shown.roundToInt().toString()
            numPaint.color = Palette.ON_DARK
            canvas.drawText(num, x0, 32 * d, numPaint)
            pctPaint.textSize = 15 * d
            canvas.drawText("%", x0 + numPaint.measureText(num) + 3 * d, 32 * d, pctPaint)
        } else {
            numPaint.color = Palette.MUTED_DARK
            canvas.drawText("—", x0, 32 * d, numPaint)
        }

        labelPaint.textSize = 10 * d
        canvas.drawText(title, x1, 14 * d, labelPaint)
        if (charging) {
            subPaint.textSize = 10 * d
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

/**
 * Полоса «матричного дождя»: проходит по экрану один раз, сверху вниз.
 * Работает на собственном таймере, поэтому не зависит от системной настройки «Масштаб анимации».
 */
class MatrixRainView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val glyphs = "01ABCDEFGHJKLMNPQRSTUVWXYZ\$#@%&*+=<>/"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = Palette.MATRIX
    }
    private val rnd = java.util.Random(7)
    private val shifts = FloatArray(64) { rnd.nextFloat() }
    private val trails = IntArray(64) { 5 + rnd.nextInt(7) }
    private var progress = -1f
    private var frame = 0
    private var startedAt = 0L
    private var length = 1L

    private val ticker = object : Runnable {
        override fun run() {
            val f = (SystemClock.uptimeMillis() - startedAt).toFloat() / length
            if (f >= 1f) {
                progress = -1f
                invalidate()
                return
            }
            progress = f
            frame++
            invalidate()
            postOnAnimation(this)
        }
    }

    fun play(duration: Long) {
        removeCallbacks(ticker)
        startedAt = SystemClock.uptimeMillis()
        length = duration
        post(ticker)
    }

    override fun onDraw(canvas: Canvas) {
        if (progress < 0f) return
        val cell = 15 * d
        paint.textSize = 13 * d
        val cols = minOf((width / cell).toInt(), shifts.size)
        val startX = (width - cols * cell) / 2f + cell / 2f
        val travel = height + 14 * cell
        for (c in 0 until cols) {
            // небольшой разброс по высоте делает фронт дождя неровным
            val head = progress * travel - shifts[c] * 4 * cell
            val trail = trails[c]
            for (k in 0 until trail) {
                val y = head - k * cell
                if (y < 0f || y > height + cell) continue
                paint.alpha = if (k == 0) 220 else (140 * (1f - k / trail.toFloat())).toInt()
                val g = glyphs[(c * 31 + k * 17 + (frame / 2) * 7) % glyphs.length]
                canvas.drawText(g.toString(), startX + c * cell, y, paint)
            }
        }
    }
}

/** Круглая кнопка смены темы: луна в светлой теме, солнце в тёмной. */
class ThemeButton(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rays = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.6f * d
    }
    private val moon = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((34 * d).toInt(), (34 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        fill.color = Palette.BAR
        canvas.drawCircle(cx, cy, cx - d, fill)
        ring.color = Palette.LINE
        canvas.drawCircle(cx, cy, cx - d, ring)

        fill.color = Palette.TEXT
        rays.color = Palette.TEXT
        if (Palette.dark) {
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
    }
}

/**
 * Фон из скруглённых квадратиков в 2.5D: плоскость с сеткой наклонена в перспективе,
 * левый край ближе (крупнее), правый дальше (мельче и бледнее).
 * Возле пальца квадратики приподнимаются к зрителю и слегка поворачиваются, потом плавно возвращаются.
 */
class PatternView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    // Контур скруглённого квадрата в локальных координатах: по шесть точек на каждый угол
    private val local: FloatArray

    // Наклон плоскости и «камера»
    private val cosY = cos(Math.toRadians(32.0)).toFloat()
    private val sinY = sin(Math.toRadians(32.0)).toFloat()
    private val cosX = cos(Math.toRadians(10.0)).toFloat()
    private val sinX = sin(Math.toRadians(10.0)).toFloat()
    private val focal = 400f * d
    private var centerX = 0f
    private var centerY = 0f
    private val mid = FloatArray(3)
    private val pt = FloatArray(3)

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
    }

    private val ticker = object : Runnable {
        override fun run() {
            fingerX += (targetX - fingerX) * 0.25f
            fingerY += (targetY - fingerY) * 0.25f
            strength += ((if (touching) 1f else 0f) - strength) * 0.16f
            invalidate()
            if (touching || strength > 0.01f) {
                postOnAnimation(this)
            } else {
                strength = 0f
                running = false
                invalidate()
            }
        }
    }

    /** Принимает координаты касания в системе этого элемента; сами события при этом не перехватываются. */
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
        if (!running) {
            running = true
            postOnAnimation(ticker)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        running = false
        super.onDetachedFromWindow()
    }

    /** Точка плоскости (x, y) с подъёмом z: поворот вокруг Y и X, затем перспектива. out = [экранный x, экранный y, масштаб]. */
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

    private fun withAlpha(color: Int, factor: Float): Int =
        (color and 0x00FFFFFF) or (((color ushr 24) * factor).toInt() shl 24)

    override fun onDraw(canvas: Canvas) {
        centerX = width / 2f
        centerY = height / 2f
        val pitch = 76f * d
        val reach = 160f * d
        val margin = 90f * d

        for (r in -9..9) {
            for (c in -6..6) {
                val wx = c * pitch
                val wy = r * pitch
                if (!project(wx, wy, 0f, mid)) continue
                if (mid[0] < -margin || mid[0] > width + margin || mid[1] < -margin || mid[1] > height + margin) continue

                var angle = 0f
                var lift = 0f
                if (strength > 0f) {
                    val dx = mid[0] - fingerX
                    val dy = mid[1] - fingerY
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < reach) {
                        val k = 1f - dist / reach
                        angle = 20f * k * k * strength
                        lift = 44f * d * k * k * strength
                    }
                }
                val rad = Math.toRadians(angle.toDouble())
                val ca = cos(rad).toFloat()
                val sa = sin(rad).toFloat()

                path.reset()
                var ok = true
                var i = 0
                while (i < local.size) {
                    val lx = local[i]
                    val ly = local[i + 1]
                    val px = wx + lx * ca - ly * sa
                    val py = wy + lx * sa + ly * ca
                    if (!project(px, py, lift, pt)) {
                        ok = false
                        break
                    }
                    if (i == 0) path.moveTo(pt[0], pt[1]) else path.lineTo(pt[0], pt[1])
                    i += 2
                }
                if (!ok) continue
                path.close()

                val scale = mid[2]
                val fade = ((scale - 0.55f) / 0.6f).coerceIn(0.35f, 1f)
                outline.strokeWidth = 2f * d * scale

                canvas.save()
                canvas.translate(-1.5f * d * scale, -1.5f * d * scale)
                outline.color = withAlpha(Palette.PATTERN_HI, fade)
                canvas.drawPath(path, outline)
                canvas.restore()

                outline.color = withAlpha(Palette.PATTERN, fade)
                canvas.drawPath(path, outline)
            }
        }
    }
}

/**
 * Анимированный значок-«искра»: лучи пульсируют и медленно вращаются, как индикатор размышления.
 * Пока идёт поиск, он оживлённее, при живых данных успокаивается.
 */
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

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = if (last == 0L) 16L else minOf(now - last, 64L)
            last = now
            val speed = if (busy) 1f else 0.35f
            phase += dt / 1000f * speed * (2f * Math.PI.toFloat() / 1.6f)
            angle += dt / 1000f * speed * 40f
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = 0L
        postOnAnimation(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((30 * d).toInt(), (30 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
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
}
