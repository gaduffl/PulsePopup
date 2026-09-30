package com.gaduffl.pulsepopup

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var permStatus: TextView
    private lateinit var btnPerms: Button
    private lateinit var deviceStatus: TextView
    private lateinit var btnDevice: Button
    private lateinit var cbTimer: CheckBox
    private lateinit var btnStart: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private var scanning = false
    private val scanDevices = ArrayList<BluetoothDevice>()
    private val scanLabels = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs.of(this)

        permStatus = findViewById(R.id.permStatus)
        btnPerms = findViewById(R.id.btnPerms)
        deviceStatus = findViewById(R.id.deviceStatus)
        btnDevice = findViewById(R.id.btnDevice)
        cbTimer = findViewById(R.id.cbTimer)
        btnStart = findViewById(R.id.btnStart)

        cbTimer.isChecked = prefs.getBoolean(Prefs.KEY_SHOW_TIMER, true)
        cbTimer.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.KEY_SHOW_TIMER, checked).apply()
        }

        btnPerms.setOnClickListener { requestMissing() }
        btnDevice.setOnClickListener { showScanDialog() }
        btnStart.setOnClickListener { toggleService() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        stopScan()
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        refresh()
    }

    // ---------------------------------------------------------------- permissions

    private fun bluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothPermissions() = bluetoothPermissions().all { granted(it) }

    private fun hasNotificationPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun hasOverlayPermission() = Settings.canDrawOverlays(this)

    private fun requestMissing() {
        val missing = bluetoothPermissions().filter { !granted(it) }.toMutableList()
        if (!hasNotificationPermission()) missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
        } else if (!hasOverlayPermission()) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    // ---------------------------------------------------------------- UI state

    private fun refresh() {
        val btOk = hasBluetoothPermissions() && hasNotificationPermission()
        val overlayOk = hasOverlayPermission()
        permStatus.text = "Bluetooth/Benachrichtigung: ${if (btOk) "✔" else "✘"}\n" +
            "Über anderen Apps einblenden: ${if (overlayOk) "✔" else "✘"}"
        btnPerms.visibility = if (btOk && overlayOk) android.view.View.GONE else android.view.View.VISIBLE
        btnPerms.text = if (!btOk) "Bluetooth-Berechtigungen erteilen" else "Overlay-Berechtigung erteilen"

        val address = prefs.getString(Prefs.KEY_ADDRESS, null)
        val name = prefs.getString(Prefs.KEY_NAME, null)
        deviceStatus.text = if (address == null) "Kein Gerät ausgewählt" else "${name ?: "Gerät"} ($address)"
        btnDevice.isEnabled = btOk

        btnStart.text = if (HeartRateService.running) "Stoppen" else "Starten"
        btnStart.isEnabled = HeartRateService.running || (btOk && overlayOk && address != null)
    }

    private fun toggleService() {
        val intent = Intent(this, HeartRateService::class.java)
        if (HeartRateService.running) {
            stopService(intent)
        } else {
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(this, "Bitte Bluetooth einschalten", Toast.LENGTH_LONG).show()
                return
            }
            startForegroundService(intent)
        }
        handler.postDelayed({ refresh() }, 300)
    }

    // ---------------------------------------------------------------- scanning

    private fun showScanDialog() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bitte Bluetooth einschalten", Toast.LENGTH_LONG).show()
            return
        }
        scanDevices.clear()
        scanLabels.clear()
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, scanLabels)
        val list = ListView(this).apply { this.adapter = listAdapter }
        var showAll = false

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (scanDevices.any { it.address == device.address }) return
                val name = result.scanRecord?.deviceName ?: device.name ?: "Unbenannt"
                scanDevices.add(device)
                scanLabels.add("$name\n${device.address}   (${result.rssi} dBm)")
                listAdapter.notifyDataSetChanged()
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Suche Pulsmesser…")
            .setView(list)
            .setNegativeButton("Abbrechen", null)
            .setNeutralButton("Alle Geräte", null)
            .create()

        fun restart() {
            stopScan()
            scanDevices.clear()
            scanLabels.clear()
            listAdapter.notifyDataSetChanged()
            startScan(callback, showAll)
        }

        list.setOnItemClickListener { _, _, position, _ ->
            val device = scanDevices[position]
            prefs.edit()
                .putString(Prefs.KEY_ADDRESS, device.address)
                .putString(Prefs.KEY_NAME, device.name ?: device.address)
                .apply()
            dialog.dismiss()
            refresh()
        }
        dialog.setOnDismissListener { stopScan() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            showAll = !showAll
            (it as Button).text = if (showAll) "Nur Pulsmesser" else "Alle Geräte"
            restart()
        }
        startScan(callback, showAll)
    }

    private var activeCallback: ScanCallback? = null

    private fun startScan(callback: ScanCallback, all: Boolean) {
        val scanner = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(this, "BLE-Scanner nicht verfügbar", Toast.LENGTH_LONG).show()
            return
        }
        val filters = if (all) emptyList() else listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(HeartRate.SERVICE)).build()
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)
        activeCallback = callback
        scanning = true
    }

    private fun stopScan() {
        val callback = activeCallback ?: return
        if (scanning && hasBluetoothPermissions()) {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner
                ?.stopScan(callback)
        }
        scanning = false
        activeCallback = null
    }
}
