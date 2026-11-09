package app.podsbattery

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.transition.TransitionManager
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
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
    private lateinit var cardTitle: TextView
    private lateinit var hintText: TextView
    private lateinit var disclaimerText: TextView
    private lateinit var developerText: TextView
    private lateinit var updatedText: TextView
    private lateinit var statusPill: LinearLayout
    private lateinit var host: FrameLayout
    private lateinit var spark: SparkView
    private lateinit var pattern: PatternView
    private var transitionGen = 0
    private var shownThemeDark = Palette.dark
    private var themeGen = 0
    private lateinit var developerRow: LinearLayout
    private lateinit var rain: MatrixRainView
    private val langButtons = mutableMapOf<String, TextView>()
    private lateinit var langPill: JellyPill

    private var lang = Prefs.DEFAULT_LANG
    private var localizedRes: Resources? = null
    private var switching = false
    private var statusAnimating = false
    private var gridGesture = false
    private lateinit var cardView: View
    private lateinit var topBarView: View
    private lateinit var contentColumn: LinearLayout
    private var retryAt = 0L
    private lateinit var popupToggle: TextView
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_POPUP) renderPopupToggle()
    }

    private var statusRes = R.string.status_searching
    private var statusArg: Int? = null
    private var statusLive = false
    private var headsetPct: Int? = null

    private var lastState: PodsState? = null
    private var savedState: PodsState? = null
    private var lastDataAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val touchLoc = IntArray(2)
    private var shownDotColor = 0
    private var lastSeen = 0L
    private var scanning = false

    private var lastRestart = 0L
    private val textShimmerMatrix = Matrix()
    private var textShimmer: LinearGradient? = null
    private var textShimmerOn = false
    private val textShimmerTick = object : Runnable {
        override fun run() {
            if (!textShimmerOn || !::updatedText.isInitialized) return
            stepTextShimmer()
            updatedText.postOnAnimation(this)
        }
    }
    private val stabilizer = PodsStabilizer()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(PodsParser.APPLE_COMPANY_ID) ?: return
            if (data.isNotEmpty() && data[0] == 0x07.toByte() && result.rssi >= -85) parse(data)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            retryAt = System.currentTimeMillis() + 10_000
            setStatus(R.string.status_scan_error, arg = errorCode)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            stopScan()
            if (hasPermissions()) startScan()
        }
    }

    private fun registerStateReceiver() {
        val f = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        f.addAction(LocationManager.MODE_CHANGED_ACTION)
        f.addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, f, Context.RECEIVER_EXPORTED)
            else registerReceiver(stateReceiver, f)
        } catch (_: Exception) {}
    }

    private val signalWatchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (!scanning && now >= retryAt && hasPermissions()) {
                retryAt = now + 5000
                startScan()
            }
            if (scanning && now - lastSeen > 8000) {
                setStatus(R.string.status_no_signal)
            }
            if (scanning && now - lastSeen > 30_000 && now - lastRestart > 30_000) {
                lastRestart = now
                stopScan()
                startScan(true)
            }
            renderUpdated()
            handler.postDelayed(this, 1000)
        }
    }

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val cmd = i.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD) ?: return
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

    override fun getResources(): Resources = localizedRes ?: super.getResources()

    private fun applyLocale(code: String) {
        val config = Configuration(super.getResources().configuration)
        config.setLocale(Locale(code))
        localizedRes = createConfigurationContext(config).resources
    }

    private fun setLanguage(code: String) {
        if (code == lang) return
        lang = code
        Prefs.setLang(this, code)
        applyLocale(code)
        updateLanguageToggle(true)
        playLanguageTransition()
    }

    private fun updateLanguageToggle(animate: Boolean = false) {
        for ((code, button) in langButtons) {
            val active = code == lang
            val textTo = if (active) Palette.PILL_TEXT else Palette.MUTED
            (button.tag as? ValueAnimator)?.cancel()
            if (!animate) {
                button.setTextColor(textTo)
                continue
            }
            val textFrom = button.currentTextColor
            val evaluator = ArgbEvaluator()
            val anim = ValueAnimator.ofFloat(0f, 1f)
            anim.duration = 320
            anim.addUpdateListener {
                button.setTextColor(evaluator.evaluate(it.animatedFraction, textFrom, textTo) as Int)
            }
            button.tag = anim
            anim.start()
        }
        if (::langPill.isInitialized) {
            val target = langButtons[lang]
            if (target != null && target.width > 0) {
                if (animate) langPill.flowTo(target.left.toFloat(), target.right.toFloat())
                else langPill.snapTo(target.left.toFloat(), target.right.toFloat())
            }
        }
    }

    private val scrambleGlyphs = "01ABCDEFGHJKLMNPQRSTUVWXYZ\$#@%&*+=<>/"

    private fun runTimed(delay: Long, duration: Long, onFrame: (Float) -> Unit, onEnd: () -> Unit) {
        val begin = SystemClock.uptimeMillis() + delay
        val choreographer = Choreographer.getInstance()
        val tick = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val f = ((SystemClock.uptimeMillis() - begin).toFloat() / duration).coerceIn(0f, 1f)
                onFrame(f)
                if (f >= 1f) onEnd() else choreographer.postFrameCallback(this)
            }
        }
        handler.postDelayed({ tick.doFrame(0L) }, delay)
    }

    private fun scramble(
        delay: Long, duration: Long, target: () -> String, tile: View?, onText: (CharSequence) -> Unit,
        onDone: (() -> Unit)? = null, cancelled: (() -> Boolean)? = null, superseded: (() -> Boolean)? = null
    ) {
        val rnd = java.util.Random()
        var started = false
        var snapped = false
        var fromWidth = 0
        var toWidth = 0
        runTimed(delay, duration, { f ->
            if (snapped) return@runTimed
            if (superseded?.invoke() == true) {
                snapped = true
                onText(target())
                if (tile != null) {
                    tile.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    tile.requestLayout()
                }
                return@runTimed
            }
            if (cancelled?.invoke() == true) {
                snapped = true
                onText(target())
                if (tile != null) {
                    tile.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    tile.requestLayout()
                }
                return@runTimed
            }
            val text = target()
            if (tile != null && !started) {
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
            if (superseded?.invoke() == true) return@runTimed
            if (!snapped) {
                onText(target())
                if (tile != null) {
                    tile.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    tile.requestLayout()
                }
            }
            onDone?.invoke()
        })
    }

    private fun playStatusTransition() {
        statusAnimating = true
        val gen = transitionGen
        var pending = 2
        val finish = {
            pending--
            if (pending == 0) {
                statusAnimating = false
                renderStatus()
            }
        }
        scramble(0, 420, { chipMessage() }, statusPill, { statusLabel.text = it }, finish, { statusLive }, { gen != transitionGen })
        scramble(90, 520, { signalMessage() }, null, { signalText.text = it }, finish, { statusLive }, { gen != transitionGen })
    }

    private fun setDotColor(color: Int, animate: Boolean) {
        if (color == shownDotColor) return
        val from = shownDotColor
        shownDotColor = color
        val drawable = statusDot.background as GradientDrawable
        if (!animate) {
            drawable.setColor(color)
            return
        }
        ValueAnimator.ofObject(ArgbEvaluator(), from, color).apply {
            duration = 420
            addUpdateListener { drawable.setColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun playLanguageTransition() {
        val gen = ++transitionGen
        switching = true
        statusAnimating = false
        rain.play(1900L)
        val length = 520L
        var finish = 0L
        fun add(
            anchor: View, target: () -> String, tile: View? = null,
            cancelled: (() -> Boolean)? = null, onText: (CharSequence) -> Unit
        ) {
            val delay = rain.contactDelay(anchor)
            finish = maxOf(finish, delay + length)
            scramble(delay, length, target, tile, onText, null, cancelled) { gen != transitionGen }
        }
        if (!statusLive) add(statusPill, { chipMessage() }, statusPill, { statusLive }) { statusLabel.text = it }
        add(cardTitle, { getString(R.string.card_title) }) { cardTitle.text = it }
        add(popupToggle, { popupPlain() }, popupToggle) { if (it is String) renderPopupToggle() else popupToggle.text = it }
        add(signalText, { signalMessage() }) { signalText.text = it }
        if (updatedText.visibility == View.VISIBLE) add(updatedText, { updatedMessage() }) { updatedText.text = it }
        add(leftRow, { getString(R.string.row_left) }) { leftRow.title = it.toString() }
        add(rightRow, { getString(R.string.row_right) }) { rightRow.title = it.toString() }
        add(caseRow, { getString(R.string.row_case) }) { caseRow.title = it.toString() }
        if (headsetPct != null) add(headsetText, { headsetMessage() }) { headsetText.text = it }
        add(hintText, { getString(R.string.hint_case) }) { hintText.text = it }
        add(developerRow, { developerMessage() }, developerRow) { developerText.text = if (it is SpannableString) it else styleDeveloper(it.toString()) }
        add(disclaimerText, { disclaimerMessage() }) { disclaimerText.text = it }
        for (row in listOf(leftRow, rightRow, caseRow)) {
            handler.postDelayed({
                if (gen == transitionGen) row.chargingText = getString(R.string.charging)
            }, rain.contactDelay(row))
        }

        handler.postDelayed({
            if (gen != transitionGen) return@postDelayed
            switching = false
            renderStatus()
            renderUpdated()
        }, finish + 50)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = Prefs.lang(this)
        applyLocale(lang)
        Palette.apply(Prefs.dark(this))
        shownThemeDark = Palette.dark
        host = FrameLayout(this)
        setContentView(host)
        applySystemBars()
        host.addView(buildUi())
        Prefs.loadLast(this)?.let { (state, time) ->
            lastState = state
            savedState = state
            lastDataAt = time
            applyState(state)
        }
        setStatus(R.string.status_searching, animate = false)
    }

    private fun insideView(view: View, x: Float, y: Float): Boolean {
        view.getLocationOnScreen(touchLoc)
        return x >= touchLoc[0] && x <= touchLoc[0] + view.width &&
            y >= touchLoc[1] && y <= touchLoc[1] + view.height
    }

    private fun insidePanel(x: Float, y: Float): Boolean {
        if (!::cardView.isInitialized || !::topBarView.isInitialized || !::developerRow.isInitialized) return false
        return insideView(cardView, x, y) || insideView(topBarView, x, y) || insideView(developerRow, x, y)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::pattern.isInitialized) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) gridGesture = !insidePanel(ev.rawX, ev.rawY)
            if (gridGesture) {
                pattern.getLocationOnScreen(touchLoc)
                pattern.touch(ev.rawX - touchLoc[0], ev.rawY - touchLoc[1], ev.actionMasked)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun applySystemBars() {
        window.setBackgroundDrawable(ColorDrawable(Palette.BG))
        setBarColors(Palette.BG)
        applyBarIcons()
    }

    private fun setBarColors(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color
    }

    private fun applyBarIcons() {
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (Palette.dark) 0 else flags, flags)
        } else {
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            val current = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (Palette.dark) current and light.inv() else current or light
        }
    }

    private fun toggleTheme() {
        val dark = !Palette.dark
        Prefs.setDark(this, dark)
        Palette.apply(dark)
        rebuildUi()
    }

    private fun rebuildUi() {
        val gen = ++themeGen
        val fresh = buildUi()
        restoreUi()
        fresh.alpha = 0f
        host.addView(fresh)
        window.setBackgroundDrawable(ColorDrawable(Palette.BG))
        val fromBars = window.statusBarColor
        val evaluator = ArgbEvaluator()
        var iconsApplied = false
        runTimed(0, 280, { f ->
            if (gen != themeGen) return@runTimed
            fresh.alpha = f
            setBarColors(evaluator.evaluate(f, fromBars, Palette.BG) as Int)
            if (!iconsApplied && f >= 0.5f) {
                iconsApplied = true
                applyBarIcons()
            }
        }, {
            if (gen != themeGen) return@runTimed
            for (i in host.childCount - 1 downTo 0) {
                if (host.getChildAt(i) !== fresh) host.removeViewAt(i)
            }
            setBarColors(Palette.BG)
            applyBarIcons()
        })
    }

    private fun restoreUi() {
        lastState?.let { applyState(it, animate = false) }
        if (headsetPct != null) {
            headsetText.visibility = View.VISIBLE
            headsetText.text = headsetMessage()
        }
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        Prefs.listen(this, prefsListener)
        renderPopupToggle()
        if (hasPermissions()) {
            startScan()
            startPodsService()
            askNotificationPermission()
        } else {
            requestPermissions(neededPermissions(), 1)
        }
        handler.post(signalWatchdog)
        registerHeadsetReceiver()
        registerStateReceiver()
    }

    override fun onPause() {
        super.onPause()
        Prefs.unlisten(this, prefsListener)
        lastState?.let { if (lastDataAt > 0) Prefs.saveLast(this, it, lastDataAt) }
        try { unregisterReceiver(headsetReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        stopScan()
        handler.removeCallbacks(signalWatchdog)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 2) return
        if (hasPermissions()) {
            startScan()
            startPodsService()
            askNotificationPermission()
        } else {
            setStatus(R.string.status_need_permission)
        }
    }

    private fun popupState(): String = getString(if (Prefs.popup(this)) R.string.popup_on else R.string.popup_off)

    private fun popupPlain(): String = (getString(R.string.popup_label) + " " + popupState()).uppercase()

    private fun renderPopupToggle() {
        if (!::popupToggle.isInitialized) return
        val text = popupPlain()
        val state = popupState().uppercase()
        val styled = SpannableString(text)
        styled.setSpan(
            ForegroundColorSpan(if (Prefs.popup(this)) Palette.LIVE else Palette.MUTED_DARK),
            text.length - state.length, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        popupToggle.text = styled
    }

    private fun togglePopup() {
        val on = !Prefs.popup(this)
        Prefs.setPopup(this, on)
        renderPopupToggle()
        if (on) {
            startPodsService()
            askNotificationPermission(true)
        } else {
            stopService(Intent(this, PodsService::class.java))
        }
    }

    private fun startPodsService() {
        if (!Prefs.popup(this)) {
            stopService(Intent(this, PodsService::class.java))
            return
        }
        if (!hasPermissions()) return
        try {
            startForegroundService(Intent(this, PodsService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun askNotificationPermission(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < 33 || !Prefs.popup(this)) return
        if (!force && Prefs.notifAsked(this)) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        Prefs.setNotifAsked(this)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
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

    private fun startScan(quiet: Boolean = false) {
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
            if (!quiet) {
                val now = System.currentTimeMillis()
                lastSeen = now
                if (!statusLive || now - lastDataAt > 8000) setStatus(R.string.status_searching)
            }
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
        val raw = PodsParser.parse(d) ?: return
        val now = System.currentTimeMillis()
        lastSeen = now
        val state = stabilizer.push(raw, now) ?: return
        val changed = state != lastState
        lastDataAt = now
        lastState = state
        if (state != savedState) {
            savedState = state
            Prefs.saveLast(this, state, now)
        }
        PodsHub.publish(state, now)
        if (changed) applyState(state)
        setStatus(R.string.status_connected, true)
    }

    private fun applyState(state: PodsState, animate: Boolean = true) {
        setTextIfChanged(modelTitle, state.model)
        leftRow.setLevel(state.left, state.chargingLeft, animate)
        rightRow.setLevel(state.right, state.chargingRight, animate)
        caseRow.setLevel(state.case, state.chargingCase, animate)
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

    private fun setUpdatedShimmer(on: Boolean) {
        if (on == textShimmerOn) return
        textShimmerOn = on
        if (on) {
            updatedText.postOnAnimation(textShimmerTick)
        } else {
            updatedText.paint.shader = null
            updatedText.invalidate()
        }
    }

    private fun stepTextShimmer() {
        if (switching) {
            updatedText.paint.shader = null
            updatedText.invalidate()
            return
        }
        val band = dp(64).toFloat()
        val shader = textShimmer ?: run {
            val dim = (Palette.MUTED_DARK and 0x00FFFFFF) or (0xA0 shl 24)
            LinearGradient(
                0f, 0f, band, 0f,
                intArrayOf(dim, Palette.ON_DARK, dim), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            ).also { textShimmer = it }
        }
        val textWidth = updatedText.paint.measureText(updatedText.text.toString())
        val cycle = 3600L
        val t = (SystemClock.uptimeMillis() % cycle) / cycle.toFloat()
        val sweep = (t / 0.75f).coerceAtMost(1f)
        val eased = sweep * sweep * (3f - 2f * sweep)
        textShimmerMatrix.setTranslate(-band + (textWidth + band) * eased, 0f)
        shader.setLocalMatrix(textShimmerMatrix)
        updatedText.paint.shader = shader
        updatedText.invalidate()
    }

    private fun renderUpdated(animate: Boolean = false) {
        if (!::updatedText.isInitialized) return
        val stale = lastDataAt > 0 && !statusLive
        setUpdatedShimmer(stale)
        if (stale) {
            if (updatedText.visibility != View.VISIBLE) {
                updatedText.animate().cancel()
                updatedText.visibility = View.VISIBLE
                if (animate) {
                    updatedText.alpha = 0f
                    updatedText.animate().alpha(1f).setDuration(320)
                } else {
                    updatedText.alpha = 1f
                }
            }
        } else if (updatedText.visibility == View.VISIBLE) {
            updatedText.animate().cancel()
            if (animate) {
                updatedText.animate().alpha(0f).setDuration(320).withEndAction {
                    if (!(lastDataAt > 0 && !statusLive)) updatedText.visibility = View.INVISIBLE
                }
            } else {
                updatedText.visibility = View.INVISIBLE
            }
        }
        if (stale && !switching) setTextIfChanged(updatedText, updatedMessage())
    }

    private fun headsetMessage() = headsetPct?.let { getString(R.string.headset_level, it) } ?: ""

    private fun showHeadsetLevel(pct: Int) {
        headsetPct = pct
        if (headsetText.visibility != View.VISIBLE) {
            TransitionManager.beginDelayedTransition(contentColumn)
            headsetText.visibility = View.VISIBLE
        }
        if (!switching) headsetText.text = headsetMessage()
    }

    private fun setTextIfChanged(view: TextView, text: CharSequence) {
        if (!view.text.contentEquals(text)) view.text = text
    }

    private fun developerMessage() = getString(R.string.developed_by) + " " + DEVELOPER

    private fun styleDeveloper(text: String): CharSequence {
        val out = SpannableString(text)
        val from = text.indexOf(DEVELOPER)
        if (from >= 0) {
            val to = from + DEVELOPER.length
            out.setSpan(ForegroundColorSpan(Palette.TEXT), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

    private fun setStatus(res: Int, live: Boolean = false, arg: Int? = null, animate: Boolean = true) {
        statusRes = res
        statusArg = arg
        statusLive = live
        renderStatus(animate)
    }

    private fun signalMessage(): String = statusArg?.let { getString(statusRes, it) } ?: getString(statusRes)

    private fun chipMessage(): String = getString(if (statusLive) R.string.chip_live else R.string.chip_searching)

    private fun renderStatus(animate: Boolean = false) {
        if (!::signalText.isInitialized) return
        if (!switching && !statusAnimating) {
            val chip = chipMessage()
            val signal = signalMessage()
            if (!statusLabel.text.contentEquals(chip) || !signalText.text.contentEquals(signal)) {
                if (animate && !statusLive) {
                    playStatusTransition()
                } else {
                    if (animate && ::topBarView.isInitialized) TransitionManager.beginDelayedTransition(topBarView as ViewGroup)
                    statusLabel.text = chip
                    signalText.text = signal
                }
            }
        }
        setDotColor(if (statusLive) Palette.LIVE else Palette.ACCENT, animate)
        spark.busy = !statusLive
        renderUpdated(animate)
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

    private fun languageToggle(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pill = JellyPill(this).apply { color = Palette.PILL_BG }
        langPill = pill
        for ((code, label) in listOf("en" to "EN", "ru" to "RU")) {
            val active = code == lang
            val button = mono(label, 10f, if (active) Palette.PILL_TEXT else Palette.MUTED, true).apply {
                setPadding(dp(9), dp(6), dp(9), dp(6))
                setOnClickListener { setLanguage(code) }
            }
            langButtons[code] = button
            row.addView(button)
        }
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val target = langButtons[lang]
            if (!pill.animating && target != null && target.width > 0) {
                pill.snapTo(target.left.toFloat(), target.right.toFloat())
            }
        }
        return FrameLayout(this).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Palette.LINE)
            }
            addView(pill, FrameLayout.LayoutParams(-1, -1))
            addView(row, FrameLayout.LayoutParams(-2, -2))
        }
    }

    private fun stringIn(code: String, id: Int): String {
        val config = Configuration(super.getResources().configuration)
        config.setLocale(Locale(code))
        return createConfigurationContext(config).getString(id)
    }

    private fun lockWidth(view: TextView, texts: List<String>, extra: Int) {
        var widest = 0f
        for (t in texts) widest = maxOf(widest, view.paint.measureText(t))
        view.minWidth = widest.toInt() + extra + 1
        view.gravity = Gravity.CENTER
    }

    private fun darkLine() = View(this).apply { setBackgroundColor(Palette.LINE_DARK) }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        pattern = PatternView(this)
        root.addView(pattern, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }
        contentColumn = col
        val narrow = resources.displayMetrics.widthPixels / resources.displayMetrics.density < 380f

        spark = SparkView(this)
        statusDot = View(this).apply { background = oval(Palette.ACCENT) }
        shownDotColor = Palette.ACCENT
        statusLabel = mono(getString(R.string.chip_searching), 10f, Palette.PILL_TEXT, true).apply { isSingleLine = true }
        lockWidth(
            statusLabel,
            listOf("en", "ru").flatMap { l ->
                listOf(R.string.chip_live, R.string.chip_searching).map { stringIn(l, it).uppercase() }
            },
            0
        )
        statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat()
                setColor(Palette.PILL_BG)
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
            addView(spark, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(mono("AirPods", 12f, Palette.TEXT, true).apply {
                isSingleLine = true
                if (narrow) visibility = View.INVISIBLE
            },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            addView(languageToggle(), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            val themeButton = ThemeButton(this@MainActivity, shownThemeDark).apply { setOnClickListener { toggleTheme() } }
            if (shownThemeDark != Palette.dark) themeButton.post { themeButton.playTo(Palette.dark) }
            shownThemeDark = Palette.dark
            addView(themeButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(statusPill)
        }
        topBarView = topBar
        col.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(26), dp(24), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF47362D.toInt(), 0xFF2A2825.toInt(), 0xFF292826.toInt())
            ).apply {
                cornerRadius = dp(36).toFloat()
                setStroke(dp(1), Palette.CARD_STROKE)
            }
        }
        cardView = card
        cardTitle = mono(getString(R.string.card_title), 10f, Palette.MUTED_DARK)
        popupToggle = mono(popupPlain(), 9f, Palette.ON_DARK, true).apply {
            isAllCaps = false
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat()
                setColor(0x2EFFFFFF)
                setStroke(dp(1), 0x59FFFFFF)
            }
            setOnClickListener { togglePopup() }
        }
        lockWidth(
            popupToggle,
            listOf("en", "ru").flatMap { l ->
                listOf(R.string.popup_on, R.string.popup_off).map {
                    (stringIn(l, R.string.popup_label) + " " + stringIn(l, it)).uppercase()
                }
            },
            dp(22)
        )
        val cardHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cardTitle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(popupToggle, LinearLayout.LayoutParams(-2, -2))
        }
        card.addView(cardHeader, LinearLayout.LayoutParams(-1, -2))
        renderPopupToggle()
        modelTitle = tv("AirPods", 34f, Palette.ON_DARK, "sans-serif", Typeface.BOLD, -0.03f)
        card.addView(modelTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        signalText = tv(getString(R.string.status_searching), 13f, Palette.MUTED_DARK)
        card.addView(signalText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        updatedText = mono("", 10f, Palette.MUTED_DARK).apply { visibility = View.INVISIBLE }
        textShimmerOn = false
        textShimmer = null
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

        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        hintText = mono(getString(R.string.hint_case), 10f)
        col.addView(hintText,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); marginStart = dp(8); marginEnd = dp(8) })
        developerText = tv(styleDeveloper(developerMessage()), 12f, Palette.MUTED).apply { isSingleLine = true }
        lockWidth(developerText, listOf("en", "ru").map { stringIn(it, R.string.developed_by) + " " + DEVELOPER }, 0)
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
        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(developerRow, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(24)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        disclaimerText = mono(disclaimerMessage(), 9f).apply { gravity = Gravity.CENTER }
        col.addView(disclaimerText,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); marginStart = dp(8); marginEnd = dp(8) })

        scroll.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll)
        rain = MatrixRainView(this)
        root.addView(rain, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }
}
