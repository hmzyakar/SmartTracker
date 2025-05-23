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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.*

class MainActivity : Activity() {
    private val CHANNEL_ID = "smarttracker"
    private var bleGatt: BluetoothGatt? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var bleConnected = false

    // UI Components
    private var statusText: TextView? = null
    private var connectionText: TextView? = null
    private var alarmStatusText: TextView? = null
    private var gpsStatusText: TextView? = null
    private var gsmStatusText: TextView? = null
    private var debugLogText: TextView? = null
    private var rssiTestText: TextView? = null
    private var startButton: Button? = null
    private var stopButton: Button? = null
    private var alarmSwitch: Switch? = null
    private var silenceButton: Button? = null
    private var clearLogButton: Button? = null
    private var rssiTestButton: Button? = null
    private var setThresholdButton: Button? = null

    // Alarm Control
    private var isAlarmEnabled = true
    private var isAlarmActive = false
    private var prefs: SharedPreferences? = null

    // RSSI Test & Calibration
    private var isRssiTestActive = false
    private var currentRssi = 0
    private var rssiThreshold = -70  // Default threshold
    private val rssiUpdateHandler = Handler(Looper.getMainLooper())

    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_TIMEOUT = 30000L

    companion object {
        private const val TAG = "SmartTracker"
        const val BLE_SERVICE_UUID = "12345678-1234-1234-1234-1234567890ab"
        const val BLE_CHAR_UUID = "abcd1234-ab12-cd34-ef00-1234567890ab"
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_ENABLE_BT = 1002
        private const val UDP_PORT = 5000

        // Preferences keys
        private const val PREF_ALARM_ENABLED = "alarm_enabled"
    }

    // Gerekli izinler
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

        // SMS Receiver callback'ini ayarla
        SmsReceiver.callback = { title, message ->
            runOnUiThread {
                handleTrackerAlert(title, message)
            }
        }

        // İzinleri kontrol et
        if (checkPermissions()) {
            initializeBluetooth()
        } else {
            requestPermissions()
        }

        Log.d(TAG, "MainActivity created with alarm control")
    }

    private fun initializePreferences() {
        prefs = getSharedPreferences("smarttracker_prefs", Context.MODE_PRIVATE)
        isAlarmEnabled = prefs?.getBoolean(PREF_ALARM_ENABLED, true) ?: true
        rssiThreshold = prefs?.getInt("rssi_threshold", -70) ?: -70
        addDebugLog("📊 Loaded RSSI threshold: $rssiThreshold dBm")
    }

    private fun saveAlarmPreference() {
        prefs?.edit()?.putBoolean(PREF_ALARM_ENABLED, isAlarmEnabled)?.apply()
    }

    private fun initializeViews() {
        statusText = findViewById<TextView>(R.id.statusText)
        connectionText = findViewById<TextView>(R.id.connectionText)
        alarmStatusText = findViewById<TextView>(R.id.alarmStatusText)
        gpsStatusText = findViewById<TextView>(R.id.gpsStatusText)
        gsmStatusText = findViewById<TextView>(R.id.gsmStatusText)
        debugLogText = findViewById<TextView>(R.id.debugLogText)
        rssiTestText = findViewById<TextView>(R.id.rssiTestText)

        startButton = findViewById<Button>(R.id.startButton)?.apply {
            setOnClickListener { startTracking() }
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

        updateAlarmUI()
        updateRssiDisplay()
        addDebugLog("📱 App initialized successfully")
    }

    private fun toggleAlarm(enabled: Boolean) {
        isAlarmEnabled = enabled
        saveAlarmPreference()

        // ESP32'ye komut gönder
        if (enabled) {
            sendBLECommand("ALARM_ON")
            sendUDPCommand("ALARM_ON")
            addDebugLog("🚨 Alarm enabled - commands sent to ESP32")
        } else {
            sendBLECommand("ALARM_OFF")
            sendUDPCommand("ALARM_OFF")
            isAlarmActive = false
            addDebugLog("🔕 Alarm disabled - commands sent to ESP32")
        }

        updateAlarmUI()

        val statusMessage = if (enabled) "enabled" else "disabled"
        updateStatus("Alarm $statusMessage")

        Toast.makeText(this,
            "Alarm $statusMessage",
            Toast.LENGTH_SHORT).show()

        Log.d(TAG, "Alarm toggled: $enabled")
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
            Log.d(TAG, "Alarm silenced by user")
        }
    }

    private fun updateAlarmUI() {
        runOnUiThread {
            alarmSwitch?.isChecked = isAlarmEnabled

            val statusText = when {
                !isAlarmEnabled -> "🔕 Alarm: DISABLED"
                isAlarmActive -> "🚨 Alarm: ACTIVE (ringing)"
                else -> "🔔 Alarm: ENABLED (ready)"
            }

            alarmStatusText?.text = statusText

            val colorRes = when {
                !isAlarmEnabled -> android.R.color.darker_gray
                isAlarmActive -> android.R.color.holo_red_dark
                else -> android.R.color.holo_green_dark
            }

            alarmStatusText?.setTextColor(ContextCompat.getColor(this, colorRes))
            silenceButton?.isEnabled = isAlarmActive && isAlarmEnabled
        }
    }

    private fun handleTrackerAlert(title: String, message: String) {
        Log.d(TAG, "Tracker alert received: $title - $message")
        addDebugLog("🚨 Alert: $title - $message")

        // Alarm durumunu güncelle
        if (isAlarmEnabled && (message.contains("ALERT") || message.contains("uzaklaşıyor") || message.contains("disconnected"))) {
            isAlarmActive = true
            updateAlarmUI()
            addDebugLog("🔥 CRITICAL: Alarm activated!")

            // Kritik bildirim göster
            showCriticalNotification(title, message)

            // Telefonu titret
            vibratePhone()

            // Ekranı aç (kullanıcı fark etsin)
            wakeUpScreen()
        }

        // Normal bildirim göster
        showNotification(title, message)
        updateStatus("Alert received: $message")
    }

    private fun addDebugLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logMessage = "[$timestamp] $message"

        runOnUiThread {
            val currentLog = debugLogText?.text?.toString() ?: ""
            val lines = currentLog.split("\n").toMutableList()

            // Maksimum 20 satır tut
            if (lines.size >= 20) {
                lines.removeAt(0)
            }

            lines.add(logMessage)
            debugLogText?.text = lines.joinToString("\n")

            // ScrollView'i en alta kaydır
            handler.post {
                val scrollView = findViewById<android.widget.ScrollView>(R.id.debugScrollView)
                scrollView?.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }

        Log.d(TAG, logMessage)
    }

    private fun clearDebugLog() {
        debugLogText?.text = ""
        addDebugLog("🗑️ Debug log cleared")
    }

    private fun updateGpsStatus(status: String) {
        runOnUiThread {
            gpsStatusText?.text = "GPS: $status"
        }
        addDebugLog("📍 GPS: $status")
    }

    private fun updateGsmStatus(status: String) {
        runOnUiThread {
            gsmStatusText?.text = "GSM: $status"
        }
        addDebugLog("📱 GSM: $status")
    }

    private fun toggleRssiTest() {
        if (!bleConnected) {
            Toast.makeText(this, "ESP32 not connected!", Toast.LENGTH_SHORT).show()
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
        setThresholdButton?.isEnabled = true

        addDebugLog("📡 RSSI test started - current threshold: $rssiThreshold dBm")
        Toast.makeText(this, "RSSI Test Started! Walk to your desired distance and press SET THRESHOLD", Toast.LENGTH_LONG).show()

        // RSSI'yi gerçek zamanlı güncelle
        updateRssiPeriodically()
    }

    private fun stopRssiTest() {
        isRssiTestActive = false
        rssiTestButton?.text = "📡 START RSSI TEST"
        setThresholdButton?.isEnabled = false

        addDebugLog("📡 RSSI test stopped")
        Toast.makeText(this, "RSSI Test Stopped", Toast.LENGTH_SHORT).show()

        // Periyodik güncellemeleri durdur
        rssiUpdateHandler.removeCallbacksAndMessages(null)
    }

    private fun updateRssiPeriodically() {
        if (!isRssiTestActive || !bleConnected) return

        // ESP32'den RSSI iste
        sendBLECommand("GET_RSSI")

        // 1 saniye sonra tekrar çağır
        rssiUpdateHandler.postDelayed({
            updateRssiPeriodically()
        }, 1000)
    }

    private fun setCurrentRssiAsThreshold() {
        if (currentRssi != 0) {
            rssiThreshold = currentRssi

            // Threshold'u kaydet
            prefs?.edit()?.putInt("rssi_threshold", rssiThreshold)?.apply()

            // ESP32'ye yeni threshold'u gönder
            sendBLECommand("SET_THRESHOLD;$rssiThreshold")

            updateRssiDisplay()
            addDebugLog("✅ RSSI threshold set to: $rssiThreshold dBm")

            Toast.makeText(this,
                "✅ Distance calibrated! Alarm threshold set to $rssiThreshold dBm",
                Toast.LENGTH_LONG).show()

            // Test'i durdur
            stopRssiTest()
        } else {
            Toast.makeText(this, "No RSSI data available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRssiDisplay() {
        runOnUiThread {
            val rssiText = if (isRssiTestActive && currentRssi != 0) {
                "📡 Current: ${currentRssi} dBm | Threshold: ${rssiThreshold} dBm"
            } else {
                "📡 RSSI Threshold: ${rssiThreshold} dBm (tap test to calibrate)"
            }

            rssiTestText?.text = rssiText

            // RSSI değerine göre renk
            val color = when {
                !isRssiTestActive -> android.R.color.darker_gray
                currentRssi > rssiThreshold -> android.R.color.holo_green_dark  // Güvenli alan
                currentRssi > rssiThreshold - 10 -> android.R.color.holo_orange_dark  // Uyarı alanı
                else -> android.R.color.holo_red_dark  // Alarm alanı
            }

            rssiTestText?.setTextColor(ContextCompat.getColor(this, color))
        }
    }

    private fun wakeUpScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val wakeLock = powerManager.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "SmartTracker:WakeLock"
            )
            wakeLock.acquire(3000) // 3 saniye ekranı açık tut
            wakeLock.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error waking up screen: ${e.message}")
        }
    }

    private fun sendBLECommand(command: String) {
        bleGatt?.let { gatt ->
            val service = gatt.getService(UUID.fromString(BLE_SERVICE_UUID))
            val characteristic = service?.getCharacteristic(UUID.fromString(BLE_CHAR_UUID))

            if (characteristic != null) {
                try {
                    characteristic.value = command.toByteArray()
                    val success = gatt.writeCharacteristic(characteristic)
                    Log.d(TAG, "BLE command sent: $command, success: $success")
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception sending BLE command: ${e.message}")
                }
            } else {
                Log.w(TAG, "BLE characteristic not found for command: $command")
            }
        } ?: run {
            Log.w(TAG, "BLE GATT not connected, cannot send command: $command")
        }
    }

    private fun sendUDPCommand(command: String) {
        Thread {
            try {
                val socket = DatagramSocket()
                val address = InetAddress.getByName("192.168.4.1") // ESP32 AP IP
                val buffer = command.toByteArray()
                val packet = DatagramPacket(buffer, buffer.size, address, UDP_PORT)

                socket.send(packet)
                socket.close()

                Log.d(TAG, "UDP command sent: $command")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending UDP command: ${e.message}")
            }
        }.start()
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
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
                initializeBluetooth()
            } else {
                Toast.makeText(this, "Permissions denied. App may not work properly.", Toast.LENGTH_LONG).show()
                updateStatus("Permissions denied")
            }
        }
    }

    private fun initializeBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
            updateStatus("Bluetooth not supported")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            try {
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            } catch (e: SecurityException) {
                Toast.makeText(this, "Bluetooth permission needed", Toast.LENGTH_LONG).show()
            }
            return
        }

        bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            Toast.makeText(this, "BLE scanner not available", Toast.LENGTH_LONG).show()
            updateStatus("BLE scanner not available")
            return
        }

        updateStatus("Ready to start tracking")
        startButton?.isEnabled = true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                initializeBluetooth()
            } else {
                Toast.makeText(this, "Bluetooth is required for tracking", Toast.LENGTH_LONG).show()
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
        silenceButton?.isEnabled = false

        addDebugLog("🚀 Starting tracking system...")
        startBLEScan()

        // Alarm durumunu ESP32'ye bildir
        handler.postDelayed({
            if (isAlarmEnabled) {
                sendBLECommand("ALARM_ON")
                addDebugLog("📤 Sent ALARM_ON to ESP32")
            } else {
                sendBLECommand("ALARM_OFF")
                addDebugLog("📤 Sent ALARM_OFF to ESP32")
            }
        }, 2000) // 2 saniye bekle
    }

    private fun stopTracking() {
        stopBLEScan()
        bleGatt?.let { gatt ->
            try {
                // Durdurulmadan önce alarm'ı kapat
                sendBLECommand("ALARM_OFF")
                addDebugLog("📤 Sent ALARM_OFF before stopping")

                gatt.disconnect()
                gatt.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception disconnecting GATT: ${e.message}")
            }
        }
        bleGatt = null

        startButton?.isEnabled = true
        stopButton?.isEnabled = false
        silenceButton?.isEnabled = false
        isAlarmActive = false

        updateAlarmUI()
        updateStatus("Tracking stopped")
        addDebugLog("⏹️ Tracking stopped by user")
    }

    private fun startBLEScan() {
        if (!checkPermissions()) {
            Toast.makeText(this, "Missing permissions for BLE scan", Toast.LENGTH_LONG).show()
            return
        }

        if (isScanning) {
            Log.d(TAG, "Already scanning")
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_LONG).show()
            updateStatus("Bluetooth not available")
            addDebugLog("❌ Bluetooth not available")
            return
        }

        bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            Toast.makeText(this, "BLE scanner not available", Toast.LENGTH_LONG).show()
            updateStatus("BLE scanner not available")
            addDebugLog("❌ BLE scanner not available")
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()

        try {
            bleScanner?.startScan(null, scanSettings, scanCallback)
            isScanning = true
            updateStatus("Scanning for ESP32_TRACKER...")
            addDebugLog("🔍 BLE scan started")
            Toast.makeText(this, "Scanning for ESP32_TRACKER...", Toast.LENGTH_LONG).show()
            Log.d(TAG, "BLE scan started with aggressive settings")

            handler.postDelayed({
                if (isScanning) {
                    Log.d(TAG, "Scan timeout reached")
                    stopBLEScan()
                    updateStatus("Scan timeout - ESP32_TRACKER not found")
                    addDebugLog("⏰ BLE scan timeout")
                    Toast.makeText(this, "ESP32_TRACKER not found. Check if device is on and nearby.", Toast.LENGTH_LONG).show()
                }
            }, SCAN_TIMEOUT)

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting scan: ${e.message}")
            Toast.makeText(this, "Bluetooth scan permission needed", Toast.LENGTH_LONG).show()
            updateStatus("Permission denied")
            addDebugLog("❌ BLE scan permission denied")
            isScanning = false
        }
    }

    private fun stopBLEScan() {
        if (!isScanning) return

        try {
            bleScanner?.stopScan(scanCallback)
            isScanning = false
            Log.d(TAG, "BLE scan stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping scan: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val deviceName = result.device.name
                val deviceAddress = result.device.address
                val rssi = result.rssi

                Log.d(TAG, "Found device: $deviceName (${deviceAddress}) RSSI: $rssi")

                if (deviceName == "ESP32_TRACKER") {
                    Log.d(TAG, "Found ESP32_TRACKER! RSSI: $rssi, connecting...")
                    updateStatus("Found ESP32_TRACKER! Connecting...")
                    Toast.makeText(this@MainActivity, "Found ESP32_TRACKER! Connecting...", Toast.LENGTH_LONG).show()

                    stopBLEScan()

                    bleGatt = result.device.connectGatt(
                        this@MainActivity,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception in scan result: ${e.message}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            val errorMessage = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE feature unsupported"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                else -> "Unknown error: $errorCode"
            }

            Log.e(TAG, "BLE Scan failed: $errorMessage")
            updateStatus("BLE scan failed: $errorMessage")
            Toast.makeText(this@MainActivity, "BLE scan failed: $errorMessage", Toast.LENGTH_LONG).show()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    bleConnected = true
                    Log.d(TAG, "Connected to ESP32_TRACKER! Status: $status")
                    runOnUiThread {
                        updateStatus("Connected to ESP32_TRACKER!")
                        updateConnectionStatus("Connected", true)
                        addDebugLog("🔵 BLE: Connected to ESP32_TRACKER")

                        // RSSI test butonunu aktif et
                        rssiTestButton?.isEnabled = true

                        Toast.makeText(this@MainActivity, "Connected to ESP32_TRACKER!", Toast.LENGTH_LONG).show()
                        showNotification("BLE Connected", "Successfully connected to ESP32_TRACKER")
                    }

                    try {
                        Thread.sleep(600)
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception discovering services: ${e.message}")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    bleConnected = false
                    Log.d(TAG, "Disconnected from ESP32_TRACKER. Status: $status")
                    runOnUiThread {
                        updateStatus("Disconnected from ESP32_TRACKER")
                        updateConnectionStatus("Disconnected", false)
                        addDebugLog("❌ BLE: Disconnected from ESP32_TRACKER")

                        // RSSI test'i durdur ve butonu pasif et
                        if (isRssiTestActive) {
                            stopRssiTest()
                        }
                        rssiTestButton?.isEnabled = false

                        Toast.makeText(this@MainActivity, "Disconnected from ESP32_TRACKER", Toast.LENGTH_LONG).show()
                        startButton?.isEnabled = true
                        stopButton?.isEnabled = false

                        // Bağlantı kesildiğinde alarm durumunu kontrol et
                        if (isAlarmEnabled) {
                            isAlarmActive = true
                            updateAlarmUI()
                            handleTrackerAlert("Connection Lost", "ESP32_TRACKER disconnected - possible theft!")
                        }
                    }
                }

                BluetoothProfile.STATE_CONNECTING -> {
                    Log.d(TAG, "Connecting to ESP32_TRACKER...")
                    runOnUiThread {
                        updateStatus("Connecting...")
                        addDebugLog("🔄 BLE: Connecting to ESP32_TRACKER...")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered! Status: $status")

                gatt.services.forEach { service ->
                    Log.d(TAG, "Service UUID: ${service.uuid}")
                    service.characteristics.forEach { char ->
                        Log.d(TAG, "  Characteristic UUID: ${char.uuid}")
                    }
                }

                val service = gatt.getService(UUID.fromString(BLE_SERVICE_UUID))
                if (service != null) {
                    Log.d(TAG, "Found target service")
                    val characteristic = service.getCharacteristic(UUID.fromString(BLE_CHAR_UUID))
                    if (characteristic != null) {
                        Log.d(TAG, "Found characteristic, enabling notifications")
                        runOnUiThread {
                            updateStatus("Setting up notifications...")
                        }

                        try {
                            val success = gatt.setCharacteristicNotification(characteristic, true)
                            Log.d(TAG, "setCharacteristicNotification result: $success")

                            val descriptor = characteristic.getDescriptor(
                                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                            )
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                val writeSuccess = gatt.writeDescriptor(descriptor)
                                Log.d(TAG, "writeDescriptor result: $writeSuccess")
                            } else {
                                Log.e(TAG, "CCCD descriptor not found")
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Security exception enabling notifications: ${e.message}")
                        }
                    } else {
                        Log.e(TAG, "Target characteristic not found")
                        runOnUiThread {
                            updateStatus("Characteristic not found")
                        }
                    }
                } else {
                    Log.e(TAG, "Target service not found")
                    runOnUiThread {
                        updateStatus("Service not found")
                    }
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
                runOnUiThread {
                    updateStatus("Service discovery failed")
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor write successful - notifications enabled!")
                runOnUiThread {
                    updateStatus("BLE notifications enabled!")
                    Toast.makeText(this@MainActivity, "BLE notifications enabled!", Toast.LENGTH_LONG).show()
                }

                // Bağlantı kurulduktan sonra alarm durumunu gönder ve status iste
                handler.postDelayed({
                    if (isAlarmEnabled) {
                        sendBLECommand("ALARM_ON")
                    } else {
                        sendBLECommand("ALARM_OFF")
                    }

                    // ESP32'den mevcut durumu iste
                    handler.postDelayed({
                        sendBLECommand("GET_ALARM_STATUS")
                    }, 500)

                }, 1000)

            } else {
                Log.e(TAG, "Descriptor write failed with status: $status")
                runOnUiThread {
                    updateStatus("Failed to enable notifications")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            if (data != null) {
                val message = String(data)
                Log.d(TAG, "BLE message received: $message")
                addDebugLog("📩 BLE MSG: $message")

                runOnUiThread {
                    when {
                        message.startsWith("ALERT") -> {
                            handleTrackerAlert("BLE Alert", message)
                        }
                        message.startsWith("ALARM_STATUS") -> {
                            val parts = message.split(";")
                            if (parts.size > 1) {
                                val status = parts[1]
                                val enabled = status == "ENABLED"

                                // ESP32'den gelen durumu app ile senkronize et
                                if (isAlarmEnabled != enabled) {
                                    isAlarmEnabled = enabled
                                    saveAlarmPreference()
                                    updateAlarmUI()
                                    addDebugLog("🔄 Alarm synced with ESP32: $status")
                                }

                                updateStatus("ESP32 Alarm status: $status")
                                Toast.makeText(this@MainActivity,
                                    "ESP32 confirmed: Alarm $status",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                        message == "ALARM_SILENCED" -> {
                            isAlarmActive = false
                            updateAlarmUI()
                            updateStatus("Alarm silenced by ESP32")
                            addDebugLog("🔇 ESP32 silenced the alarm")
                            Toast.makeText(this@MainActivity,
                                "Alarm silenced remotely",
                                Toast.LENGTH_SHORT).show()
                        }
                        message.startsWith("RECONNECT") -> {
                            updateStatus("ESP32 reconnected")
                            addDebugLog("🔄 ESP32 reconnected")
                            Toast.makeText(this@MainActivity,
                                "Tracker reconnected",
                                Toast.LENGTH_SHORT).show()
                        }
                        message.startsWith("WIFI_ALERT") -> {
                            handleTrackerAlert("WiFi Connection Lost", message)
                        }
                        message.startsWith("PONG") || message.startsWith("LOCATION") -> {
                            updateStatus("Location received: $message")
                            // Location parsing
                            val locationMatch = Regex("""LAT=([\d.-]+);LON=([\d.-]+)""").find(message)
                            if (locationMatch != null) {
                                val lat = locationMatch.groupValues[1]
                                val lon = locationMatch.groupValues[2]
                                updateGpsStatus("$lat, $lon")
                                Toast.makeText(this@MainActivity,
                                    "Location: $lat, $lon",
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                        message.startsWith("RSSI") -> {
                            // RSSI mesajını işle: "RSSI;-65"
                            val parts = message.split(";")
                            if (parts.size > 1) {
                                currentRssi = parts[1].toIntOrNull() ?: 0
                                updateRssiDisplay()
                                addDebugLog("📶 RSSI: $currentRssi dBm")
                            }
                        }
                        message.startsWith("THRESHOLD_SET") -> {
                            addDebugLog("✅ ESP32 confirmed threshold setting")
                            Toast.makeText(this@MainActivity,
                                "ESP32 threshold updated",
                                Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            updateStatus("Message received: $message")
                            showNotification("BLE Message", message)
                        }
                    }
                }
            }
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            statusText?.text = "Status: $status"
        }
        Log.d(TAG, "Status: $status")
    }

    private fun updateConnectionStatus(status: String, isConnected: Boolean) {
        runOnUiThread {
            connectionText?.text = "Connection: $status"
            connectionText?.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isConnected) android.R.color.holo_green_dark else android.R.color.holo_red_dark
                )
            )
        }
        Log.d(TAG, "Connection: $status")
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
            Log.e(TAG, "Notification permission needed: ${e.message}")
        }
    }

    private fun showCriticalNotification(title: String, text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 $title")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .setLights(0xFFFF0000.toInt(), 500, 500)
            .build()

        try {
            notificationManager.notify(999, notification) // Fixed ID for critical alerts
        } catch (e: SecurityException) {
            Log.e(TAG, "Critical notification permission needed: ${e.message}")
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
            Log.e(TAG, "Error vibrating phone: ${e.message}")
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
            // Durdurulmadan önce ESP32'ye alarm kapatma komutu gönder
            if (bleConnected) {
                sendBLECommand("ALARM_OFF")
            }

            stopBLEScan()
            bleGatt?.close()

            // SMS Receiver callback'ini temizle
            SmsReceiver.callback = null
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in onDestroy: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        updateAlarmUI()

        // Resume'da ESP32'ye mevcut alarm durumunu bildir
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
        // Critical notification'ı temizle
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(999)
    }
}