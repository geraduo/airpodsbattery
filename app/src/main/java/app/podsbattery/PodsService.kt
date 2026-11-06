package app.podsbattery

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews

class PodsService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var manager: NotificationManager
    private var scanning = false
    private var state: PodsState? = null
    private var dataAt = 0L
    private var receiverRegistered = false
    private val stabilizer = PodsStabilizer()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(PodsParser.APPLE_COMPANY_ID) ?: return
            if (data.isEmpty() || data[0] != 0x07.toByte() || result.rssi < -85) return
            val raw = PodsParser.parse(data) ?: return
            val now = System.currentTimeMillis()
            val parsed = stabilizer.push(raw, now) ?: return
            val changed = parsed != state
            val wasLive = isLive(now)
            state = parsed
            dataAt = now
            if (changed) Prefs.saveLast(this@PodsService, parsed, now)
            if (changed || !wasLive) notifyNow()
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> restartScan()
                    BluetoothAdapter.STATE_OFF -> stopScan()
                }
            } else {
                restartScan()
            }
            notifyNow()
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!scanning) startScan()
            notifyNow()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun adopt() {
        val shared = PodsHub.state ?: return
        val time = PodsHub.dataAt
        if (time < dataAt) return
        val changed = shared != state
        val wasLive = isLive(System.currentTimeMillis())
        state = shared
        dataAt = time
        if (changed || !wasLive) notifyNow()
    }

    override fun onCreate() {
        super.onCreate()
        PodsHub.listener = { adopt() }
        manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notif_channel_desc)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
        Prefs.loadLast(this)?.let { (saved, time) ->
            state = saved
            dataAt = time
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setPopup(this, false)
            shutDown()
            return START_NOT_STICKY
        }
        PodsHub.state?.let { if (PodsHub.dataAt >= dataAt) { state = it; dataAt = PodsHub.dataAt } }
        goForeground(buildNotification())
        if (!Prefs.popup(this) || !hasPermissions()) {
            shutDown()
            return START_NOT_STICKY
        }
        if (!receiverRegistered) {
            try {
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                filter.addAction(LocationManager.MODE_CHANGED_ACTION)
                filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
                else registerReceiver(bluetoothReceiver, filter)
                receiverRegistered = true
            } catch (_: Exception) {
            }
        }
        startScan()
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, REFRESH_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        PodsHub.listener = null
        handler.removeCallbacks(refresh)
        stopScan()
        if (receiverRegistered) {
            try { unregisterReceiver(bluetoothReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun shutDown() {
        handler.removeCallbacks(refresh)
        stopScan()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (_: Exception) {
            stopSelf()
        }
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

    private fun adapter(): BluetoothAdapter? = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun locationOff(): Boolean =
        Build.VERSION.SDK_INT >= 28 && !(getSystemService(LOCATION_SERVICE) as LocationManager).isLocationEnabled

    private fun startScan() {
        if (scanning || !hasPermissions() || locationOff()) return
        val bt = adapter() ?: return
        if (!bt.isEnabled) return
        val filter = ScanFilter.Builder()
            .setManufacturerData(PodsParser.APPLE_COMPANY_ID, byteArrayOf(0x07), byteArrayOf(0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(1)
            .build()
        try {
            bt.bluetoothLeScanner?.startScan(listOf(filter), settings, callback)
            scanning = true
        } catch (_: Exception) {
        }
    }

    private fun restartScan() {
        stopScan()
        startScan()
    }

    private fun stopScan() {
        if (!scanning) return
        try {
            adapter()?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {
        }
        scanning = false
    }

    private fun isLive(now: Long) = state != null && now - dataAt < LIVE_MS

    private fun notifyNow() {
        try {
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun blockedMessage(ctx: Context): String? = when {
        !hasPermissions() -> ctx.getString(R.string.status_no_permission)
        adapter()?.isEnabled != true -> ctx.getString(R.string.status_bt_off)
        locationOff() -> ctx.getString(R.string.status_location_off)
        else -> null
    }

    private fun updatedMessage(ctx: Context): String {
        val minutes = ((System.currentTimeMillis() - dataAt) / 60_000).toInt().coerceAtLeast(0)
        val res = ctx.resources
        return when {
            minutes < 1 -> ctx.getString(R.string.updated_now)
            minutes < 60 -> res.getQuantityString(R.plurals.updated_minutes, minutes, minutes)
            minutes < 24 * 60 -> {
                val hours = minutes / 60
                res.getQuantityString(R.plurals.updated_hours, hours, hours)
            }
            else -> {
                val days = minutes / (24 * 60)
                res.getQuantityString(R.plurals.updated_days, days, days)
            }
        }
    }

    private fun dimmed(color: Int): Int {
        val keep = 0.55f
        val back = 0xFF2A2825.toInt()
        fun mix(shift: Int): Int {
            val c = (color shr shift) and 0xFF
            val b = (back shr shift) and 0xFF
            return (c * keep + b * (1f - keep)).toInt()
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun valueText(pct: Int?, dim: Boolean): CharSequence {
        if (pct == null) {
            return SpannableString("—").apply {
                setSpan(ForegroundColorSpan(Palette.MUTED_DARK), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val number = pct.toString()
        val out = SpannableStringBuilder(number).append("%")
        if (dim) out.setSpan(ForegroundColorSpan(dimmed(Palette.ON_DARK)), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(RelativeSizeSpan(0.55f), number.length, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(Palette.MUTED_DARK), number.length, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    private fun itemText(label: String, pct: Int?, charging: Boolean, dim: Boolean): CharSequence {
        val out = SpannableStringBuilder(label).append(' ')
        out.setSpan(
            ForegroundColorSpan(if (dim) dimmed(Palette.MUTED_DARK) else Palette.MUTED_DARK),
            0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val start = out.length
        out.append(if (pct != null) "$pct%" else "—")
        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val valueColor = if (pct == null) Palette.MUTED_DARK else if (dim) dimmed(Palette.ON_DARK) else Palette.ON_DARK
        out.setSpan(ForegroundColorSpan(valueColor), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (charging) {
            val from = out.length
            out.append(" ⚡")
            out.setSpan(ForegroundColorSpan(Palette.LIVE), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun segments(pct: Int?, dim: Boolean): Bitmap {
        val d = resources.displayMetrics.density
        val w = (100 * d).toInt()
        val h = (6 * d).toInt().coerceAtLeast(6)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TRACK_DARK }
        val fillColor = Palette.forLevel(pct ?: 100)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dim) dimmed(fillColor) else fillColor }
        val n = 10
        val gap = 2f * d
        val segW = (w - gap * (n - 1)) / n
        val radius = 1.5f * d
        val rect = RectF()
        for (i in 0 until n) {
            val left = i * (segW + gap)
            rect.set(left, 0f, left + segW, h.toFloat())
            canvas.drawRoundRect(rect, radius, radius, track)
            if (pct != null && pct >= (i + 1) * 10) canvas.drawRoundRect(rect, radius, radius, fill)
        }
        return bmp
    }

    private fun fillColumn(
        rv: RemoteViews, ctx: Context, label: Int, value: Int, bar: Int, charge: Int,
        name: String, pct: Int?, charging: Boolean, dim: Boolean
    ) {
        rv.setTextViewText(label, name)
        rv.setTextColor(label, if (dim) dimmed(Palette.MUTED_DARK) else Palette.MUTED_DARK)
        rv.setTextViewText(value, valueText(pct, dim))
        rv.setImageViewBitmap(bar, segments(pct, dim))
        rv.setTextViewText(charge, ctx.getString(R.string.charging))
        rv.setViewVisibility(charge, if (charging) View.VISIBLE else View.INVISIBLE)
    }

    private fun buildNotification(): Notification =
        try {
            buildRichNotification()
        } catch (_: Exception) {
            buildPlainNotification()
        }

    private fun buildPlainNotification(): Notification {
        val s = state
        val text = if (s == null) getString(R.string.status_searching) else listOf(
            getString(R.string.short_left) to s.left,
            getString(R.string.short_right) to s.right,
            getString(R.string.short_case) to s.case
        ).joinToString(" · ") { (label, pct) -> label + " " + (pct?.let { "$it%" } ?: "—") }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pods)
            .setContentTitle(s?.model ?: "AirPods")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun buildRichNotification(): Notification {
        val ctx = Prefs.localized(this)
        val now = System.currentTimeMillis()
        val s = state
        val live = isLive(now)
        val blocked = blockedMessage(ctx)
        val title = s?.model ?: "AirPods"
        val status = blocked ?: when {
            s == null -> ctx.getString(R.string.status_searching)
            live -> ctx.getString(R.string.chip_live)
            else -> updatedMessage(ctx)
        }
        val dim = s != null && !live

        val shortLeft = ctx.getString(R.string.short_left)
        val shortRight = ctx.getString(R.string.short_right)
        val shortCase = ctx.getString(R.string.short_case)

        val collapsed = RemoteViews(packageName, R.layout.notif_collapsed)
        val expanded = RemoteViews(packageName, R.layout.notif_expanded)

        if (s == null) {
            collapsed.setTextViewText(R.id.n_title, status)
            collapsed.setViewVisibility(R.id.n_c_left, View.GONE)
            collapsed.setViewVisibility(R.id.n_c_right, View.GONE)
            collapsed.setViewVisibility(R.id.n_c_case, View.GONE)
        } else {
            collapsed.setTextViewText(R.id.n_title, title)
            collapsed.setTextViewText(R.id.n_c_left, itemText(shortLeft, s.left, s.chargingLeft, dim))
            collapsed.setTextViewText(R.id.n_c_right, itemText(shortRight, s.right, s.chargingRight, dim))
            collapsed.setTextViewText(R.id.n_c_case, itemText(shortCase, s.case, s.chargingCase, dim))
        }

        expanded.setTextViewText(R.id.n_title, title)
        expanded.setTextViewText(R.id.n_status, status)
        expanded.setTextColor(R.id.n_status, if (live && blocked == null) Palette.LIVE else Palette.MUTED_DARK)
        fillColumn(
            expanded, ctx, R.id.n_left_label, R.id.n_left_value, R.id.n_left_bar, R.id.n_left_charge,
            ctx.getString(R.string.row_left), s?.left, s?.chargingLeft == true, dim
        )
        fillColumn(
            expanded, ctx, R.id.n_right_label, R.id.n_right_value, R.id.n_right_bar, R.id.n_right_charge,
            ctx.getString(R.string.row_right), s?.right, s?.chargingRight == true, dim
        )
        fillColumn(
            expanded, ctx, R.id.n_case_label, R.id.n_case_value, R.id.n_case_bar, R.id.n_case_charge,
            ctx.getString(R.string.row_case), s?.case, s?.chargingCase == true, dim
        )

        val summary = if (s == null) status else listOf(
            shortLeft to s.left, shortRight to s.right, shortCase to s.case
        ).joinToString(" · ") { (label, pct) -> label + " " + (pct?.let { "$it%" } ?: "—") }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PodsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_pods), ctx.getString(R.string.notif_stop), stop
        ).build()

        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pods)
            .setColor(Palette.ACCENT)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setContentIntent(open)
            .addAction(stopAction)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "battery"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.podsbattery.STOP"
        private const val LIVE_MS = 20_000L
        private const val REFRESH_MS = 30_000L
    }
}
