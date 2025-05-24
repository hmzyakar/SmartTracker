package com.example.smarttracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        var callback: ((String, String) -> Unit)? = null

        // Real tracker message keywords (no dummy data)
        private val TRACKER_KEYWORDS = listOf(
            "ESP32_TRACKER",
            "Smart Tracker",
            "TRACKER ALERT",
            "uzaklaşıyor",
            "uzaklasiyor",
            "moving away",
            "connection lost",
            "BLE disconnected",
            "WiFi lost",
            "GSM alert",
            "GPS location",
            "ALERT:",
            "RECONNECT",
            "Battery low"
        )

        // SMS command processing
        private val SMS_COMMANDS = mapOf(
            "ALARM ON" to "ALARM_ON",
            "ALARM OFF" to "ALARM_OFF",
            "SILENCE" to "SILENCE_ALARM",
            "STATUS" to "GET_STATUS",
            "LOCATION" to "GET_LOCATION",
            "GPS" to "GET_GPS_STATUS",
            "GSM" to "GET_GSM_STATUS",
            "PING" to "PING_TEST"
        )

        // Authorized phone numbers (replace with real numbers)
        private val AUTHORIZED_NUMBERS = listOf(
            "+905447661357",  // Main authorized number
            "5447661357"      // Without country code
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
                Log.d(TAG, "SMS received intent processing...")

                val bundle = intent.extras
                if (bundle != null) {
                    val pdus = bundle.get("pdus") as Array<*>?
                    val format = bundle.getString("format")

                    if (pdus != null) {
                        Log.d(TAG, "Processing ${pdus.size} SMS PDUs")

                        pdus.forEach { pdu ->
                            try {
                                val smsMessage = createSmsMessage(pdu as ByteArray, format)

                                if (smsMessage != null) {
                                    val messageBody = smsMessage.messageBody ?: ""
                                    val originatingAddress = smsMessage.originatingAddress ?: "Unknown"

                                    Log.d(TAG, "SMS from: $originatingAddress")
                                    Log.d(TAG, "SMS body: $messageBody")

                                    // Process authorized commands first
                                    if (isAuthorizedNumber(originatingAddress) && isCommand(messageBody)) {
                                        handleSMSCommand(context, originatingAddress, messageBody)
                                    }
                                    // Then check for tracker messages
                                    else if (isTrackerMessage(messageBody)) {
                                        Log.d(TAG, "Tracker SMS detected from tracker system")
                                        callback?.invoke("SMS Alert", formatTrackerMessage(messageBody))
                                    } else {
                                        Log.d(TAG, "Non-tracker SMS ignored")
                                    }
                                } else {
                                    Log.e(TAG, "Failed to create SMS message from PDU")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing SMS PDU: ${e.message}")
                            }
                        }
                    } else {
                        Log.w(TAG, "No PDUs found in SMS intent")
                    }
                } else {
                    Log.w(TAG, "No extras found in SMS intent")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS intent: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun createSmsMessage(pdu: ByteArray, format: String?): SmsMessage? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu, format)
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating SMS message: ${e.message}")
            null
        }
    }

    private fun isTrackerMessage(messageBody: String): Boolean {
        return TRACKER_KEYWORDS.any { keyword ->
            messageBody.contains(keyword, ignoreCase = true)
        }
    }

    private fun isCommand(messageBody: String): Boolean {
        val normalizedMessage = messageBody.trim().uppercase()
        return SMS_COMMANDS.keys.any { command ->
            normalizedMessage.startsWith(command)
        }
    }

    private fun isAuthorizedNumber(phoneNumber: String): Boolean {
        val cleanNumber = phoneNumber.replace("+90", "").replace("+", "")
        return AUTHORIZED_NUMBERS.any { authorizedNumber ->
            val cleanAuthorized = authorizedNumber.replace("+90", "").replace("+", "")
            cleanNumber.contains(cleanAuthorized) || cleanAuthorized.contains(cleanNumber)
        }
    }

    private fun handleSMSCommand(context: Context, phoneNumber: String, command: String) {
        Log.d(TAG, "Processing SMS command: $command from $phoneNumber")

        val normalizedCommand = command.trim().uppercase()
        var responseMessage = ""
        var esp32Command = ""

        // Find matching command
        SMS_COMMANDS.entries.find { normalizedCommand.startsWith(it.key) }?.let { entry ->
            esp32Command = entry.value

            responseMessage = when (entry.key) {
                "ALARM ON" -> {
                    callback?.invoke("SMS Command", "Alarm enabled via SMS from $phoneNumber")
                    "✅ ALARM ENABLED\nTracker will sound when item moves away."
                }
                "ALARM OFF" -> {
                    callback?.invoke("SMS Command", "Alarm disabled via SMS from $phoneNumber")
                    "🔕 ALARM DISABLED\nTracker will work silently."
                }
                "SILENCE" -> {
                    callback?.invoke("SMS Command", "Alarm silenced via SMS from $phoneNumber")
                    "🔇 ALARM SILENCED\nBuzzer stopped remotely."
                }
                "STATUS" -> {
                    callback?.invoke("SMS Command", "Status requested via SMS from $phoneNumber")
                    "📊 STATUS REQUEST SENT\nWaiting for tracker response..."
                }
                "LOCATION" -> {
                    callback?.invoke("SMS Command", "Location requested via SMS from $phoneNumber")
                    "📍 LOCATION REQUEST SENT\nWaiting for GPS coordinates..."
                }
                "GPS" -> {
                    callback?.invoke("SMS Command", "GPS status requested via SMS")
                    "🛰️ GPS STATUS REQUEST SENT"
                }
                "GSM" -> {
                    callback?.invoke("SMS Command", "GSM status requested via SMS")
                    "📱 GSM STATUS REQUEST SENT"
                }
                "PING" -> {
                    callback?.invoke("SMS Command", "Ping test via SMS")
                    "🏓 PING TEST SENT\nTesting tracker connection..."
                }
                else -> "❓ Command processed"
            }
        } ?: run {
            responseMessage = "❓ Unknown command.\nValid commands:\n• ALARM ON/OFF\n• SILENCE\n• STATUS\n• LOCATION\n• GPS\n• GSM\n• PING"
        }

        // Send command to ESP32 if valid
        if (esp32Command.isNotEmpty()) {
            sendESP32Command(esp32Command)
            Log.d(TAG, "ESP32 command sent: $esp32Command")
        }

        // Send response SMS
        sendResponseSMS(phoneNumber, responseMessage)
    }

    private fun sendESP32Command(command: String) {
        // Send command via UDP to ESP32
        Thread {
            try {
                val socket = DatagramSocket()
                val address = InetAddress.getByName("192.168.4.1") // ESP32 AP IP
                val buffer = command.toByteArray()
                val packet = DatagramPacket(buffer, buffer.size, address, 5000)

                socket.send(packet)
                socket.close()

                Log.d(TAG, "UDP command sent to ESP32: $command")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending UDP command to ESP32: ${e.message}")

                // Try alternative method if UDP fails
                try {
                    // Could implement BLE command sending here if needed
                    Log.d(TAG, "UDP failed, command may need to be sent via BLE")
                } catch (e2: Exception) {
                    Log.e(TAG, "All ESP32 communication methods failed: ${e2.message}")
                }
            }
        }.start()
    }

    private fun sendResponseSMS(phoneNumber: String, message: String) {
        try {
            val smsManager = SmsManager.getDefault()
            val fullMessage = "🔍 SmartTracker Response:\n$message"

            // Handle long messages by splitting
            val parts = smsManager.divideMessage(fullMessage)

            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, parts[0], null, null)
            } else {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }

            Log.d(TAG, "Response SMS sent to $phoneNumber: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending response SMS: ${e.message}")
        }
    }

    private fun formatTrackerMessage(originalMessage: String): String {
        return when {
            originalMessage.contains("TRACKER ALERT", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                val mapsLink = extractGoogleMapsLink(originalMessage)
                "🚨 CRITICAL ALERT: Your item is moving away!\n$location\n$mapsLink"
            }
            originalMessage.contains("uzaklaşıyor", ignoreCase = true) ||
                    originalMessage.contains("uzaklasiyor", ignoreCase = true) ||
                    originalMessage.contains("moving away", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                val mapsLink = extractGoogleMapsLink(originalMessage)
                "🚨 THEFT ALERT: Item is being moved!\n$location\n$mapsLink"
            }
            originalMessage.contains("connection lost", ignoreCase = true) ||
                    originalMessage.contains("BLE disconnected", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                "📡 CONNECTION LOST: Tracker disconnected!\n$location\nPossible theft or device moved out of range."
            }
            originalMessage.contains("WiFi lost", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                "📶 WIFI ALERT: WiFi connection lost!\n$location"
            }
            originalMessage.contains("Battery low", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                "🔋 BATTERY WARNING: Tracker battery low!\n$location\nPlease charge or replace battery soon."
            }
            originalMessage.contains("RECONNECT", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                "🔄 RECONNECTED: Tracker is back online\n$location"
            }
            originalMessage.contains("GPS location", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                val mapsLink = extractGoogleMapsLink(originalMessage)
                "📍 GPS UPDATE: Current location\n$location\n$mapsLink"
            }
            originalMessage.contains("GSM alert", ignoreCase = true) -> {
                "📱 GSM ALERT: ${originalMessage}"
            }
            else -> {
                "📱 Tracker Message: ${originalMessage}"
            }
        }
    }

    private fun extractLocation(message: String): String {
        // Extract coordinates in various formats
        // Format 1: LAT=41.008240;LON=28.978359
        val latLonRegex = Regex("""LAT=([\d.-]+);LON=([\d.-]+)""")
        val latLonMatch = latLonRegex.find(message)
        if (latLonMatch != null) {
            val lat = latLonMatch.groupValues[1]
            val lon = latLonMatch.groupValues[2]
            return "📍 Location: $lat, $lon"
        }

        // Format 2: Simple coordinate pair (41.008240,28.978359)
        val coordRegex = Regex("""([\d.-]+),([\d.-]+)""")
        val coordMatch = coordRegex.find(message)
        if (coordMatch != null) {
            val lat = coordMatch.groupValues[1]
            val lon = coordMatch.groupValues[2]
            return "📍 Location: $lat, $lon"
        }

        // Format 3: Location: text format
        val locationRegex = Regex("""Location:\s*([\d.-]+),\s*([\d.-]+)""")
        val locationMatch = locationRegex.find(message)
        if (locationMatch != null) {
            val lat = locationMatch.groupValues[1]
            val lon = locationMatch.groupValues[2]
            return "📍 Location: $lat, $lon"
        }

        return "📍 Location data not available"
    }

    private fun extractGoogleMapsLink(message: String): String {
        // Extract existing Google Maps link
        val linkRegex = Regex("""https://maps\.google\.com/\?q=[\d.,-]+""")
        val linkMatch = linkRegex.find(message)
        if (linkMatch != null) {
            return "🗺️ View: ${linkMatch.value}"
        }

        // Create Google Maps link from coordinates
        val coordRegex = Regex("""([\d.-]+),([\d.-]+)""")
        val coordMatch = coordRegex.find(message)
        if (coordMatch != null) {
            val lat = coordMatch.groupValues[1]
            val lon = coordMatch.groupValues[2]
            return "🗺️ View: https://maps.google.com/?q=$lat,$lon"
        }

        // Extract from LAT/LON format
        val latLonRegex = Regex("""LAT=([\d.-]+);LON=([\d.-]+)""")
        val latLonMatch = latLonRegex.find(message)
        if (latLonMatch != null) {
            val lat = latLonMatch.groupValues[1]
            val lon = latLonMatch.groupValues[2]
            return "🗺️ View: https://maps.google.com/?q=$lat,$lon"
        }

        return "🗺️ Map link not available"
    }

    private fun extractTimestamp(message: String): String {
        // Extract timestamp if present
        val timestampRegex = Regex("""\[(\d{2}:\d{2}:\d{2})\]""")
        val timestampMatch = timestampRegex.find(message)
        return if (timestampMatch != null) {
            "⏰ Time: ${timestampMatch.groupValues[1]}"
        } else {
            "⏰ Time: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
        }
    }

    private fun extractSignalStrength(message: String): String {
        // Extract RSSI or signal strength information
        val rssiRegex = Regex("""RSSI:\s*(-?\d+)""")
        val rssiMatch = rssiRegex.find(message)
        if (rssiMatch != null) {
            val rssi = rssiMatch.groupValues[1].toInt()
            val signalQuality = when {
                rssi > -50 -> "Excellent"
                rssi > -60 -> "Good"
                rssi > -70 -> "Fair"
                rssi > -80 -> "Weak"
                else -> "Very Weak"
            }
            return "📶 Signal: $rssi dBm ($signalQuality)"
        }
        return ""
    }

    private fun extractBatteryLevel(message: String): String {
        // Extract battery information if present
        val batteryRegex = Regex("""Battery:\s*(\d+)%""")
        val batteryMatch = batteryRegex.find(message)
        if (batteryMatch != null) {
            val battery = batteryMatch.groupValues[1].toInt()
            val batteryIcon = when {
                battery > 75 -> "🔋"
                battery > 50 -> "🔋"
                battery > 25 -> "🪫"
                else -> "🪫"
            }
            return "$batteryIcon Battery: $battery%"
        }
        return ""
    }

    // Enhanced message formatting with all extracted information
    private fun formatEnhancedTrackerMessage(originalMessage: String): String {
        val basicMessage = formatTrackerMessage(originalMessage)
        val timestamp = extractTimestamp(originalMessage)
        val signal = extractSignalStrength(originalMessage)
        val battery = extractBatteryLevel(originalMessage)

        val additionalInfo = listOf(timestamp, signal, battery)
            .filter { it.isNotEmpty() }
            .joinToString("\n")

        return if (additionalInfo.isNotEmpty()) {
            "$basicMessage\n\n$additionalInfo"
        } else {
            basicMessage
        }
    }
}