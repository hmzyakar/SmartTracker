package com.example.smarttracker

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private var bleGatt: BluetoothGatt? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var bleConnected = false

    // UI Components
    private var statusText: TextView? = null
    private var connectionStatusCard: TextView? = null
    private var bleStatusText: TextView? = null
    private var wifiStatusText: TextView? = null
    private var gsmStatusText: TextView? = null
    private var alarmStatusText: TextView? = null
    private var gpsStatusText: TextView? = null
    private var debugLogText: TextView? = null
    private var debugScrollView: ScrollView? = null
    private var rssiTestText: TextView? = null
    private var startButton: Button? = null
    private var stopButton: Button? = null
    private var alarmSwitch: Switch? = null
    private var silenceButton: Button? = null
    private var clearLogButton: Button? = null
    private var rssiTestButton: Button? = null
    private var setThresholdButton: Button? = null
    private var retryBleButton: Button? = null
    private var retryWifiButton: Button? = null
    private var retryGsmButton: Button? = null

    // State variables
    private var isAlarmEnabled = true
    private var isAlarmActive = false
    private var prefs: SharedPreferences? = null

    // RSSI Test & Calibration
    private var isRssiTestActive = false
    private var currentRssi = 0
    private var rssiThreshold = -70
    private val rssiUpdateHandler = Handler(Looper.getMainLooper())

    // Debug logging with scroll management
    private val debugMessages = mutableListOf<String>()
    private val maxDebugMessages = 50 // Reduced for better performance
    private var shouldAutoScroll = true

    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_TIMEOUT = 30000L

    companion object {
        private const val TAG = "SmartTracker"
        const val BLE_SERVICE_UUID = "12345678-1234-1234-1234-1234567890ab"
        const val BLE_CHAR_UUID = "abcd1234-ab12-cd34-ef00-1234567890ab"
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_ENABLE_BT = 1002
        private const val UDP_PORT = 5000
        private const val CHANNEL_ID = "smarttracker"
        private const val PREF_ALARM_ENABLED = "alarm_enabled"
        private const val PREF_RSSI_THRESHOLD = "rssi_threshold"
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.WAKE_LOCK
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WAKE_LOCK
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializePreferences()
        initializeViews()
        createNotificationChannel()

        SmsReceiver.callback = { title, message ->
            runOnUiThread {
                handleTrackerAlert(title, message)
            }
        }

        if (checkPermissions()) {
            initializeBluetooth()
        } else {
            requestPermissions()
        }

        addDebugLog("📱 SmartTracker initialized")
        updateConnectionStatus()
    }

    private fun initializePreferences() {
        prefs = getSharedPreferences("smarttracker_prefs", Context.MODE_PRIVATE)
        isAlarmEnabled = prefs?.getBoolean(PREF_ALARM_ENABLED, true) ?: true
        rssiThreshold = prefs?.getInt(PREF_RSSI_THRESHOLD, -70) ?: -70
        addDebugLog("⚙️ Settings loaded - Alarm: $isAlarmEnabled, RSSI: $rssiThreshold dBm")
    }

    private fun savePreferences() {
        prefs?.edit()?.apply {
            putBoolean(PREF_ALARM_ENABLED, isAlarmEnabled)
            putInt(PREF_RSSI_THRESHOLD, rssiThreshold)
            apply()
        }
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        connectionStatusCard = findViewById(R.id.connectionStatusCard)
        bleStatusText = findViewById(R.id.bleStatusText)
        wifiStatusText = findViewById(R.id.wifiStatusText)
        gsmStatusText = findViewById(R.id.gsmStatusText)
        alarmStatusText = findViewById(R.id.alarmStatusText)
        gpsStatusText = findViewById(R.id.gpsStatusText)
        debugLogText = findViewById(R.id.debugLogText)
        debugScrollView = findViewById(R.id.debugScrollView)
        rssiTestText = findViewById(R.id.rssiTestText)

        startButton = findViewById<Button>(R.id.startButton)?.apply {
            setOnClickListener { startTracking() }
            isEnabled = false
        }

        stopButton = findViewById<Button>(R.id.stopButton)?.apply {
            setOnClickListener { stopTracking() }
            isEnabled = false
        }

        alarmSwitch = findViewById<Switch>(R.id.alarmSwitch)?.apply {
            isChecked = isAlarmEnabled
            setOnCheckedChangeListener { _, isChecked ->
                toggleAlarm(isChecked)
            }
        }

        silenceButton = findViewById<Button>(R.id.silenceButton)?.apply {
            setOnClickListener { silenceAlarm() }
            isEnabled = false
        }

        clearLogButton = findViewById<Button>(R.id.clearLogButton)?.apply {
            setOnClickListener { clearDebugLog() }
        }

        rssiTestButton = findViewById<Button>(R.id.rssiTestButton)?.apply {
            setOnClickListener { toggleRssiTest() }
            isEnabled = false
        }

        setThresholdButton = findViewById<Button>(R.id.setThresholdButton)?.apply {
            setOnClickListener { setCurrentRssiAsThreshold() }
            isEnabled = false
        }

        retryBleButton = findViewById<Button>(R.id.retryBleButton)?.apply {
            setOnClickListener { retryConnection("bluetooth") }
        }

        retryWifiButton = findViewById<Button>(R.id.retryWifiButton)?.apply {
            setOnClickListener { retryConnection("wifi") }
        }

        retryGsmButton = findViewById<Button>(R.id.retryGsmButton)?.apply {
            setOnClickListener { retryConnection("gsm") }
        }

        updateAlarmUI()
        updateRssiDisplay()
        updateConnectionStatus()
        addDebugLog("✅ UI initialized")
    }

    private fun addDebugLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "[$timestamp] $message"

        debugMessages.add(logMessage)

        if (debugMessages.size > maxDebugMessages) {
            debugMessages.removeAt(0)
        }

        runOnUiThread {
            debugLogText?.text = debugMessages.joinToString("\n")

            // Auto-scroll to bottom only if enabled
            if (shouldAutoScroll) {
                debugScrollView?.post {
                    debugScrollView?.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }

        Log.d(TAG, logMessage)
    }

    private fun clearDebugLog() {
        debugMessages.clear()
        debugLogText?.text = ""
        addDebugLog("🗑️ Debug log cleared")
    }

    private fun toggleAlarm(enabled: Boolean) {
        isAlarmEnabled = enabled
        savePreferences()

        if (enabled) {
            sendBLECommand("ALARM_ON")
            sendUDPCommand("ALARM_ON")
            addDebugLog("🚨 Alarm enabled")
        } else {
            sendBLECommand("ALARM_OFF")
            sendUDPCommand("ALARM_OFF")
            isAlarmActive = false
            addDebugLog("🔕 Alarm disabled")
        }

        updateAlarmUI()
        updateStatus("Alarm ${if (enabled) "enabled" else "disabled"}")
        Toast.makeText(this, "Alarm ${if (enabled) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
    }

    private fun silenceAlarm() {
        if (isAlarmActive) {
            sendBLECommand("SILENCE_ALARM")
            sendUDPCommand("SILENCE_ALARM")
            isAlarmActive = false
            updateAlarmUI()
            updateStatus("Alarm silenced")
            addDebugLog("🔇 Alarm silenced by user")
            Toast.makeText(this, "Alarm silenced", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAlarmUI() {
        runOnUiThread {
            alarmSwitch?.isChecked = isAlarmEnabled

            val statusText: String
            val colorRes: Int

            when {
                !isAlarmEnabled -> {
                    statusText = "🔕 DISABLED"
                    colorRes = android.R.color.darker_gray
                }
                isAlarmActive -> {
                    statusText = "🚨 ACTIVE (ringing)"
                    colorRes = android.R.color.holo_red_dark
                }
                else -> {
                    statusText = "🔔 ENABLED (ready)"
                    colorRes = android.R.color.holo_green_dark
                }
            }

            alarmStatusText?.text = statusText
            alarmStatusText?.setTextColor(ContextCompat.getColor(this, colorRes))
            silenceButton?.isEnabled = isAlarmActive && isAlarmEnabled
        }
    }

    private fun updateConnectionStatus() {
        runOnUiThread {
            // BLE Status
            bleStatusText?.apply {
                if (bleConnected) {
                    text = "🔵 BLE: Connected"
                    setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
                } else {
                    text = "🔵 BLE: Disconnected"
                    setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                }
            }

            // WiFi Status (simulated - would need actual implementation)
            wifiStatusText?.apply {
                text = "📶 WiFi: Ready"
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
            }

            // GSM Status (simulated - would need actual implementation)
            gsmStatusText?.apply {
                text = "📱 GSM: Ready"
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
            }

            // Overall connection status
            val overallStatus = when {
                bleConnected -> "🟢 Connected via BLE"
                else -> "🔴 Disconnected"
            }
            connectionStatusCard?.text = overallStatus
        }
    }

    private fun toggleRssiTest() {
        if (!bleConnected) {
            Toast.makeText(this, "ESP32 not connected!", Toast.LENGTH_SHORT).show()
            addDebugLog("❌ RSSI test failed - not connected")
            return
        }

        isRssiTestActive = !isRssiTestActive

        if (isRssiTestActive) {
            startRssiTest()
        } else {
            stopRssiTest()
        }
    }

    private fun startRssiTest() {
        isRssiTestActive = true
        rssiTestButton?.text = "⏹️ STOP RSSI TEST"
        setThresholdButton?.isEnabled = false

        addDebugLog("📡 RSSI test started - Walk to desired distance")
        Toast.makeText(this, "RSSI Test Started! Walk to desired distance", Toast.LENGTH_LONG).show()

        sendBLECommand("START_RSSI_TEST")
        updateRssiPeriodically()
    }

    private fun stopRssiTest() {
        isRssiTestActive = false
        rssiTestButton?.text = "📡 START RSSI TEST"
        setThresholdButton?.isEnabled = false
        rssiUpdateHandler.removeCallbacksAndMessages(null)

        addDebugLog("📡 RSSI test stopped")
        Toast.makeText(this, "RSSI Test Stopped", Toast.LENGTH_SHORT).show()

        if (bleConnected) {
            sendBLECommand("STOP_RSSI_TEST")
        }
    }

    private fun updateRssiPeriodically() {
        if (!isRssiTestActive || !bleConnected) return

        bleGatt?.let { gatt ->
            try {
                gatt.readRemoteRssi()
            } catch (e: SecurityException) {
                if (isRssiTestActive) {
                    addDebugLog("❌ Security error reading RSSI")
                }
                else {}
            } catch (e: Exception) {
                if (isRssiTestActive) {
                    addDebugLog("❌ Error reading RSSI: ${e.message}")
                }
                else {}
            }
        }

        if (bleConnected && isRssiTestActive) {
            rssiUpdateHandler.postDelayed({
                updateRssiPeriodically()
            }, 2000) // Increased interval to reduce spam
        }
    }

    private fun setCurrentRssiAsThreshold() {
        if (currentRssi != 0) {
            rssiThreshold = currentRssi
            savePreferences()
            sendBLECommand("SET_THRESHOLD;$rssiThreshold")
            updateRssiDisplay()
            addDebugLog("✅ RSSI threshold set to: $rssiThreshold dBm")
            Toast.makeText(this, "✅ Threshold set to: $rssiThreshold dBm", Toast.LENGTH_LONG).show()
            stopRssiTest()
        } else {
            Toast.makeText(this, "No RSSI data available", Toast.LENGTH_SHORT).show()
            addDebugLog("❌ Cannot set threshold - no RSSI data")
        }
    }

    private fun updateRssiDisplay() {
        runOnUiThread {
            val rssiText: String
            val color: Int

            if (isRssiTestActive && currentRssi != 0) {
                rssiText = "📡 Current: ${currentRssi} dBm | Threshold: ${rssiThreshold} dBm"
                setThresholdButton?.isEnabled = true
            } else {
                rssiText = "📡 RSSI Threshold: ${rssiThreshold} dBm"
            }

            rssiTestText?.text = rssiText

            when {
                !isRssiTestActive -> color = android.R.color.darker_gray
                currentRssi > rssiThreshold -> color = android.R.color.holo_green_dark
                currentRssi > rssiThreshold - 10 -> color = android.R.color.holo_orange_dark
                else -> color = android.R.color.holo_red_dark
            }

            rssiTestText?.setTextColor(ContextCompat.getColor(this, color))
        }
    }

    private fun retryConnection(connectionType: String) {
        addDebugLog("🔄 Manual retry: $connectionType")

        when (connectionType.lowercase()) {
            "bluetooth", "ble" -> {
                if (bleConnected) {
                    Toast.makeText(this, "BLE Already Connected", Toast.LENGTH_SHORT).show()
                    return
                }

                if (isScanning) {
                    stopBLEScan()
                }

                handler.postDelayed({
                    startBLEScan()
                }, 1000)

                Toast.makeText(this, "🔵 Retrying BLE...", Toast.LENGTH_SHORT).show()
            }

            "wifi" -> {
                sendUDPCommand("PING_TEST")
                Toast.makeText(this, "📶 Testing WiFi UDP...", Toast.LENGTH_SHORT).show()
            }

            "gsm" -> {
                sendBLECommand("GET_GSM_STATUS")
                sendUDPCommand("GET_GSM_STATUS")
                Toast.makeText(this, "📱 Testing GSM...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendBLECommand(command: String) {
        bleGatt?.let { gatt ->
            val service = gatt.getService(UUID.fromString(BLE_SERVICE_UUID))
            val characteristic = service?.getCharacteristic(UUID.fromString(BLE_CHAR_UUID))

            if (characteristic != null) {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        characteristic.value = command.toByteArray()
                        val success = gatt.writeCharacteristic(characteristic)
                        if (success) {
                            addDebugLog("📤 BLE: $command ✅")
                        } else {
                            addDebugLog("📤 BLE: $command ❌")
                        }
                    } else {
                        addDebugLog("❌ BLE permission denied")
                    }
                } catch (e: SecurityException) {
                    addDebugLog("❌ BLE security error: ${e.message}")
                }
            } else {
                addDebugLog("❌ BLE characteristic not found")
            }
        } ?: run {
            addDebugLog("❌ BLE not connected")
        }
    }

    private fun sendUDPCommand(command: String) {
        Thread {
            try {
                val socket = DatagramSocket()
                val address = InetAddress.getByName("192.168.4.1")
                val buffer = command.toByteArray()
                val packet = DatagramPacket(buffer, buffer.size, address, UDP_PORT)

                socket.send(packet)
                socket.close()

                addDebugLog("📤 UDP: $command ✅")
            } catch (e: Exception) {
                addDebugLog("❌ UDP error: ${e.message}")
            }
        }.start()
    }

    private fun handleTrackerAlert(title: String, message: String) {
        addDebugLog("🚨 ALERT: $title - $message")

        if (isAlarmEnabled && (message.contains("ALERT") ||
                    message.contains("uzaklaşıyor") ||
                    message.contains("disconnected") ||
                    message.contains("moved away"))) {
            isAlarmActive = true
            updateAlarmUI()
            showCriticalNotification(title, message)
            vibratePhone()
        }

        showNotification(title, message)
        updateStatus("Alert: $message")
    }

    private fun checkPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        updateStatus("Requesting permissions...")
        ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                addDebugLog("✅ All permissions granted")
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
                initializeBluetooth()
            } else {
                addDebugLog("❌ Some permissions denied")
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_LONG).show()
                updateStatus("Permissions denied")
            }
        }
    }

    private fun initializeBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            addDebugLog("❌ Bluetooth not supported")
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            updateStatus("Bluetooth not supported")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            addDebugLog("🔵 Requesting Bluetooth enable...")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
                } else {
                    addDebugLog("❌ Bluetooth permission denied")
                    Toast.makeText(this, "Bluetooth permission needed", Toast.LENGTH_LONG).show()
                }
            } catch (e: SecurityException) {
                addDebugLog("❌ Bluetooth permission error")
                Toast.makeText(this, "Bluetooth permission needed", Toast.LENGTH_LONG).show()
            }
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bleScanner = bluetoothAdapter.bluetoothLeScanner
            if (bleScanner == null) {
                addDebugLog("❌ BLE scanner not available")
                updateStatus("BLE scanner not available")
                return
            }

            addDebugLog("✅ Bluetooth initialized")
            updateStatus("Ready to start tracking")
            startButton?.isEnabled = true
        } else {
            addDebugLog("❌ BLE scan permission denied")
            updateStatus("BLE scan permission needed")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                addDebugLog("✅ Bluetooth enabled")
                initializeBluetooth()
            } else {
                addDebugLog("❌ Bluetooth enable cancelled")
                updateStatus("Bluetooth disabled")
            }
        }
    }

    private fun startTracking() {
        if (!checkPermissions()) {
            requestPermissions()
            return
        }

        startButton?.isEnabled = false
        stopButton?.isEnabled = true

        addDebugLog("🚀 Starting tracking...")
        startBLEScan()

        handler.postDelayed({
            if (bleConnected) {
                if (isAlarmEnabled) {
                    sendBLECommand("ALARM_ON")
                } else {
                    sendBLECommand("ALARM_OFF")
                }
            }
        }, 2000)
    }

    private fun stopTracking() {
        addDebugLog("⏹️ Stopping tracking...")

        rssiUpdateHandler.removeCallbacksAndMessages(null)
        isRssiTestActive = false

        stopBLEScan()

        bleGatt?.let { gatt ->
            try {
                sendBLECommand("ALARM_OFF")
                handler.postDelayed({
                    try {
                        gatt.disconnect()
                        gatt.close()
                        addDebugLog("🔌 BLE connection closed")
                    } catch (e: SecurityException) {
                        addDebugLog("❌ Error closing BLE: ${e.message}")
                    }
                }, 500)
            } catch (e: Exception) {
                addDebugLog("❌ Error stopping tracking: ${e.message}")
            }
        }

        bleConnected = false
        bleGatt = null
        currentRssi = 0

        startButton?.isEnabled = true
        stopButton?.isEnabled = false
        silenceButton?.isEnabled = false
        rssiTestButton?.isEnabled = false
        setThresholdButton?.isEnabled = false
        isAlarmActive = false

        updateAlarmUI()
        updateRssiDisplay()
        updateConnectionStatus()
        updateStatus("Tracking stopped")
        addDebugLog("✅ Tracking stopped")
    }

    private fun startBLEScan() {
        if (!checkPermissions()) {
            addDebugLog("❌ Missing BLE permissions")
            return
        }

        if (isScanning) {
            addDebugLog("⚠️ Already scanning")
            stopBLEScan()
            handler.postDelayed({ startBLEScan() }, 1000)
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            addDebugLog("❌ Bluetooth not available")
            updateStatus("Bluetooth not available")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            addDebugLog("❌ BLE scan permission denied")
            updateStatus("BLE scan permission needed")
            return
        }

        bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            addDebugLog("❌ BLE scanner not available")
            updateStatus("BLE scanner not available")
            return
        }

        val scanFilter = android.bluetooth.le.ScanFilter.Builder()
            .setDeviceName("ESP32_TRACKER")
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            isScanning = true
            updateStatus("Scanning for ESP32_TRACKER...")
            addDebugLog("🔍 Scanning for ESP32_TRACKER...")
            Toast.makeText(this, "Looking for ESP32_TRACKER...", Toast.LENGTH_SHORT).show()

            handler.postDelayed({
                if (isScanning) {
                    stopBLEScan()
                    updateStatus("ESP32_TRACKER not found")
                    addDebugLog("⏰ ESP32_TRACKER not found - timeout")
                    Toast.makeText(this, "ESP32_TRACKER not found", Toast.LENGTH_LONG).show()
                    startButton?.isEnabled = true
                    stopButton?.isEnabled = false
                }
            }, SCAN_TIMEOUT)

        } catch (e: SecurityException) {
            addDebugLog("❌ BLE scan permission denied")
            updateStatus("Permission denied")
            isScanning = false
            startButton?.isEnabled = true
        }
    }

    private fun stopBLEScan() {
        if (!isScanning) return

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bleScanner?.stopScan(scanCallback)
                isScanning = false
                addDebugLog("🔍 BLE scan stopped")
            } else {
                addDebugLog("❌ Permission denied for stopping scan")
            }
        } catch (e: SecurityException) {
            addDebugLog("❌ Error stopping scan")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    addDebugLog("❌ BLE connect permission denied")
                    return
                }

                val deviceName = result.device.name
                val rssi = result.rssi

                if (deviceName == "ESP32_TRACKER") {
                    updateStatus("Found ESP32_TRACKER! Connecting...")
                    addDebugLog("🎯 ESP32_TRACKER found! RSSI: $rssi dBm")

                    stopBLEScan()

                    bleGatt?.let { oldGatt ->
                        try {
                            oldGatt.disconnect()
                            oldGatt.close()
                            addDebugLog("🔌 Closed previous connection")
                        } catch (e: Exception) {
                            addDebugLog("⚠️ Error closing old connection")
                        }
                    }

                    bleGatt = result.device.connectGatt(
                        this@MainActivity,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )

                    Toast.makeText(this@MainActivity, "Connecting...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: SecurityException) {
                addDebugLog("❌ Security error in scan")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            val errorMessage = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                else -> "Unknown error: $errorCode"
            }

            addDebugLog("❌ BLE scan failed: $errorMessage")
            updateStatus("BLE scan failed")
            startButton?.isEnabled = true
            stopButton?.isEnabled = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentRssi = rssi

                runOnUiThread {
                    updateRssiDisplay()
                    if (isRssiTestActive) {
                        setThresholdButton?.isEnabled = true
                        // Only log during RSSI test, but reduce frequency
                        if (System.currentTimeMillis() % 3000 < 100) { // Only every 3 seconds
                            addDebugLog("📶 RSSI: $rssi dBm")
                        }
                    }
                }

                if (isRssiTestActive) {
                    sendBLECommand("ANDROID_RSSI;$rssi")
                }
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    bleConnected = true
                    runOnUiThread {
                        updateStatus("Connected to ESP32_TRACKER!")
                        updateConnectionStatus()
                        addDebugLog("🔵 BLE: Connected")
                        rssiTestButton?.isEnabled = true
                        Toast.makeText(this@MainActivity, "Connected!", Toast.LENGTH_SHORT).show()
                        showNotification("BLE Connected", "Connected to ESP32_TRACKER")
                    }

                    try {
                        Thread.sleep(600)
                        gatt.discoverServices()

                        handler.postDelayed({
                            if (bleConnected) {
                                startPeriodicRSSIReading(gatt)
                            }
                        }, 2000)

                    } catch (e: SecurityException) {
                        addDebugLog("❌ Error discovering services")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = bleConnected
                    bleConnected = false
                    currentRssi = 0
                    rssiUpdateHandler.removeCallbacksAndMessages(null)

                    runOnUiThread {
                        updateStatus("Disconnected from ESP32_TRACKER")
                        updateConnectionStatus()
                        addDebugLog("❌ BLE: Disconnected")

                        if (isRssiTestActive) {
                            stopRssiTest()
                        }
                        rssiTestButton?.isEnabled = false

                        Toast.makeText(this@MainActivity, "Disconnected", Toast.LENGTH_SHORT).show()

                        if (stopButton?.isEnabled == true) {
                            addDebugLog("🔄 Auto-reconnection in 3 seconds...")
                            handler.postDelayed({
                                if (!bleConnected && stopButton?.isEnabled == true) {
                                    addDebugLog("🔍 Starting reconnection...")
                                    startBLEScan()
                                }
                            }, 3000)
                        } else {
                            startButton?.isEnabled = true
                            stopButton?.isEnabled = false
                        }

                        if (wasConnected && isAlarmEnabled) {
                            isAlarmActive = true
                            updateAlarmUI()
                            handleTrackerAlert("Connection Lost", "ESP32_TRACKER disconnected!")
                        }
                    }
                }

                BluetoothProfile.STATE_CONNECTING -> {
                    runOnUiThread {
                        updateStatus("Connecting...")
                        addDebugLog("🔄 BLE: Connecting...")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addDebugLog("🔍 Services discovered")

                val service = gatt.getService(UUID.fromString(BLE_SERVICE_UUID))
                if (service != null) {
                    addDebugLog("✅ Target service found")
                    val characteristic = service.getCharacteristic(UUID.fromString(BLE_CHAR_UUID))
                    if (characteristic != null) {
                        addDebugLog("✅ Setting up notifications...")
                        runOnUiThread {
                            updateStatus("Setting up notifications...")
                        }

                        try {
                            val success = gatt.setCharacteristicNotification(characteristic, true)

                            val descriptor = characteristic.getDescriptor(
                                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                            )
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                                addDebugLog("📝 Descriptor write initiated")
                            } else {
                                addDebugLog("❌ CCCD descriptor not found")
                            }
                        } catch (e: SecurityException) {
                            addDebugLog("❌ Security error enabling notifications")
                        }
                    } else {
                        addDebugLog("❌ Target characteristic not found")
                        runOnUiThread {
                            updateStatus("Characteristic not found")
                        }
                    }
                } else {
                    addDebugLog("❌ Target service not found")
                    runOnUiThread {
                        updateStatus("Service not found")
                    }
                }
            } else {
                addDebugLog("❌ Service discovery failed")
                runOnUiThread {
                    updateStatus("Service discovery failed")
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addDebugLog("✅ BLE notifications enabled!")
                runOnUiThread {
                    updateStatus("BLE notifications enabled!")
                    Toast.makeText(this@MainActivity, "Ready to communicate!", Toast.LENGTH_SHORT).show()
                }

                handler.postDelayed({
                    if (isAlarmEnabled) {
                        sendBLECommand("ALARM_ON")
                    } else {
                        sendBLECommand("ALARM_OFF")
                    }

                    handler.postDelayed({
                        sendBLECommand("GET_ALARM_STATUS")
                    }, 500)

                }, 1000)

            } else {
                addDebugLog("❌ Failed to enable notifications")
                runOnUiThread {
                    updateStatus("Failed to enable notifications")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            if (data != null) {
                val message = String(data)

                runOnUiThread {
                    processESP32Message(message)
                }
            }
        }
    }

    private fun startPeriodicRSSIReading(gatt: BluetoothGatt) {
        if (!bleConnected || stopButton?.isEnabled != true) {
            return
        }

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                gatt.readRemoteRssi()
            } else {
                addDebugLog("❌ BLE connect permission denied for RSSI")
            }
        } catch (e: Exception) {
            // Only log during RSSI test to reduce spam
            if (isRssiTestActive) {
                addDebugLog("❌ Error reading RSSI: ${e.message}")
            }
        }

        if (bleConnected && stopButton?.isEnabled == true) {
            handler.postDelayed({
                startPeriodicRSSIReading(gatt)
            }, 5000)
        }
    }

    private fun processESP32Message(message: String) {
        // Reduce ESP32 message logging spam - only log important messages
        val shouldLog = when {
            message.startsWith("ALERT") -> true
            message.startsWith("ALARM_STATUS") -> true
            message == "ALARM_SILENCED" -> true
            message.startsWith("PONG") -> true
            message.startsWith("LOCATION") -> true
            message.startsWith("GSM_STATUS") -> true
            message.startsWith("RSSI") && !isRssiTestActive -> true // Only log RSSI when not in test mode
            else -> false
        }

        if (shouldLog) {
            addDebugLog("📩 ESP32: $message")
        }

        when {
            message.startsWith("ALERT") -> {
                handleTrackerAlert("BLE Alert", message)
            }
            message.startsWith("ALARM_STATUS") -> {
                val parts = message.split(";")
                if (parts.size > 1) {
                    val status = parts[1]
                    val enabled = status == "ENABLED"

                    if (isAlarmEnabled != enabled) {
                        isAlarmEnabled = enabled
                        savePreferences()
                        updateAlarmUI()
                        addDebugLog("🔄 Alarm synced: $status")
                    }

                    updateStatus("ESP32 Alarm: $status")
                    Toast.makeText(this, "ESP32: Alarm $status", Toast.LENGTH_SHORT).show()
                }
            }
            message == "ALARM_SILENCED" -> {
                isAlarmActive = false
                updateAlarmUI()
                updateStatus("Alarm silenced by ESP32")
                addDebugLog("🔇 ESP32 silenced alarm")
                Toast.makeText(this, "Alarm silenced remotely", Toast.LENGTH_SHORT).show()
            }
            message.startsWith("PONG") || message.startsWith("LOCATION") -> {
                updateStatus("Location received")
                val locationMatch = Regex("""LAT=([\d.-]+);LON=([\d.-]+)""").find(message)
                if (locationMatch != null) {
                    val lat = locationMatch.groupValues[1]
                    val lon = locationMatch.groupValues[2]
                    updateGpsStatus("$lat, $lon")
                    addDebugLog("📍 GPS: $lat, $lon")
                    Toast.makeText(this, "Location: $lat, $lon", Toast.LENGTH_LONG).show()
                }
            }
            message.startsWith("RSSI") -> {
                val parts = message.split(";")
                if (parts.size > 1 && parts[1] != "ERROR") {
                    try {
                        currentRssi = parts[1].toInt()
                        updateRssiDisplay()

                        if (isRssiTestActive && currentRssi != 0) {
                            setThresholdButton?.isEnabled = true
                        }
                    } catch (e: NumberFormatException) {
                        addDebugLog("❌ Invalid RSSI format")
                    }
                } else {
                    addDebugLog("❌ RSSI error from ESP32")
                }
            }
            message.startsWith("GSM_STATUS") -> {
                val parts = message.split(";")
                if (parts.size > 1) {
                    val status = parts[1]
                    updateGsmStatus(status)
                    addDebugLog("📱 GSM: $status")
                    Toast.makeText(this, "GSM: $status", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                updateStatus("Message: $message")
                showNotification("ESP32 Message", message)
            }
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            statusText?.text = "Status: $status"
        }
    }

    private fun updateGpsStatus(status: String) {
        runOnUiThread {
            gpsStatusText?.text = "GPS: $status"
        }
    }

    private fun updateGsmStatus(status: String) {
        runOnUiThread {
            gsmStatusText?.text = "GSM: $status"
        }
    }

    private fun showNotification(title: String, text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            addDebugLog("❌ Notification permission needed")
        }
    }

    private fun showCriticalNotification(title: String, text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 $title")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .setLights(0xFFFF0000.toInt(), 500, 500)
            .build()

        try {
            notificationManager.notify(999, notification)
        } catch (e: SecurityException) {
            addDebugLog("❌ Critical notification permission needed")
        }
    }

    private fun vibratePhone() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrationEffect = android.os.VibrationEffect.createWaveform(
                    longArrayOf(0, 500, 200, 500, 200, 500), -1
                )
                vibrator.vibrate(vibrationEffect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 500, 200, 500, 200, 500), -1)
            }
        } catch (e: Exception) {
            addDebugLog("❌ Vibration error: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "SmartTracker Alerts"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = "Channel for tracker notifications and alarms"
                enableVibration(true)
                vibrationPattern = longArrayOf(1000, 1000, 1000)
                enableLights(true)
                lightColor = 0xFFFF0000.toInt()
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (bleConnected) {
                sendBLECommand("ALARM_OFF")
            }
            stopBLEScan()
            bleGatt?.close()
            SmsReceiver.callback = null
            rssiUpdateHandler.removeCallbacksAndMessages(null)
        } catch (e: SecurityException) {
            addDebugLog("❌ Error in onDestroy")
        }
    }

    override fun onResume() {
        super.onResume()
        updateAlarmUI()
        updateConnectionStatus()

        handler.postDelayed({
            if (bleConnected) {
                if (isAlarmEnabled) {
                    sendBLECommand("ALARM_ON")
                } else {
                    sendBLECommand("ALARM_OFF")
                }
            }
        }, 1000)
    }

    override fun onPause() {
        super.onPause()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(999)
    }
}