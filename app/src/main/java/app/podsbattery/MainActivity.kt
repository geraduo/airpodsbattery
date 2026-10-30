package app.podsbattery

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

private const val APPLE_COMPANY_ID = 0x004C
private const val PREFS = "settings"
private const val KEY_LANG = "lang"
private const val DEFAULT_LANG = "en"

class MainActivity : Activity() {

    private lateinit var modelTitle: TextView
    private lateinit var signalText: TextView
    private lateinit var headsetText: TextView
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var leftRow: BatteryRow
    private lateinit var rightRow: BatteryRow
    private lateinit var caseRow: BatteryRow
    private lateinit var debugLabel: TextView
    private lateinit var cardTitle: TextView
    private lateinit var diagButton: TextView
    private lateinit var refreshButton: TextView
    private lateinit var hintText: TextView
    private lateinit var disclaimerText: TextView
    private lateinit var rain: MatrixRainView
    private val langButtons = mutableMapOf<String, TextView>()

    private var lang = DEFAULT_LANG
    private var localizedRes: Resources? = null
    private var switching = false

    private var statusRes = R.string.status_searching
    private var statusArg: Int? = null
    private var statusLive = false
    private var headsetPct: Int? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastSeen = 0L
    private var scanning = false

    private var total = 0
    private var apple = 0
    private var pairing = 0
    private val types = java.util.TreeMap<Int, Int>()
    private var headsetEvents = 0
    private var lastCmd = "-"

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            total++
            val data = result.scanRecord?.getManufacturerSpecificData(APPLE_COMPANY_ID)
            if (data != null) {
                apple++
                if (data.isNotEmpty()) {
                    val t = data[0].toInt() and 0xFF
                    types[t] = (types[t] ?: 0) + 1
                    if (data[0] == 0x07.toByte()) {
                        pairing++
                        // Берём только близкие устройства (ваши AirPods, а не чужие рядом)
                        if (result.rssi >= -85) parse(data)
                    }
                }
            }
            renderDebug()
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            setStatus(R.string.status_scan_error, arg = errorCode)
        }
    }

    private val signalWatchdog = object : Runnable {
        override fun run() {
            if (scanning && System.currentTimeMillis() - lastSeen > 8000) {
                setStatus(R.string.status_no_signal)
            }
            handler.postDelayed(this, 1000)
        }
    }

    // Общий заряд от подключённых AirPods приходит как команда гарнитуры Apple +IPHONEACCEV
    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val cmd = i.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD) ?: return
            headsetEvents++
            lastCmd = cmd
            @Suppress("DEPRECATION")
            val args = i.getSerializableExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS) as? Array<*>
            if (cmd == "+IPHONEACCEV" && args != null && args.isNotEmpty()) {
                val n = (args[0] as? Int) ?: 0
                var k = 1
                repeat(n) {
                    val key = args.getOrNull(k) as? Int
                    val v = args.getOrNull(k + 1) as? Int
                    if (key == 1 && v != null) showHeadsetLevel((v + 1) * 10)
                    k += 2
                }
            }
            renderDebug()
        }
    }

    private fun registerHeadsetReceiver() {
        val f = IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
        f.addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + ".76")
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(headsetReceiver, f, Context.RECEIVER_EXPORTED)
            else registerReceiver(headsetReceiver, f)
        } catch (_: Exception) {}
    }

    // Ресурсы подменяются без пересоздания экрана, поэтому язык меняется без вспышки
    override fun getResources(): Resources = localizedRes ?: super.getResources()

    private fun applyLocale(code: String) {
        val config = Configuration(super.getResources().configuration)
        config.setLocale(Locale(code))
        localizedRes = createConfigurationContext(config).resources
    }

    private fun setLanguage(code: String) {
        if (code == lang || switching) return
        lang = code
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LANG, code).apply()
        applyLocale(code)
        updateLanguageToggle()
        leftRow.chargingText = getString(R.string.charging)
        rightRow.chargingText = getString(R.string.charging)
        caseRow.chargingText = getString(R.string.charging)
        playLanguageTransition()
    }

    private fun updateLanguageToggle() {
        for ((code, button) in langButtons) {
            val active = code == lang
            button.setTextColor(if (active) Palette.ON_DARK else Palette.MUTED)
            (button.background as GradientDrawable).setColor(if (active) Palette.INK else Color.TRANSPARENT)
        }
    }

    private val scrambleGlyphs = "01ABCDEFGHJKLMNPQRSTUVWXYZ\$#@%&*+=<>/"

    /** Текст «расшифровывается» слева направо из случайных символов; до своей очереди остаётся прежним. */
    private fun scramble(delay: Long, duration: Long, target: () -> String, onText: (CharSequence) -> Unit) {
        val rnd = java.util.Random()
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener {
                val text = target()
                val resolved = (it.animatedFraction * text.length).toInt()
                val out = StringBuilder(text)
                for (i in resolved until out.length) {
                    if (!out[i].isWhitespace() && out[i] !in "·|%") out.setCharAt(i, scrambleGlyphs[rnd.nextInt(scrambleGlyphs.length)])
                }
                val span = SpannableString(out)
                if (resolved < out.length) {
                    span.setSpan(ForegroundColorSpan(Palette.MATRIX), resolved, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                onText(span)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onText(target())
            })
            start()
        }
    }

    /** Смена языка: «матричный» дождь и расшифровка текстов сверху вниз. */
    private fun playLanguageTransition() {
        switching = true
        var index = 0
        val step = 90L
        val length = 520L
        fun add(target: () -> String, onText: (CharSequence) -> Unit) {
            scramble(index * step, length, target, onText)
            index++
        }
        add({ chipMessage() }) { statusLabel.text = it }
        add({ getString(R.string.card_title) }) { cardTitle.text = it }
        add({ signalMessage() }) { signalText.text = it }
        add({ getString(R.string.row_left) }) { leftRow.title = it.toString() }
        add({ getString(R.string.row_right) }) { rightRow.title = it.toString() }
        add({ getString(R.string.row_case) }) { caseRow.title = it.toString() }
        if (headsetPct != null) add({ headsetMessage() }) { headsetText.text = it }
        add({ getString(R.string.btn_diagnostics) }) { diagButton.text = it }
        add({ getString(R.string.btn_refresh) }) { refreshButton.text = it }
        add({ getString(R.string.hint_case) }) { hintText.text = it }
        if (debugLabel.visibility == View.VISIBLE) add({ debugMessage() }) { debugLabel.text = it }
        add({ getString(R.string.disclaimer) }) { disclaimerText.text = it }

        val total = (index - 1) * step + length
        rain.play(total + 200)
        handler.postDelayed({
            switching = false
            renderStatus()
            renderDebug()
        }, total + 50)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LANG, DEFAULT_LANG) ?: DEFAULT_LANG
        applyLocale(lang)
        buildUi()
        setStatus(R.string.status_searching)
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions()) startScan() else requestPermissions(neededPermissions(), 1)
        handler.post(signalWatchdog)
        registerHeadsetReceiver()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(headsetReceiver) } catch (_: Exception) {}
        stopScan()
        handler.removeCallbacks(signalWatchdog)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (hasPermissions()) startScan() else setStatus(R.string.status_need_permission)
    }

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions() = neededPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startScan() {
        if (scanning) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus(R.string.status_bt_off)
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled) {
            setStatus(R.string.status_location_off)
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(1)
            .build()
        try {
            adapter.bluetoothLeScanner.startScan(null, settings, callback)
            scanning = true
            lastSeen = System.currentTimeMillis()
            setStatus(R.string.status_searching)
        } catch (e: SecurityException) {
            setStatus(R.string.status_no_permission)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        try {
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: SecurityException) {}
        scanning = false
    }

    /**
     * Разбор рекламного пакета Apple типа 0x07 (proximity pairing).
     * Байты данных производителя (без идентификатора компании):
     * 0 — тип, 1 — длина, 3–4 — модель, 5 — статус,
     * 6 — заряд наушников (по полубайту), 7 — флаги зарядки (старший полубайт) и заряд кейса (младший).
     * Заряд 0–10 соответствует 0–100 %, значение 15 означает «нет данных».
     */
    private fun parse(d: ByteArray) {
        if (d.size < 8) return
        lastSeen = System.currentTimeMillis()

        val model = ((d[3].toInt() and 0xFF) shl 8) or (d[4].toInt() and 0xFF)
        val status = d[5].toInt() and 0xFF
        val b6 = d[6].toInt() and 0xFF
        val b7 = d[7].toInt() and 0xFF

        val flip = (status and 0x20) == 0
        val hi = b6 shr 4
        val lo = b6 and 0x0F
        val leftV = if (flip) hi else lo
        val rightV = if (flip) lo else hi
        val caseV = b7 and 0x0F
        val cs = b7 shr 4
        val chargeL = if (flip) (cs and 2) != 0 else (cs and 1) != 0
        val chargeR = if (flip) (cs and 1) != 0 else (cs and 2) != 0
        val chargeC = (cs and 4) != 0

        modelTitle.text = when (model) {
            0x0220 -> "AirPods"
            0x0F20 -> "AirPods 2"
            0x1320 -> "AirPods 3"
            0x0E20 -> "AirPods Pro"
            0x1420 -> "AirPods Pro 2"
            else -> "AirPods"
        }
        setStatus(R.string.status_connected, true)
        leftRow.setLevel(percent(leftV), chargeL)
        rightRow.setLevel(percent(rightV), chargeR)
        caseRow.setLevel(percent(caseV), chargeC)
    }

    private fun percent(raw: Int): Int? = if (raw in 0..10) raw * 10 else null

    private fun headsetMessage() = headsetPct?.let { getString(R.string.headset_level, it) } ?: ""

    private fun showHeadsetLevel(pct: Int) {
        headsetPct = pct
        headsetText.visibility = View.VISIBLE
        if (!switching) headsetText.text = headsetMessage()
    }

    private fun debugMessage(): String {
        if (total == 0 && headsetEvents == 0) return getString(R.string.debug_empty)
        val t = types.entries.joinToString(", ") { String.format("%02X", it.key) + "x" + it.value }
        val main = getString(R.string.debug_main, total, apple, pairing, t)
        return if (headsetEvents > 0) main + " | " + getString(R.string.debug_headset, headsetEvents, lastCmd) else main
    }

    private fun renderDebug() {
        if (!switching) debugLabel.text = debugMessage()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setStatus(res: Int, live: Boolean = false, arg: Int? = null) {
        statusRes = res
        statusArg = arg
        statusLive = live
        renderStatus()
    }

    private fun signalMessage(): String = statusArg?.let { getString(statusRes, it) } ?: getString(statusRes)

    private fun chipMessage(): String = getString(if (statusLive) R.string.chip_live else R.string.chip_searching)

    private fun renderStatus() {
        if (!::signalText.isInitialized) return
        if (!switching) {
            signalText.text = signalMessage()
            statusLabel.text = chipMessage()
        }
        (statusDot.background as GradientDrawable).setColor(if (statusLive) Palette.LIVE else Palette.ACCENT)
    }

    private fun tv(
        text: CharSequence, sp: Float, color: Int,
        family: String = "sans-serif", style: Int = Typeface.NORMAL, spacing: Float = 0f
    ) = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        typeface = Typeface.create(family, style)
        letterSpacing = spacing
        includeFontPadding = false
    }

    private fun mono(text: String, sp: Float = 10f, color: Int = Palette.MUTED, bold: Boolean = false) =
        tv(text, sp, color, "monospace", if (bold) Typeface.BOLD else Typeface.NORMAL, 0.14f)
            .apply { isAllCaps = true }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun pill(text: String, bg: Int, fg: Int, stroke: Int?) =
        tv(text, 14f, fg, "sans-serif-medium").apply {
            setPadding(dp(20), dp(13), dp(20), dp(13))
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat()
                setColor(bg)
                if (stroke != null) setStroke(dp(1), stroke)
            }
        }

    private fun languageToggle() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(3), dp(3), dp(3), dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dp(100).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Palette.LINE)
        }
        for ((code, label) in listOf("en" to "EN", "ru" to "RU")) {
            val active = code == lang
            val button = mono(label, 10f, if (active) Palette.ON_DARK else Palette.MUTED, true).apply {
                setPadding(dp(9), dp(6), dp(9), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(100).toFloat()
                    setColor(if (active) Palette.INK else Color.TRANSPARENT)
                }
                setOnClickListener { setLanguage(code) }
            }
            langButtons[code] = button
            addView(button)
        }
    }

    private fun darkLine() = View(this).apply { setBackgroundColor(Palette.LINE_DARK) }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }

        // Верхняя «плавающая» панель
        val logo = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Palette.INK)
            }
            addView(View(this@MainActivity).apply { background = oval(Palette.ACCENT) },
                FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER))
        }
        statusDot = View(this).apply { background = oval(Palette.ACCENT) }
        statusLabel = mono(getString(R.string.chip_searching), 10f, Palette.ON_DARK, true)
        val statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat()
                setColor(Palette.INK)
            }
            addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })
            addView(statusLabel)
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat()
                setColor(Palette.BAR)
                setStroke(dp(1), Palette.LINE)
            }
            addView(logo, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(mono("AirPods", 12f, Palette.INK, true),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            addView(languageToggle(), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(statusPill)
        }
        col.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        // Тёмная карточка
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(26), dp(24), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF3B2B23.toInt(), 0xFF1F1D1B.toInt(), Palette.INK)
            ).apply { cornerRadius = dp(36).toFloat() }
        }
        cardTitle = mono(getString(R.string.card_title), 10f, Palette.MUTED_DARK)
        card.addView(cardTitle)
        modelTitle = tv("AirPods", 34f, Palette.ON_DARK, "sans-serif", Typeface.BOLD, -0.03f)
        card.addView(modelTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        signalText = tv(getString(R.string.status_searching), 13f, Palette.MUTED_DARK)
        card.addView(signalText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        leftRow = BatteryRow(this, getString(R.string.row_left))
        rightRow = BatteryRow(this, getString(R.string.row_right))
        caseRow = BatteryRow(this, getString(R.string.row_case))
        card.addView(leftRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        card.addView(darkLine(), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(14); bottomMargin = dp(14) })
        card.addView(rightRow, LinearLayout.LayoutParams(-1, -2))
        card.addView(darkLine(), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(14); bottomMargin = dp(14) })
        card.addView(caseRow, LinearLayout.LayoutParams(-1, -2))

        headsetText = mono("", 10f, Palette.MUTED_DARK).apply { visibility = View.GONE; isAllCaps = false }
        card.addView(headsetText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })

        refreshButton = pill(getString(R.string.btn_refresh), Palette.BG, Palette.INK, Palette.ON_DARK).apply {
            setOnClickListener { stopScan(); startScan() }
        }
        diagButton = pill(getString(R.string.btn_diagnostics), Color.TRANSPARENT, Palette.ON_DARK, Palette.LINE_DARK).apply {
            setOnClickListener {
                debugLabel.visibility = if (debugLabel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(diagButton)
            addView(refreshButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        }
        card.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        // Под карточкой
        hintText = mono(getString(R.string.hint_case), 10f)
        col.addView(hintText,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); marginStart = dp(8); marginEnd = dp(8) })
        debugLabel = mono(getString(R.string.debug_empty), 10f).apply { visibility = View.GONE; isAllCaps = false }
        col.addView(debugLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); marginStart = dp(8); marginEnd = dp(8) })
        disclaimerText = mono(getString(R.string.disclaimer), 9f)
        col.addView(disclaimerText,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); marginStart = dp(8); marginEnd = dp(8) })

        scroll.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll)
        rain = MatrixRainView(this)
        root.addView(rain, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }
}
