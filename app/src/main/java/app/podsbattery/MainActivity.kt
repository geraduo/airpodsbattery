package app.podsbattery

import android.Manifest
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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

private const val DEVELOPER = "geraduo"
private const val REPO_URL = "https://github.com/geraduo/airpodsbattery"

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
    private lateinit var developerText: TextView
    private lateinit var updatedText: TextView
    private lateinit var statusPill: LinearLayout
    private lateinit var developerRow: LinearLayout
    private lateinit var rain: MatrixRainView
    private val langButtons = mutableMapOf<String, TextView>()

    private var lang = Prefs.DEFAULT_LANG
    private var localizedRes: Resources? = null
    private var switching = false

    private var statusRes = R.string.status_searching
    private var statusArg: Int? = null
    private var statusLive = false
    private var headsetPct: Int? = null

    private var lastState: PodsState? = null
    private var savedState: PodsState? = null
    private var lastDataAt = 0L

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
            val data = result.scanRecord?.getManufacturerSpecificData(PodsParser.APPLE_COMPANY_ID)
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
            renderUpdated()
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
        Prefs.setLang(this, code)
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

    /** Покадровый таймер на SystemClock: не зависит от системной настройки «Масштаб анимации». */
    private fun runTimed(delay: Long, duration: Long, onFrame: (Float) -> Unit, onEnd: () -> Unit) {
        val begin = SystemClock.uptimeMillis() + delay
        val tick = object : Runnable {
            override fun run() {
                val f = ((SystemClock.uptimeMillis() - begin).toFloat() / duration).coerceIn(0f, 1f)
                onFrame(f)
                if (f >= 1f) onEnd() else handler.postDelayed(this, 16)
            }
        }
        handler.postDelayed(tick, delay)
    }

    /** Текст «расшифровывается» слева направо из случайных символов; до своей очереди остаётся прежним. */
    private fun scramble(
        delay: Long, duration: Long, target: () -> String, tile: View?, onText: (CharSequence) -> Unit
    ) {
        val rnd = java.util.Random()
        var started = false
        var fromWidth = 0
        var toWidth = 0
        runTimed(delay, duration, { f ->
            val text = target()
            if (tile != null && !started) {
                // Узнаём будущую ширину плитки по итоговому тексту, чтобы растянуть её плавно, а не рывком
                started = true
                fromWidth = tile.width
                onText(text)
                tile.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                tile.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                toWidth = tile.measuredWidth
            }
            val resolved = (f * text.length).toInt()
            val out = StringBuilder(text)
            for (i in resolved until out.length) {
                if (!out[i].isWhitespace() && out[i] !in "·|%") out.setCharAt(i, scrambleGlyphs[rnd.nextInt(scrambleGlyphs.length)])
            }
            val span = SpannableString(out)
            if (resolved < out.length) {
                span.setSpan(ForegroundColorSpan(Palette.MATRIX), resolved, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            onText(span)
            if (tile != null && fromWidth > 0) {
                val e = f * f * (3f - 2f * f)
                tile.layoutParams.width = (fromWidth + (toWidth - fromWidth) * e).toInt()
                tile.requestLayout()
            }
        }, {
            onText(target())
            if (tile != null) {
                tile.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                tile.requestLayout()
            }
        })
    }

    /** Смена языка: «матричный» дождь и расшифровка текстов сверху вниз. */
    private fun playLanguageTransition() {
        switching = true
        var index = 0
        val step = 90L
        val length = 520L
        fun add(target: () -> String, tile: View? = null, onText: (CharSequence) -> Unit) {
            scramble(index * step, length, target, tile, onText)
            index++
        }
        add({ chipMessage() }, statusPill) { statusLabel.text = it }
        add({ getString(R.string.card_title) }) { cardTitle.text = it }
        add({ signalMessage() }) { signalText.text = it }
        if (updatedText.visibility == View.VISIBLE) add({ updatedMessage() }) { updatedText.text = it }
        add({ getString(R.string.row_left) }) { leftRow.title = it.toString() }
        add({ getString(R.string.row_right) }) { rightRow.title = it.toString() }
        add({ getString(R.string.row_case) }) { caseRow.title = it.toString() }
        if (headsetPct != null) add({ headsetMessage() }) { headsetText.text = it }
        add({ getString(R.string.btn_diagnostics) }, diagButton) { diagButton.text = it }
        add({ getString(R.string.btn_refresh) }, refreshButton) { refreshButton.text = it }
        add({ getString(R.string.hint_case) }) { hintText.text = it }
        if (debugLabel.visibility == View.VISIBLE) add({ debugMessage() }) { debugLabel.text = it }
        add({ developerMessage() }, developerRow) { developerText.text = if (it is SpannableString) it else styleDeveloper(it.toString()) }
        add({ disclaimerMessage() }) { disclaimerText.text = it }

        val total = (index - 1) * step + length
        rain.play(total + 200)
        handler.postDelayed({
            switching = false
            renderStatus()
            renderDebug()
            renderUpdated()
        }, total + 50)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = Prefs.lang(this)
        applyLocale(lang)
        buildUi()
        Prefs.loadLast(this)?.let { (state, time) ->
            lastState = state
            savedState = state
            lastDataAt = time
            applyState(state)
        }
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
        lastState?.let { if (lastDataAt > 0) Prefs.saveLast(this, it, lastDataAt) }
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

    private fun parse(d: ByteArray) {
        val state = PodsParser.parse(d) ?: return
        val now = System.currentTimeMillis()
        lastSeen = now
        lastDataAt = now
        lastState = state
        if (state != savedState) {
            savedState = state
            Prefs.saveLast(this, state, now)
        }
        applyState(state)
        setStatus(R.string.status_connected, true)
    }

    private fun applyState(state: PodsState) {
        modelTitle.text = state.model
        leftRow.setLevel(state.left, state.chargingLeft)
        rightRow.setLevel(state.right, state.chargingRight)
        caseRow.setLevel(state.case, state.chargingCase)
    }

    private fun updatedMessage(): String {
        val minutes = ((System.currentTimeMillis() - lastDataAt) / 60_000).toInt().coerceAtLeast(0)
        return when {
            minutes < 1 -> getString(R.string.updated_now)
            minutes < 60 -> resources.getQuantityString(R.plurals.updated_minutes, minutes, minutes)
            minutes < 24 * 60 -> {
                val hours = minutes / 60
                resources.getQuantityString(R.plurals.updated_hours, hours, hours)
            }
            else -> {
                val days = minutes / (24 * 60)
                resources.getQuantityString(R.plurals.updated_days, days, days)
            }
        }
    }

    /** Пока живых данных нет, показываем последние значения приглушёнными и пишем, как давно они получены. */
    private fun renderUpdated() {
        if (!::updatedText.isInitialized) return
        val stale = lastDataAt > 0 && !statusLive
        updatedText.visibility = if (stale) View.VISIBLE else View.INVISIBLE
        val alpha = if (stale) 0.55f else 1f
        leftRow.alpha = alpha
        rightRow.alpha = alpha
        caseRow.alpha = alpha
        if (stale && !switching) updatedText.text = updatedMessage()
    }

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

    private fun developerMessage() = getString(R.string.developed_by) + " " + DEVELOPER

    /** Имя разработчика выделяется цветом и жирным начертанием. */
    private fun styleDeveloper(text: String): CharSequence {
        val out = SpannableString(text)
        val from = text.indexOf(DEVELOPER)
        if (from >= 0) {
            val to = from + DEVELOPER.length
            out.setSpan(ForegroundColorSpan(Palette.INK), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(StyleSpan(Typeface.BOLD), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun openRepo() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
        } catch (_: Exception) {}
    }

    private fun disclaimerMessage(): String {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { null }
        return getString(R.string.disclaimer) + if (version != null) "  ·  v$version" else ""
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
        renderUpdated()
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
            isSingleLine = true
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
        statusLabel = mono(getString(R.string.chip_searching), 10f, Palette.ON_DARK, true).apply { isSingleLine = true }
        statusPill = LinearLayout(this).apply {
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
        updatedText = mono("", 10f, Palette.MUTED_DARK).apply { visibility = View.INVISIBLE }
        card.addView(updatedText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

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
        developerText = tv(styleDeveloper(developerMessage()), 12f, Palette.MUTED).apply { isSingleLine = true }
        developerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat()
                setColor(Palette.BAR)
                setStroke(dp(1), Palette.LINE)
            }
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_github) },
                LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(10) })
            addView(developerText)
            setOnClickListener { openRepo() }
        }
        col.addView(developerRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22); marginStart = dp(8) })

        disclaimerText = mono(disclaimerMessage(), 9f)
        col.addView(disclaimerText,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); marginStart = dp(8); marginEnd = dp(8) })

        scroll.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll)
        rain = MatrixRainView(this)
        root.addView(rain, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }
}
