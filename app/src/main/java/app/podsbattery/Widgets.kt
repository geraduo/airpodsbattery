package app.podsbattery

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.roundToInt

object Palette {
    val BG = 0xFFFAF9F5.toInt()
    val INK = 0xFF1D1C1A.toInt()
    val MUTED = 0xFF8A867C.toInt()
    val LINE = 0xFFE7E4DA.toInt()
    val BAR = 0xFFFDFCFA.toInt()
    val ON_DARK = 0xFFF5F3EE.toInt()
    val MUTED_DARK = 0xFF8C877D.toInt()
    val LINE_DARK = 0xFF34312D.toInt()
    val TRACK_DARK = 0xFF3A3733.toInt()
    val ACCENT = 0xFFE8896A.toInt()
    val LIVE = 0xFF6CD49A.toInt()
    val RED = 0xFFE5484D.toInt()
    val MATRIX = ACCENT
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

    fun setLevel(pct: Int?, isCharging: Boolean) {
        val newLevel = pct ?: -1
        if (newLevel == level && isCharging == charging) return
        level = newLevel
        charging = isCharging
        anim?.cancel()
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
