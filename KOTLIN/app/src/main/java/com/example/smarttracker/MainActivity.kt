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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class MainActivity : Activity() {
    private var bleGatt: BluetoothGatt? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var bleConnected = false

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
    private var requestGpsButton: Button? = null
    private var thresholdSpinner: Spinner? = null

    private var isAlarmEnabled = true
    private var isTrackingEnabled = false
    private var isAlarmActive = false
    private var prefs: SharedPreferences? = null

    private var isRssiTestActive = false
    private var currentRssi = 0
    private var rssiThreshold = -70
    private val rssiUpdateHandler = Handler(Looper.getMainLooper())
    private val wifiCheckHandler = Handler(Looper.getMainLooper())
    private val WIFI_CHECK_INTERVAL = 15000L // 15 saniyede bir WiFi kontrolü

    private val debugMessages = ConcurrentLinkedQueue<String>()
    private val maxDebugMessages = 25
    private var shouldAutoScroll = true
    private var userScrolledUp = false

    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_TIMEOUT = 30000L

    private var wifiConnected = false
    private var gsmConnected = false

    private var lastNotificationTime = 0L
    private val NOTIFICATION_COOLDOWN = 3000L
    private var lastCriticalNotificationTime = 0L
    private val CRITICAL_NOTIFICATION_COOLDOWN = 5000L
    private var lastLogTime = 0L
    private val LOG_COOLDOWN = 100L

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
        private const val PREF_TRACKING_ENABLED = "tracking_enabled"
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.READ_PHONE_STATE
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.READ_PHONE_STATE
        )
    }

    private val thresholdOptions = arrayOf(-50, -60, -70, -80, -90, -100)

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

        addDebugLog("SmartTracker - Initialized")
        updateConnectionStatus()
        checkSystemConnections()

        // Start periodic WiFi health check
        startPeriodicWiFiCheck()
    }

    private fun initializePreferences() {
        prefs = getSharedPreferences("smarttracker_prefs", Context.MODE_PRIVATE)
        isAlarmEnabled = prefs?.getBoolean(PREF_ALARM_ENABLED, true) ?: true
        isTrackingEnabled = prefs?.getBoolean(PREF_TRACKING_ENABLED, false) ?: false
        rssiThreshold = prefs?.getInt(PREF_RSSI_THRESHOLD, -70) ?: -70
        addDebugLog("Settings loaded - Alarm: $isAlarmEnabled, Tracking: $isTrackingEnabled, RSSI: $rssiThreshold dBm")
    }

    private fun savePreferences() {
        prefs?.edit()?.apply {
            putBoolean(PREF_ALARM_ENABLED, isAlarmEnabled)
            putBoolean(PREF_TRACKING_ENABLED, isTrackingEnabled)
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

        setupScrollListener()

        thresholdSpinner = findViewById<Spinner>(R.id.thresholdSpinner)?.apply {
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item,
                thresholdOptions.map { "${it} dBm" })
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            this.adapter = adapter

            val currentIndex = thresholdOptions.indexOf(rssiThreshold)
            if (currentIndex >= 0) {
                setSelection(currentIndex)
            }

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val newThreshold = thresholdOptions[position]
                    if (newThreshold != rssiThreshold) {
                        val oldThreshold = rssiThreshold
                        rssiThreshold = newThreshold
                        savePreferences()
                        sendThresholdUpdate()
                        updateRssiDisplay()
                        showToast("Alert distance updated: $rssiThreshold dBm (was $oldThreshold dBm)")
                        addDebugLog("Threshold changed via dropdown: $oldThreshold → $rssiThreshold dBm")
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        startButton = findViewById<Button>(R.id.startButton)?.apply {
            setOnClickListener {
                showToast("Starting tracking system...")
                startTracking()
            }
            isEnabled = false
        }

        stopButton = findViewById<Button>(R.id.stopButton)?.apply {
            setOnClickListener {
                showToast("Stopping tracking...")
                stopTracking()
            }
            isEnabled = false
        }

        alarmSwitch = findViewById<Switch>(R.id.alarmSwitch)?.apply {
            isChecked = isAlarmEnabled
            setOnCheckedChangeListener { _, isChecked ->
                val action = if (isChecked) "Alarm ENABLED" else "Alarm DISABLED"
                showToast(action)
                toggleAlarm(isChecked)
            }
        }

        silenceButton = findViewById<Button>(R.id.silenceButton)?.apply {
            setOnClickListener {
                showToast("Silencing alarm...")
                silenceAlarm()
            }
            isEnabled = false
        }

        clearLogButton = findViewById<Button>(R.id.clearLogButton)?.apply {
            setOnClickListener {
                showToast("Debug log cleared")
                clearDebugLog()
            }
        }

        rssiTestButton = findViewById<Button>(R.id.rssiTestButton)?.apply {
            setOnClickListener {
                if (!isRssiTestActive) {
                    showToast("Distance calibration started - walk to desired alert distance")
                } else {
                    showToast("Distance calibration stopped")
                }
                toggleRssiTest()
            }
            isEnabled = false
        }

        setThresholdButton = findViewById<Button>(R.id.setThresholdButton)?.apply {
            setOnClickListener {
                showToast("Setting current distance as alert threshold...")
                setCurrentRssiAsThreshold()
            }
            isEnabled = false
        }

        requestGpsButton = findViewById<Button>(R.id.requestGpsButton)?.apply {
            setOnClickListener {
                showToast("Requesting GPS location...")
                requestGPSLocation()
            }
            isEnabled = false
        }

        retryBleButton = findViewById<Button>(R.id.retryBleButton)?.apply {
            setOnClickListener {
                showToast("Retrying BLE connection...")
                retryConnection("bluetooth")
            }
        }

        retryWifiButton = findViewById<Button>(R.id.retryWifiButton)?.apply {
            setOnClickListener {
                if (wifiConnected) {
                    showToast("Requesting location SMS...")
                    requestLocationSMS()
                } else {
                    showToast("Testing WiFi connection...")
                    retryConnection("wifi")
                }
            }
        }

        retryGsmButton = findViewById<Button>(R.id.retryGsmButton)?.apply {
            setOnClickListener {
                showToast("Testing GSM connection...")
                retryConnection("gsm")
            }
        }

        updateAlarmUI()
        updateTrackingUI()
        updateRssiDisplay()
        updateConnectionStatus()
        addDebugLog("UI components initialized with popup notifications")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun requestGPSLocation() {
        if (bleConnected) {
            sendBLECommand("REQUEST_GPS")
            addDebugLog("GPS location requested via BLE")
        } else if (wifiConnected) {
            sendUDPCommand("REQUEST_GPS")
            addDebugLog("GPS location requested via WiFi")
        } else {
            showToast("No connection available for GPS request")
            addDebugLog("GPS request failed - no connection")
        }
    }

    private fun sendThresholdUpdate() {
        val command = "SET_THRESHOLD;$rssiThreshold"
        if (bleConnected) {
            sendBLECommand(command)
            addDebugLog("Threshold sent via BLE: $rssiThreshold dBm")
        } else if (wifiConnected) {
            sendUDPCommand(command)
            addDebugLog("Threshold sent via WiFi: $rssiThreshold dBm")
        }
    }

    private fun requestLocationSMS() {
        if (bleConnected) {
            sendBLECommand("REQUEST_SMS")
            addDebugLog("Location SMS requested via BLE")
        } else if (wifiConnected) {
            sendUDPCommand("REQUEST_SMS")
            addDebugLog("Location SMS requested via WiFi")
        } else {
            showToast("No connection available for SMS request")
            addDebugLog("SMS request failed - no connection")
        }
    }

    private fun setupScrollListener() {
        debugScrollView?.viewTreeObserver?.addOnScrollChangedListener {
            val view = debugScrollView
            val child = view?.getChildAt(0)
            if (view != null && child != null) {
                val maxScroll = child.height - view.height
                val currentScroll = view.scrollY
                userScrolledUp = currentScroll < maxScroll - 100
                shouldAutoScroll = !userScrolledUp
            }
        }
    }

    private fun addDebugLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logMessage = "[$timestamp] $message"

        debugMessages.offer(logMessage)

        while (debugMessages.size > maxDebugMessages) {
            debugMessages.poll()
        }

        runOnUiThread {
            val allMessages = debugMessages.toList().reversed().joinToString("\n")
            debugLogText?.text = allMessages

            if (shouldAutoScroll) {
                debugScrollView?.post {
                    debugScrollView?.smoothScrollTo(0, 0)
                }
            }
        }

        Log.d(TAG, logMessage)
    }

    private fun clearDebugLog() {
        debugMessages.clear()
        debugLogText?.text = ""
        shouldAutoScroll = true
        userScrolledUp = false
        addDebugLog("Debug log cleared")
    }

    private fun checkSystemConnections() {
        Thread {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            wifiConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } else {
                wifiManager.isWifiEnabled && wifiManager.connectionInfo.networkId != -1
            }

            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            gsmConnected = try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    telephonyManager.simState == TelephonyManager.SIM_STATE_READY &&
                            telephonyManager.networkType != TelephonyManager.NETWORK_TYPE_UNKNOWN
                } else {
                    telephonyManager.simState == TelephonyManager.SIM_STATE_READY
                }
            } catch (e: SecurityException) {
                addDebugLog("GSM check permission error")
                false
            }

            runOnUiThread {
                updateConnectionStatus()
                updateButtonLabels()
            }
        }.start()
    }

    private fun updateButtonLabels() {
        retryWifiButton?.text = if (wifiConnected) "REQUEST SMS" else "TEST WiFi"
    }

    private fun toggleAlarm(enabled: Boolean) {
        isAlarmEnabled = enabled
        savePreferences()

        if (enabled) {
            sendBLECommand("ALARM_ON")
            sendUDPCommand("ALARM_ON")
            addDebugLog("Alarm system ENABLED")
        } else {
            sendBLECommand("ALARM_OFF")
            sendUDPCommand("ALARM_OFF")
            isAlarmActive = false
            addDebugLog("Alarm system DISABLED")
        }

        updateAlarmUI()
        updateStatus("Alarm ${if (enabled) "enabled" else "disabled"}")
    }

    private fun silenceAlarm() {
        addDebugLog("Silence alarm requested by user - will disable for 5 minutes")

        var commandSent = false

        if (bleConnected) {
            sendBLECommand("SILENCE_ALARM")
            commandSent = true
            addDebugLog("Silence command sent via BLE")
        }

        if (wifiConnected) {
            sendUDPCommand("SILENCE_ALARM")
            commandSent = true
            addDebugLog("Silence command sent via WiFi")
        }

        if (!commandSent) {
            sendSMSCommand("SILENCE")
            addDebugLog("Silence command sent via SMS")
        }

        // CRITICAL: Always update local state immediately
        isAlarmActive = false
        updateAlarmUI()
        updateStatus("Alarm silenced for 5 minutes")

        showToast("🔇 Alarm silenced for 5 minutes via ${when {
            bleConnected -> "BLE"
            wifiConnected -> "WiFi"
            else -> "SMS"
        }}")

        // Force stop any local alarm indicators
        handler.postDelayed({
            isAlarmActive = false
            updateAlarmUI()
            addDebugLog("Local alarm state forced to inactive")
        }, 1000)
    }

    private fun sendSMSCommand(command: String) {
        try {
            val smsManager = android.telephony.SmsManager.getDefault()
            val phoneNumber = "+905447661357"
            smsManager.sendTextMessage(phoneNumber, null, command, null, null)
            addDebugLog("SMS command sent: $command to $phoneNumber")
        } catch (e: Exception) {
            addDebugLog("SMS send error: ${e.message}")
        }
    }

    private fun updateAlarmUI() {
        runOnUiThread {
            alarmSwitch?.isChecked = isAlarmEnabled

            val statusText: String
            val colorRes: Int

            when {
                !isAlarmEnabled -> {
                    statusText = "DISABLED"
                    colorRes = android.R.color.darker_gray
                }
                isAlarmActive -> {
                    statusText = "ACTIVE (alerting)"
                    colorRes = android.R.color.holo_red_dark
                }
                else -> {
                    statusText = "ENABLED (monitoring)"
                    colorRes = android.R.color.holo_green_dark
                }
            }

            alarmStatusText?.text = statusText
            alarmStatusText?.setTextColor(ContextCompat.getColor(this, colorRes))
            silenceButton?.isEnabled = isAlarmActive || isAlarmEnabled
        }
    }

    private fun updateTrackingUI() {
        runOnUiThread {
            // Always show static button text - don't change based on state
            startButton?.text = "START TRACKING"
            stopButton?.text = "STOP TRACKING"

            // Color changes to indicate state
            if (isTrackingEnabled) {
                startButton?.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                stopButton?.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            } else {
                startButton?.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                stopButton?.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            }
        }
    }

    private fun updateConnectionStatus() {
        runOnUiThread {
            bleStatusText?.apply {
                if (bleConnected) {
                    text = "BLE: Connected to ESP32"
                    setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
                } else {
                    text = "BLE: Searching..."
                    setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                }
            }

            wifiStatusText?.apply {
                val status = if (wifiConnected) "WiFi: Connected to ESP32 AP" else "WiFi: Connecting to ESP32 AP..."
                text = status
                val color = if (wifiConnected) android.R.color.holo_green_dark else android.R.color.holo_red_dark
                setTextColor(ContextCompat.getColor(this@MainActivity, color))
            }

            gsmStatusText?.apply {
                val status = if (gsmConnected) "GSM: Network ready" else "GSM: No network"
                text = status
                val color = if (gsmConnected) android.R.color.holo_green_dark else android.R.color.holo_red_dark
                setTextColor(ContextCompat.getColor(this@MainActivity, color))
            }

            val overallStatus = when {
                bleConnected -> "PRIMARY: BLE Connected"
                wifiConnected -> "BACKUP: WiFi Available"
                gsmConnected -> "EMERGENCY: GSM Ready"
                else -> "ALL OFFLINE"
            }
            connectionStatusCard?.text = overallStatus

            requestGpsButton?.isEnabled = bleConnected || wifiConnected
        }
    }

    private fun toggleRssiTest() {
        if (!bleConnected) {
            addDebugLog("RSSI test requires BLE connection")
            showToast("BLE connection required for distance test")
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
        rssiTestButton?.text = "STOP DISTANCE TEST"
        setThresholdButton?.isEnabled = false

        addDebugLog("Distance calibration started - alerts temporarily disabled")
        showToast("Distance test started - alerts disabled during calibration")
        sendBLECommand("START_RSSI_TEST")
        updateRssiPeriodically()
    }

    private fun stopRssiTest() {
        isRssiTestActive = false
        rssiTestButton?.text = "START DISTANCE TEST"
        setThresholdButton?.isEnabled = false
        rssiUpdateHandler.removeCallbacksAndMessages(null)

        addDebugLog("Distance calibration stopped - alerts re-enabled")
        showToast("Distance test stopped - alerts re-enabled")
        if (bleConnected) {
            sendBLECommand("STOP_RSSI_TEST")
        }
    }

    private fun updateRssiPeriodically() {
        if (!isRssiTestActive || !bleConnected) return

        bleGatt?.let { gatt ->
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.readRemoteRssi()
                } else {
                    addDebugLog("BLE permission denied for RSSI reading")
                    stopRssiTest()
                    return
                }
            } catch (e: SecurityException) {
                addDebugLog("Security error reading RSSI")
                stopRssiTest()
                return
            } catch (e: Exception) {
                addDebugLog("RSSI read error: ${e.message}")
                stopRssiTest()
                return
            }
        }

        if (bleConnected && isRssiTestActive) {
            rssiUpdateHandler.postDelayed({
                updateRssiPeriodically()
            }, 2000)
        }
    }

    private fun setCurrentRssiAsThreshold() {
        if (currentRssi != 0 && currentRssi < -20) {
            val oldThreshold = rssiThreshold
            rssiThreshold = currentRssi
            savePreferences()

            // CRITICAL: First stop the test, then send threshold
            stopRssiTest()

            // Small delay to ensure stop command is processed
            handler.postDelayed({
                // Send threshold to ESP32
                sendBLECommand("SET_THRESHOLD;$rssiThreshold")

                // Update UI
                updateRssiDisplay()

                // Update spinner to match new threshold
                val thresholdIndex = thresholdOptions.indexOf(rssiThreshold)
                if (thresholdIndex >= 0) {
                    thresholdSpinner?.setSelection(thresholdIndex)
                }

                addDebugLog("Distance threshold set: $oldThreshold → $rssiThreshold dBm")
                showToast("Alert distance updated: $rssiThreshold dBm (was $oldThreshold dBm)")
            }, 200) // 200ms delay to ensure proper sequencing

        } else {
            addDebugLog("Invalid RSSI for threshold: $currentRssi")
            showToast("Invalid signal strength - move closer and try again")
        }
    }

    private fun startPeriodicWiFiCheck() {
        wifiCheckHandler.postDelayed({
            if (wifiConnected) {
                // WiFi bağlıysa ping test gönder
                Thread {
                    try {
                        val socket = DatagramSocket()
                        socket.soTimeout = 3000 // Kısa timeout

                        val address = InetAddress.getByName("192.168.4.1")
                        val buffer = "PING_TEST".toByteArray()
                        val packet = DatagramPacket(buffer, buffer.size, address, 5000)

                        socket.send(packet)

                        val responseBuffer = ByteArray(256)
                        val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)

                        socket.receive(responsePacket)
                        socket.close()

                        // Başarılı - WiFi hala bağlı
                        addDebugLog("WiFi health check: OK")

                    } catch (e: Exception) {
                        // Başarısız - WiFi kopmuş
                        runOnUiThread {
                            if (wifiConnected) {
                                wifiConnected = false
                                updateConnectionStatus()
                                updateButtonLabels()
                                addDebugLog("WiFi health check failed - connection lost")
                            }
                        }
                    }
                }.start()
            }

            // Tekrar schedule et
            startPeriodicWiFiCheck()
        }, WIFI_CHECK_INTERVAL)
    }

    private fun stopPeriodicWiFiCheck() {
        wifiCheckHandler.removeCallbacksAndMessages(null)
    }

    private fun updateRssiDisplay() {
        runOnUiThread {
            val rssiText: String

            if (isRssiTestActive && currentRssi != 0) {
                val signalQuality = when {
                    currentRssi > -50 -> "Excellent"
                    currentRssi > -60 -> "Good"
                    currentRssi > -70 -> "Fair"
                    currentRssi > -80 -> "Weak"
                    else -> "Very Weak"
                }
                rssiText = "Current: ${currentRssi} dBm ($signalQuality) | Alert: ${rssiThreshold} dBm"
                setThresholdButton?.isEnabled = true
            } else if (currentRssi != 0) {
                // Show current RSSI even when not testing
                val status = if (currentRssi > rssiThreshold) "SAFE" else "⚠️ ALERT ZONE"
                rssiText = "Current: ${currentRssi} dBm ($status) | Alert Distance: ${rssiThreshold} dBm"
            } else {
                rssiText = "Alert Distance: ${rssiThreshold} dBm | Current: No signal"
            }

            rssiTestText?.text = rssiText

            val color = when {
                currentRssi == 0 -> android.R.color.darker_gray
                !isRssiTestActive && currentRssi <= rssiThreshold -> android.R.color.holo_red_dark
                !isRssiTestActive && currentRssi > rssiThreshold -> android.R.color.holo_green_dark
                isRssiTestActive && currentRssi > rssiThreshold -> android.R.color.holo_green_dark
                isRssiTestActive && currentRssi > rssiThreshold - 10 -> android.R.color.holo_orange_dark
                else -> android.R.color.holo_red_dark
            }

            rssiTestText?.setTextColor(ContextCompat.getColor(this, color))
        }
    }

    private fun retryConnection(connectionType: String) {
        addDebugLog("Manual retry: $connectionType")

        when (connectionType.lowercase()) {
            "bluetooth", "ble" -> {
                if (bleConnected) {
                    showToast("BLE already connected")
                    return
                }

                if (isScanning) {
                    stopBLEScan()
                }

                handler.postDelayed({
                    startBLEScan()
                }, 1000)
            }

            "wifi" -> {
                checkSystemConnections()
                // Force a WiFi connectivity test
                Thread {
                    try {
                        val socket = DatagramSocket()
                        socket.soTimeout = 5000

                        val address = InetAddress.getByName("192.168.4.1")
                        val buffer = "PING_TEST".toByteArray()
                        val packet = DatagramPacket(buffer, buffer.size, address, 5000)

                        socket.send(packet)

                        val responseBuffer = ByteArray(256)
                        val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)

                        socket.receive(responsePacket)
                        socket.close()

                        // Success
                        runOnUiThread {
                            if (!wifiConnected) {
                                wifiConnected = true
                                updateConnectionStatus()
                                updateButtonLabels()
                                showToast("WiFi connection restored!")
                                addDebugLog("WiFi manual test successful")
                            } else {
                                showToast("WiFi connection OK")
                            }
                        }

                    } catch (e: Exception) {
                        // Failed
                        runOnUiThread {
                            wifiConnected = false
                            updateConnectionStatus()
                            updateButtonLabels()
                            showToast("WiFi test failed - check ESP32 AP")
                            addDebugLog("WiFi manual test failed: ${e.message}")
                        }
                    }
                }.start()
            }

            "gsm" -> {
                checkSystemConnections()
                sendBLECommand("GET_GSM_STATUS")
                sendUDPCommand("GET_GSM_STATUS")
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
                            addDebugLog("BLE → $command")
                        } else {
                            addDebugLog("BLE command failed: $command")
                        }
                    } else {
                        addDebugLog("BLE permission denied")
                    }
                } catch (e: SecurityException) {
                    addDebugLog("BLE security error: ${e.message}")
                }
            } else {
                addDebugLog("BLE characteristic not available")
            }
        } ?: run {
            addDebugLog("BLE not connected - command ignored: $command")
        }
    }

    private fun sendUDPCommand(command: String) {
        Thread {
            try {
                val socket = DatagramSocket()
                socket.soTimeout = 5000

                val address = InetAddress.getByName("192.168.4.1")
                val buffer = command.toByteArray()
                val packet = DatagramPacket(buffer, buffer.size, address, UDP_PORT)

                socket.send(packet)

                val responseBuffer = ByteArray(1024)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)

                try {
                    socket.receive(responsePacket)
                    val response = String(responsePacket.data, 0, responsePacket.length)
                    addDebugLog("UDP → $command ← $response")

                    // WiFi is working if we got a response
                    if (!wifiConnected) {
                        wifiConnected = true
                        runOnUiThread {
                            updateConnectionStatus()
                            updateButtonLabels()
                            addDebugLog("WiFi connection restored")
                        }
                    }

                    runOnUiThread {
                        processUDPResponse(command, response)
                    }
                } catch (e: Exception) {
                    addDebugLog("UDP → $command (sent, no response)")
                    // No response doesn't necessarily mean WiFi is down
                }

                socket.close()

                if (command == "PING_TEST") {
                    wifiConnected = true
                    runOnUiThread {
                        updateConnectionStatus()
                        updateButtonLabels()
                    }
                    startWiFiAlertListener()
                }

            } catch (e: Exception) {
                addDebugLog("UDP error: ${e.message}")

                // WiFi connection failed - mark as disconnected
                if (wifiConnected) {
                    wifiConnected = false
                    runOnUiThread {
                        updateConnectionStatus()
                        updateButtonLabels()
                        addDebugLog("WiFi connection lost - UDP failed")
                    }
                }
            }
        }.start()
    }

    private fun processUDPResponse(command: String, response: String) {
        when {
            response.startsWith("ALARM_SILENCED") -> {
                val parts = response.split(";")
                val duration = if (parts.size > 1) parts[1] else "UNKNOWN"
                showToast("✅ Alarm silenced via WiFi for $duration")
                isAlarmActive = false
                updateAlarmUI()
            }
            response.startsWith("THRESHOLD_INITIALIZED") -> {
                val parts = response.split(";")
                if (parts.size > 1) {
                    try {
                        val confirmedThreshold = parts[1].toInt()
                        addDebugLog("WiFi confirmed initial threshold: $confirmedThreshold dBm")
                        showToast("✅ Distance threshold synchronized via WiFi: $confirmedThreshold dBm")
                    } catch (e: NumberFormatException) {
                        addDebugLog("Invalid threshold confirmation via WiFi")
                    }
                }
            }
            response.startsWith("THRESHOLD_SET") -> {
                val parts = response.split(";")
                if (parts.size > 1) {
                    try {
                        val confirmedThreshold = parts[1].toInt()
                        addDebugLog("WiFi confirmed threshold: $confirmedThreshold dBm")
                        showToast("✅ Distance threshold set via WiFi: $confirmedThreshold dBm")
                    } catch (e: NumberFormatException) {
                        addDebugLog("Invalid threshold confirmation via WiFi")
                    }
                }
            }
            response.startsWith("TRACKING_STATUS") -> {
                val parts = response.split(";")
                if (parts.size > 1) {
                    val status = parts[1]
                    isTrackingEnabled = status == "ENABLED"
                    savePreferences()
                    updateTrackingUI()
                    showToast("✅ Tracking ${status.lowercase()} via WiFi")
                }
            }
            response.startsWith("GPS_DATA") -> {
                val parts = response.split(";")
                if (parts.size > 1) {
                    if (parts[1] == "NO_FIX") {
                        updateGpsStatus("GPS: No fix available")
                        showToast("📍 GPS: No satellite fix")
                    } else {
                        val locationMatch = Regex("""LAT=([\d.-]+);LON=([\d.-]+)""").find(response)
                        if (locationMatch != null) {
                            val lat = locationMatch.groupValues[1]
                            val lon = locationMatch.groupValues[2]
                            updateGpsStatus("GPS: $lat, $lon")
                            showToast("📍 GPS location received")
                        }
                    }
                }
            }
            response.startsWith("SMS_SENT") -> {
                showToast("📱 Location SMS sent successfully")
            }
            response.startsWith("SMS_FAILED") -> {
                showToast("❌ SMS failed - GSM not available")
            }
            response.startsWith("GSM_STATUS") -> {
                val status = response.split(";").getOrNull(1)
                gsmConnected = status == "CONNECTED"
                updateConnectionStatus()
                showToast("GSM Status: $status")
            }
        }
    }

    private fun startWiFiAlertListener() {
        Thread {
            try {
                val socket = DatagramSocket(5001)
                socket.soTimeout = 3000

                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                var lastWifiAlertTime = 0L
                val WIFI_ALERT_COOLDOWN = 5000L

                while (stopButton?.isEnabled == true || isTrackingEnabled) {
                    try {
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length)

                        if (message.startsWith("TRACKER_ALERT:")) {
                            val currentTime = System.currentTimeMillis()

                            if (currentTime - lastWifiAlertTime > WIFI_ALERT_COOLDOWN) {
                                runOnUiThread {
                                    handleTrackerAlert("WiFi Alert", message.replace("TRACKER_ALERT:", ""))
                                }
                                lastWifiAlertTime = currentTime
                            }
                        }
                    } catch (e: Exception) {
                        // Timeout normal, continue listening
                        // But check if WiFi is still connected
                        if (wifiConnected) {
                            runOnUiThread {
                                // Simple connectivity check - if we can't listen, probably disconnected
                                wifiConnected = false
                                updateConnectionStatus()
                                updateButtonLabels()
                                addDebugLog("WiFi alert listener failed - connection lost")
                            }
                            break // Exit listener
                        }
                    }
                }

                socket.close()
            } catch (e: Exception) {
                addDebugLog("WiFi listener error: ${e.message}")
                runOnUiThread {
                    if (wifiConnected) {
                        wifiConnected = false
                        updateConnectionStatus()
                        updateButtonLabels()
                        addDebugLog("WiFi listener startup failed - connection lost")
                    }
                }
            }
        }.start()
    }

    private fun handleTrackerAlert(title: String, message: String) {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastLogTime > LOG_COOLDOWN) {
            addDebugLog("ALERT: $title - $message")
            lastLogTime = currentTime
        }

        if (currentTime - lastNotificationTime > NOTIFICATION_COOLDOWN) {
            showNotification(title, message)
            lastNotificationTime = currentTime
        }

        updateStatus("Alert: $message")

        if (isAlarmEnabled && (message.contains("ALERT") ||
                    message.contains("disconnected") ||
                    message.contains("moved away") ||
                    message.contains("connection lost"))) {

            if (currentTime - lastCriticalNotificationTime > CRITICAL_NOTIFICATION_COOLDOWN) {
                isAlarmActive = true
                updateAlarmUI()
                showCriticalNotification(title, message)
                vibratePhone()
                lastCriticalNotificationTime = currentTime

                showToast("🚨 CRITICAL ALERT: $title")
            }
        }
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
                addDebugLog("All permissions granted")
                showToast("All permissions granted")
                initializeBluetooth()
            } else {
                addDebugLog("Some permissions denied")
                showToast("Some permissions denied - app may not work properly")
                updateStatus("Permissions required for operation")
            }
        }
    }

    private fun initializeBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            addDebugLog("Device does not support Bluetooth")
            updateStatus("Bluetooth not supported")
            showToast("Device does not support Bluetooth")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            addDebugLog("Requesting Bluetooth enable...")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
                } else {
                    addDebugLog("Bluetooth permission required")
                }
            } catch (e: SecurityException) {
                addDebugLog("Bluetooth permission error")
            }
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bleScanner = bluetoothAdapter.bluetoothLeScanner
            if (bleScanner == null) {
                addDebugLog("BLE scanner not available")
                updateStatus("BLE scanner not available")
                return
            }

            addDebugLog("Bluetooth system ready")
            updateStatus("Ready to start tracking")
            startButton?.isEnabled = true
        } else {
            addDebugLog("BLE scan permission required")
            updateStatus("BLE permissions needed")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                addDebugLog("Bluetooth enabled by user")
                showToast("Bluetooth enabled")
                initializeBluetooth()
            } else {
                addDebugLog("Bluetooth enable cancelled")
                showToast("Bluetooth required for operation")
                updateStatus("Bluetooth required")
            }
        }
    }

    private fun startTracking() {
        if (!checkPermissions()) {
            requestPermissions()
            return
        }

        isTrackingEnabled = true
        savePreferences()
        updateTrackingUI()

        startButton?.isEnabled = false
        stopButton?.isEnabled = true

        addDebugLog("=== TRACKING STARTED ===")
        startBLEScan()

        handler.postDelayed({
            if (bleConnected) {
                // Send tracking and threshold initialization
                sendBLECommand("START_TRACKING")
                sendBLECommand("INIT_THRESHOLD;$rssiThreshold")

                addDebugLog("Tracking commands sent via BLE")

                handler.postDelayed({
                    syncAlarmStatus()
                }, 1000)
            } else if (wifiConnected) {
                // Send via WiFi if BLE not available
                sendUDPCommand("START_TRACKING")
                sendUDPCommand("INIT_THRESHOLD;$rssiThreshold")

                addDebugLog("Tracking commands sent via WiFi")
            }
        }, 2000)
    }

    private fun stopTracking() {
        addDebugLog("=== TRACKING STOPPED ===")

        isTrackingEnabled = false
        savePreferences()
        updateTrackingUI()

        rssiUpdateHandler.removeCallbacksAndMessages(null)
        isRssiTestActive = false

        if (bleConnected) {
            sendBLECommand("STOP_TRACKING")
        }
        if (wifiConnected) {
            sendUDPCommand("STOP_TRACKING")
        }

        startButton?.isEnabled = true
        stopButton?.isEnabled = false
        silenceButton?.isEnabled = isAlarmEnabled
        rssiTestButton?.isEnabled = bleConnected
        setThresholdButton?.isEnabled = false
        isAlarmActive = false

        updateAlarmUI()
        updateRssiDisplay()
        updateConnectionStatus()
        updateStatus("Tracking stopped")
        showToast("Tracking stopped - BLE connection maintained")
    }

    private fun resetUIState() {
        bleConnected = false
        currentRssi = 0

        startButton?.isEnabled = true
        stopButton?.isEnabled = false
        silenceButton?.isEnabled = isAlarmEnabled
        rssiTestButton?.isEnabled = false
        setThresholdButton?.isEnabled = false
        isAlarmActive = false

        updateAlarmUI()
        updateTrackingUI()
        updateRssiDisplay()
        updateConnectionStatus()
    }

    private fun startBLEScan() {
        if (!checkPermissions()) {
            addDebugLog("BLE scan requires permissions")
            return
        }

        if (isScanning) {
            addDebugLog("Already scanning - restarting...")
            stopBLEScan()
            handler.postDelayed({ startBLEScan() }, 1000)
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            addDebugLog("Bluetooth not available")
            updateStatus("Bluetooth required")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            addDebugLog("BLE scan permission required")
            updateStatus("Scan permission needed")
            return
        }

        bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            addDebugLog("BLE scanner unavailable")
            updateStatus("Scanner not available")
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
            addDebugLog("BLE scan started - looking for ESP32_TRACKER")

            handler.postDelayed({
                if (isScanning && !bleConnected) {  // Only show error if actually not connected
                    stopBLEScan()
                    updateStatus("ESP32_TRACKER not found")
                    addDebugLog("ESP32_TRACKER not found - scan timeout")
                    showToast("ESP32_TRACKER not found - check if device is powered on")

                    if (!isTrackingEnabled) {
                        resetUIState()
                    }
                } else if (isScanning && bleConnected) {
                    // Connected during scan - just stop scanning
                    stopBLEScan()
                    addDebugLog("BLE scan stopped - already connected")
                }
            }, SCAN_TIMEOUT)

        } catch (e: SecurityException) {
            addDebugLog("BLE scan permission denied")
            updateStatus("Permission denied")
            isScanning = false
            if (!isTrackingEnabled) {
                resetUIState()
            }
        }
    }

    private fun stopBLEScan() {
        if (!isScanning) return

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bleScanner?.stopScan(scanCallback)
                isScanning = false
                addDebugLog("BLE scan stopped")
            }
        } catch (e: SecurityException) {
            addDebugLog("Error stopping BLE scan")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    addDebugLog("BLE connect permission required")
                    return
                }

                val deviceName = result.device.name
                val rssi = result.rssi

                if (deviceName == "ESP32_TRACKER") {
                    updateStatus("ESP32_TRACKER found! Connecting...")
                    addDebugLog("ESP32_TRACKER found! Signal: $rssi dBm")
                    showToast("ESP32_TRACKER found! Connecting...")

                    stopBLEScan()

                    bleGatt?.let { oldGatt ->
                        try {
                            oldGatt.disconnect()
                            oldGatt.close()
                            addDebugLog("Previous connection closed")
                        } catch (e: Exception) {
                            addDebugLog("Error closing old connection")
                        }
                    }

                    bleGatt = result.device.connectGatt(
                        this@MainActivity,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                }
            } catch (e: SecurityException) {
                addDebugLog("Security error in scan result")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            val errorMessage = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already active"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature not supported"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                else -> "Unknown error: $errorCode"
            }

            addDebugLog("BLE scan failed: $errorMessage")
            updateStatus("Scan failed")
            showToast("BLE scan failed: $errorMessage")
            if (!isTrackingEnabled) {
                resetUIState()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentRssi = rssi

                runOnUiThread {
                    updateRssiDisplay()
                    if (isRssiTestActive) {
                        setThresholdButton?.isEnabled = (rssi < -20)
                    }
                }

                if (bleConnected) {
                    sendBLECommand("ANDROID_RSSI;$rssi")
                }
            } else {
                addDebugLog("RSSI read failed: status $status")
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    bleConnected = true
                    runOnUiThread {
                        updateStatus("Connected to ESP32_TRACKER!")
                        updateConnectionStatus()
                        addDebugLog("BLE connection established")
                        rssiTestButton?.isEnabled = true
                        showNotification("Tracker Connected", "ESP32_TRACKER is now connected")
                        showToast("Connected to ESP32_TRACKER!")
                    }

                    try {
                        Thread.sleep(600)
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            gatt.discoverServices()
                        }
                    } catch (e: SecurityException) {
                        addDebugLog("Error discovering services - permission")
                    } catch (e: InterruptedException) {
                        addDebugLog("Service discovery interrupted")
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
                        updateRssiDisplay()  // Update to show no signal
                        addDebugLog("BLE connection lost")

                        if (isRssiTestActive) {
                            stopRssiTest()
                        }
                        rssiTestButton?.isEnabled = false

                        if (isTrackingEnabled) {
                            addDebugLog("Auto-reconnection starting in 3 seconds...")
                            showToast("Connection lost - attempting reconnection...")
                            handler.postDelayed({
                                if (!bleConnected && isTrackingEnabled) {
                                    addDebugLog("Attempting reconnection...")
                                    startBLEScan()
                                }
                            }, 3000)
                        } else {
                            addDebugLog("Not tracking - no auto-reconnect")
                        }

                        // ALWAYS send disconnect notification when BLE drops
                        if (wasConnected) {
                            if (isTrackingEnabled) {
                                handleTrackerAlert("Connection Lost", "ESP32_TRACKER disconnected - possible theft!")

                                if (isAlarmEnabled) {
                                    isAlarmActive = true
                                    updateAlarmUI()
                                }
                            } else {
                                // Send notification even when not tracking
                                handleTrackerAlert("BLE Disconnected", "ESP32_TRACKER connection lost")
                            }
                        }
                    }

                    try {
                        gatt.close()
                        addDebugLog("GATT connection closed")
                    } catch (e: Exception) {
                        addDebugLog("Error closing GATT")
                    }
                }

                BluetoothProfile.STATE_CONNECTING -> {
                    runOnUiThread {
                        updateStatus("Connecting to ESP32_TRACKER...")
                        addDebugLog("BLE connection in progress...")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addDebugLog("BLE services discovered")

                val service = gatt.getService(UUID.fromString(BLE_SERVICE_UUID))
                if (service != null) {
                    addDebugLog("Target service found")
                    val characteristic = service.getCharacteristic(UUID.fromString(BLE_CHAR_UUID))
                    if (characteristic != null) {
                        addDebugLog("Setting up notifications...")
                        runOnUiThread {
                            updateStatus("Setting up communication...")
                        }

                        try {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                val success = gatt.setCharacteristicNotification(characteristic, true)

                                val descriptor = characteristic.getDescriptor(
                                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                                )
                                if (descriptor != null) {
                                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    gatt.writeDescriptor(descriptor)
                                    addDebugLog("Notification descriptor written")
                                } else {
                                    addDebugLog("Notification descriptor not found")
                                }
                            }
                        } catch (e: SecurityException) {
                            addDebugLog("Security error enabling notifications")
                        }
                    } else {
                        addDebugLog("Target characteristic not found")
                        runOnUiThread {
                            updateStatus("Characteristic not found")
                        }
                    }
                } else {
                    addDebugLog("Target service not found")
                    runOnUiThread {
                        updateStatus("Service not found")
                    }
                }
            } else {
                addDebugLog("Service discovery failed: status $status")
                runOnUiThread {
                    updateStatus("Service discovery failed")
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addDebugLog("BLE notifications enabled - communication ready!")
                runOnUiThread {
                    updateStatus("Communication established!")
                    showToast("Communication ready - syncing settings...")
                }

                handler.postDelayed({
                    // ALWAYS sync both threshold and tracking state on EVERY connection
                    sendBLECommand("INIT_THRESHOLD;$rssiThreshold")

                    if (isTrackingEnabled) {
                        sendBLECommand("START_TRACKING")
                        addDebugLog("BLE sync: Tracking ENABLED")
                    } else {
                        sendBLECommand("STOP_TRACKING")
                        addDebugLog("BLE sync: Tracking DISABLED")
                    }

                    handler.postDelayed({
                        syncAlarmStatus()
                        startPeriodicRSSIReading(gatt)
                    }, 500)
                }, 1000)

            } else {
                addDebugLog("Failed to enable notifications: status $status")
                runOnUiThread {
                    updateStatus("Notification setup failed")
                    showToast("Communication setup failed")
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

    private fun syncAlarmStatus() {
        if (bleConnected) {
            if (isAlarmEnabled) {
                sendBLECommand("ALARM_ON")
            } else {
                sendBLECommand("ALARM_OFF")
            }

            handler.postDelayed({
                sendBLECommand("GET_ALARM_STATUS")
            }, 500)
        }
    }

    private fun startPeriodicRSSIReading(gatt: BluetoothGatt) {
        // Always read RSSI when BLE is connected, regardless of tracking state
        if (!bleConnected) {
            return
        }

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                gatt.readRemoteRssi()
            } else {
                addDebugLog("BLE connect permission required for RSSI")
                return
            }
        } catch (e: Exception) {
            if (bleConnected) {
                addDebugLog("RSSI read error: ${e.message}")
            }
        }

        // Continue reading while BLE is connected
        if (bleConnected) {
            handler.postDelayed({
                startPeriodicRSSIReading(gatt)
            }, 5000) // Every 5 seconds
        }
    }

    private fun processESP32Message(message: String) {
        val shouldLog = when {
            message.startsWith("ALERT") -> true
            message.startsWith("ALARM_STATUS") -> true
            message.startsWith("ALARM_SILENCED") -> true
            message.startsWith("THRESHOLD_INITIALIZED") -> true
            message.startsWith("THRESHOLD_SET") -> true
            message.startsWith("TRACKING_STATUS") -> true
            message.startsWith("GPS_DATA") -> true
            message.startsWith("PONG") -> true
            message.startsWith("LOCATION") -> true
            message.startsWith("GSM_STATUS") -> true
            message.startsWith("RSSI_TEST_") -> true
            message.startsWith("SMS_SENT") -> true
            message.startsWith("SMS_FAILED") -> true
            else -> false
        }

        if (shouldLog) {
            addDebugLog("ESP32 ← $message")
        }

        when {
            message.startsWith("ALERT") -> {
                handleTrackerAlert("ESP32 Alert", message)
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
                        addDebugLog("Alarm status synced: $status")
                    }

                    updateStatus("ESP32 Alarm: $status")
                }
            }
            message.startsWith("TRACKING_STATUS") -> {
                val parts = message.split(";")
                if (parts.size > 1) {
                    val status = parts[1]
                    val enabled = status == "ENABLED"

                    if (isTrackingEnabled != enabled) {
                        isTrackingEnabled = enabled
                        savePreferences()
                        updateTrackingUI()
                        addDebugLog("Tracking status synced: $status")
                    }

                    updateStatus("ESP32 Tracking: $status")
                }
            }
            message.startsWith("THRESHOLD_INITIALIZED") -> {
                val parts = message.split(";")
                if (parts.size > 1) {
                    try {
                        val confirmedThreshold = parts[1].toInt()
                        addDebugLog("ESP32 confirmed initial threshold: $confirmedThreshold dBm")
                        showToast("✅ Distance threshold synchronized: $confirmedThreshold dBm")
                        updateRssiDisplay()
                    } catch (e: NumberFormatException) {
                        addDebugLog("Invalid threshold confirmation from ESP32")
                    }
                }
            }
            message.startsWith("ALARM_SILENCED") -> {
                val parts = message.split(";")
                val duration = if (parts.size > 1) parts[1] else "UNKNOWN"

                isAlarmActive = false
                updateAlarmUI()
                updateStatus("Alarm silenced by ESP32 for $duration")
                addDebugLog("ESP32 confirmed alarm silenced for $duration")
                showToast("✅ Alarm silenced for $duration")
            }
            message.startsWith("THRESHOLD_SET") -> {
                val parts = message.split(";")
                if (parts.size > 1) {
                    try {
                        val newThreshold = parts[1].toInt()
                        if (rssiThreshold != newThreshold) {
                            rssiThreshold = newThreshold
                            savePreferences()
                            updateRssiDisplay()

                            val thresholdIndex = thresholdOptions.indexOf(rssiThreshold)
                            if (thresholdIndex >= 0) {
                                thresholdSpinner?.setSelection(thresholdIndex)
                            }
                        }
                        addDebugLog("Distance threshold confirmed: $newThreshold dBm")
                        showToast("Distance threshold confirmed: $newThreshold dBm")
                    } catch (e: NumberFormatException) {
                        addDebugLog("Invalid threshold format from ESP32")
                    }
                }
            }
            message.startsWith("GPS_DATA") -> {
                val parts = message.split(";")
                if (parts.size > 1) {
                    if (parts[1] == "NO_FIX") {
                        updateGpsStatus("GPS: No fix available")
                        showToast("📍 GPS: No satellite fix")
                    } else {
                        val locationMatch = Regex("""LAT=([\d.-]+);LON=([\d.-]+)""").find(message)
                        if (locationMatch != null) {
                            val lat = locationMatch.groupValues[1]
                            val lon = locationMatch.groupValues[2]
                            updateGpsStatus("GPS: $lat, $lon")
                            showToast("📍 GPS location received")
                        }
                    }
                }
            }
            message.startsWith("PONG") || message.startsWith("LOCATION") -> {
                updateStatus("Location data received")
                val locationMatch = Regex("""LAT=([\d.-]+);LON=([\d.-]+)""").find(message)
                if (locationMatch != null) {
                    val lat = locationMatch.groupValues[1]
                    val lon = locationMatch.groupValues[2]
                    updateGpsStatus("GPS: $lat, $lon")
                    addDebugLog("GPS coordinates: $lat, $lon")
                    showToast("📍 GPS location received")
                } else {
                    updateGpsStatus("GPS: Data received")
                }
            }
            message.startsWith("GSM_STATUS") -> {
                val parts = message.split(";")
                if (parts.size > 1) {
                    val status = parts[1]
                    gsmConnected = status == "CONNECTED"
                    updateConnectionStatus()
                    addDebugLog("GSM network: $status")
                }
            }
            message.startsWith("RSSI_TEST_") -> {
                if (message == "RSSI_TEST_STARTED") {
                    addDebugLog("ESP32 distance test started")
                    showToast("Distance test started on ESP32")
                } else if (message == "RSSI_TEST_STOPPED") {
                    addDebugLog("ESP32 distance test stopped")
                    showToast("Distance test stopped on ESP32")
                }
            }
            message.startsWith("SMS_SENT") -> {
                addDebugLog("SMS sent confirmation from ESP32")
                showToast("📱 SMS sent successfully!")
            }
            message.startsWith("SMS_FAILED") -> {
                addDebugLog("SMS failed confirmation from ESP32")
                showToast("❌ SMS sending failed")
            }
            else -> {
                updateStatus("ESP32: $message")
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
            gpsStatusText?.text = status
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
            .setOnlyAlertOnce(true)
            .build()

        try {
            notificationManager.notify(1001, notification)
        } catch (e: SecurityException) {
            addDebugLog("Notification permission required")
        }
    }

    private fun showCriticalNotification(title: String, text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 ALERT: $title")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .setLights(0xFFFF0000.toInt(), 500, 500)
            .build()

        try {
            notificationManager.notify(999, notification)
        } catch (e: SecurityException) {
            addDebugLog("Critical notification permission required")
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
            addDebugLog("Vibration error: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "SmartTracker Alerts"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = "Real-time tracker alerts and notifications"
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
            bleGatt?.let { gatt ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.disconnect()
                }
                gatt.close()
            }
            SmsReceiver.callback = null
            rssiUpdateHandler.removeCallbacksAndMessages(null)
            handler.removeCallbacksAndMessages(null)
            stopPeriodicWiFiCheck()
        } catch (e: SecurityException) {
            addDebugLog("Error in cleanup")
        }
    }

    override fun onResume() {
        super.onResume()
        updateAlarmUI()
        updateTrackingUI()
        updateConnectionStatus()
        checkSystemConnections()

        handler.postDelayed({
            if (bleConnected) {
                // Send current threshold
                sendBLECommand("INIT_THRESHOLD;$rssiThreshold")

                // Send current tracking state
                if (isTrackingEnabled) {
                    sendBLECommand("START_TRACKING")
                    addDebugLog("Resume: Tracking state synced (ENABLED)")
                } else {
                    sendBLECommand("STOP_TRACKING")
                    addDebugLog("Resume: Tracking state synced (DISABLED)")
                }

                handler.postDelayed({
                    syncAlarmStatus()
                }, 500)
            } else if (wifiConnected) {
                sendUDPCommand("INIT_THRESHOLD;$rssiThreshold")

                if (isTrackingEnabled) {
                    sendUDPCommand("START_TRACKING")
                } else {
                    sendUDPCommand("STOP_TRACKING")
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