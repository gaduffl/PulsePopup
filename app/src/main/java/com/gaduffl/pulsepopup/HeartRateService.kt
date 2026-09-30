package com.gaduffl.pulsepopup

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView

/**
 * Foreground service: keeps a BLE connection to the heart rate strap (with auto-reconnect)
 * and shows the live value plus an optional count-up timer in a draggable always-on-top overlay.
 */
@SuppressLint("MissingPermission")
class HeartRateService : Service() {

    companion object {
        const val ACTION_STOP = "com.gaduffl.pulsepopup.STOP"
        @Volatile
        var running = false

        private const val CHANNEL_ID = "pulse"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 500L
        private const val STALE_MS = 8000L
        private const val RECONNECT_MS = 3000L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager

    // overlay
    private var overlay: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var heartView: TextView
    private lateinit var bpmView: TextView
    private lateinit var timerView: TextView

    // BLE
    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var deviceName: String = ""
    private var stopped = false
    private var connected = false
    private var lastBpm = 0
    private var lastUpdate = 0L

    // timer
    private var timerRunning = false
    private var timerStartedAt = 0L
    private var timerAccumulated = 0L
    private var timerAutoStartPending = true

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_SHOW_TIMER) main.post { applyTimerVisibility() }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateUi()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = Prefs.of(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createChannel()
        startForegroundCompat(buildNotification("Starte…"))

        address = prefs.getString(Prefs.KEY_ADDRESS, null)
        deviceName = prefs.getString(Prefs.KEY_NAME, null) ?: address ?: ""

        if (address == null || !hasBluetoothPermission() || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        addOverlay()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        main.post(ticker)
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopped = true
        running = false
        main.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        overlay?.let {
            try {
                windowManager.removeView(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        overlay = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- BLE

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun connect() {
        if (stopped) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            setConnected(false, "Bluetooth ist aus")
            scheduleReconnect()
            return
        }
        setConnected(false, "Verbinde mit $deviceName…")
        gatt?.close()
        gatt = try {
            adapter.getRemoteDevice(address).connectGatt(
                this, false, gattCallback, BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: IllegalArgumentException) {
            null
        }
        if (gatt == null) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!stopped) main.postDelayed({ connect() }, RECONNECT_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { setConnected(false, "Verbunden, aktiviere Pulsdaten…") }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                main.post {
                    if (gatt === g) {
                        gatt = null
                        setConnected(false, "Verbindung getrennt, verbinde neu…")
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val characteristic = g.getService(HeartRate.SERVICE)?.getCharacteristic(HeartRate.MEASUREMENT)
            val descriptor = characteristic?.getDescriptor(HeartRate.CCCD)
            if (characteristic == null || descriptor == null) {
                main.post { setConnected(false, "Kein Herzfrequenz-Service am Gerät") }
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(characteristic, true)
            @Suppress("DEPRECATION")
            run {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                main.post { setConnected(true, "Verbunden mit $deviceName") }
            } else {
                main.post { setConnected(false, "Pulsdaten konnten nicht aktiviert werden") }
                g.disconnect()
            }
        }

        // Android 12 and older
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleMeasurement(c.value ?: return)
        }

        // Android 13+
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handleMeasurement(value)
        }
    }

    private fun handleMeasurement(value: ByteArray) {
        val bpm = HeartRate.parse(value)
        if (bpm < 0) return
        main.post { onHeartRate(bpm) }
    }

    private fun onHeartRate(bpm: Int) {
        lastBpm = bpm
        lastUpdate = SystemClock.elapsedRealtime()
        if (bpm > 0 && timerAutoStartPending) {
            timerAutoStartPending = false
            startTimer()
        }
        updateUi()
    }

    private fun setConnected(value: Boolean, status: String) {
        connected = value
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(status))
        updateUi()
    }

    // ---------------------------------------------------------------- Timer

    private fun startTimer() {
        if (timerRunning) return
        timerRunning = true
        timerStartedAt = SystemClock.elapsedRealtime()
        updateUi()
    }

    private fun pauseTimer() {
        if (!timerRunning) return
        timerAccumulated += SystemClock.elapsedRealtime() - timerStartedAt
        timerRunning = false
        updateUi()
    }

    private fun resetTimer() {
        timerRunning = false
        timerAccumulated = 0L
        timerAutoStartPending = false
        updateUi()
    }

    private fun elapsedMs(): Long =
        timerAccumulated + if (timerRunning) SystemClock.elapsedRealtime() - timerStartedAt else 0L

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    // ---------------------------------------------------------------- Overlay

    private fun addOverlay() {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay, null)
        heartView = view.findViewById(R.id.heart)
        bpmView = view.findViewById(R.id.bpm)
        timerView = view.findViewById(R.id.timer)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(Prefs.KEY_X, 24)
            y = prefs.getInt(Prefs.KEY_Y, 200)
        }

        view.setOnTouchListener(DragTouchListener())
        windowManager.addView(view, params)
        overlay = view
        applyTimerVisibility()
        updateUi()
    }

    private fun applyTimerVisibility() {
        if (overlay == null) return
        timerView.visibility =
            if (prefs.getBoolean(Prefs.KEY_SHOW_TIMER, true)) View.VISIBLE else View.GONE
    }

    private fun updateUi() {
        if (overlay == null) return
        val fresh = lastBpm > 0 && SystemClock.elapsedRealtime() - lastUpdate < STALE_MS
        bpmView.text = if (connected && fresh) lastBpm.toString() else "--"
        heartView.setTextColor(if (connected) Color.rgb(0xFF, 0x3B, 0x30) else Color.GRAY)
        timerView.text = formatTime(elapsedMs())
        timerView.alpha = if (timerRunning) 1f else 0.5f
    }

    /** Drag to move, tap to pause/resume the timer, long press to reset it. */
    private inner class DragTouchListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@HeartRateService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            resetTimer()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    longPressed = false
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        moved = true
                        main.removeCallbacks(longPress)
                    }
                    if (moved) {
                        params.x = (startX + dx).toInt().coerceAtLeast(0)
                        params.y = (startY + dy).toInt().coerceAtLeast(0)
                        windowManager.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (moved) {
                        prefs.edit().putInt(Prefs.KEY_X, params.x).putInt(Prefs.KEY_Y, params.y).apply()
                    } else if (!longPressed) {
                        if (timerRunning) pauseTimer() else startTimer()
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }

    // ---------------------------------------------------------------- Notification

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, HeartRateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_heart), "Beenden", stop
                ).build())
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
