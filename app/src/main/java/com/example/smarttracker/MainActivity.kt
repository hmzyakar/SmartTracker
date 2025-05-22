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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.*

class MainActivity : Activity() {
    private val CHANNEL_ID = "smarttracker"
    private var bleGatt: BluetoothGatt? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var statusText: TextView? = null
    private var connectionText: TextView? = null
    private var startButton: Button? = null
    private var stopButton: Button? = null

    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_TIMEOUT = 30000L // 30 saniye tarama timeout

    companion object {
        private const val TAG = "SmartTracker"
        const val BLE_SERVICE_UUID = "12345678-1234-1234-1234-1234567890ab"
        const val BLE_CHAR_UUID = "abcd1234-ab12-cd34-ef00-1234567890ab"
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_ENABLE_BT = 1002
    }

    // Gerekli izinler
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        createNotificationChannel()

        // İzinleri kontrol et
        if (checkPermissions()) {
            initializeBluetooth()
        } else {
            requestPermissions()
        }
    }

    private fun initializeViews() {
        statusText = findViewById<TextView>(R.id.statusText)
        connectionText = findViewById<TextView>(R.id.connectionText)

        startButton = findViewById<Button>(R.id.startButton)?.apply {
            setOnClickListener { startTracking() }
        }

        stopButton = findViewById<Button>(R.id.stopButton)?.apply {
            setOnClickListener { stopTracking() }
            isEnabled = false
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
        startBLEScan()
    }

    private fun stopTracking() {
        stopBLEScan()
        bleGatt?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception disconnecting GATT: ${e.message}")
            }
        }
        bleGatt = null

        startButton?.isEnabled = true
        stopButton?.isEnabled = false
        updateStatus("Tracking stopped")
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
            return
        }

        bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            Toast.makeText(this, "BLE scanner not available", Toast.LENGTH_LONG).show()
            updateStatus("BLE scanner not available")
            return
        }

        // Tarama ayarları - daha agresif tarama
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
            Toast.makeText(this, "Scanning for ESP32_TRACKER...", Toast.LENGTH_LONG).show()
            Log.d(TAG, "BLE scan started with aggressive settings")

            // Timeout için handler
            handler.postDelayed({
                if (isScanning) {
                    Log.d(TAG, "Scan timeout reached")
                    stopBLEScan()
                    updateStatus("Scan timeout - ESP32_TRACKER not found")
                    Toast.makeText(this, "ESP32_TRACKER not found. Check if device is on and nearby.", Toast.LENGTH_LONG).show()
                }
            }, SCAN_TIMEOUT)

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting scan: ${e.message}")
            Toast.makeText(this, "Bluetooth scan permission needed", Toast.LENGTH_LONG).show()
            updateStatus("Permission denied")
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

                    // Bağlantı denemesi
                    bleGatt = result.device.connectGatt(
                        this@MainActivity,
                        false, // autoConnect = false (daha hızlı bağlantı)
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
                    Log.d(TAG, "Connected to ESP32_TRACKER! Status: $status")
                    runOnUiThread {
                        updateStatus("Connected to ESP32_TRACKER!")
                        updateConnectionStatus("Connected", true)
                        Toast.makeText(this@MainActivity, "Connected to ESP32_TRACKER!", Toast.LENGTH_LONG).show()
                        showNotification("BLE Connected", "Successfully connected to ESP32_TRACKER")
                    }

                    try {
                        // Service discovery başlat
                        Thread.sleep(600) // Android'in settle olması için kısa bekleme
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception discovering services: ${e.message}")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from ESP32_TRACKER. Status: $status")
                    runOnUiThread {
                        updateStatus("Disconnected from ESP32_TRACKER")
                        updateConnectionStatus("Disconnected", false)
                        Toast.makeText(this@MainActivity, "Disconnected from ESP32_TRACKER", Toast.LENGTH_LONG).show()
                        startButton?.isEnabled = true
                        stopButton?.isEnabled = false
                    }
                }

                BluetoothProfile.STATE_CONNECTING -> {
                    Log.d(TAG, "Connecting to ESP32_TRACKER...")
                    runOnUiThread {
                        updateStatus("Connecting...")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered! Status: $status")

                // Mevcut servisleri listele
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

                runOnUiThread {
                    updateStatus("Message received: $message")
                    Toast.makeText(this@MainActivity, "Message: $message", Toast.LENGTH_LONG).show()
                    showNotification("BLE Message", message)
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(1000, 1000, 1000))
            .build()

        try {
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission needed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "SmartTracker Alerts"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = "Channel for tracker notifications"
                enableVibration(true)
                vibrationPattern = longArrayOf(1000, 1000, 1000)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            stopBLEScan()
            bleGatt?.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in onDestroy: ${e.message}")
        }
    }
}