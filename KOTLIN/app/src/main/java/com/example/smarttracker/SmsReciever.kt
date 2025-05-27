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

        // Enhanced tracker message keywords
        private val TRACKER_KEYWORDS = listOf(
            "SMART TRACKER ALERT",
            "ESP32_TRACKER",
            "TRACKER ALERT",
            "Your item is moving away",
            "Device moved away",
            "connection lost",
            "BLE disconnected",
            "WiFi lost",
            "GPS location",
            "Battery low",
            "Signal lost",
            "Theft alert",
            "Movement detected",
            "Distance alert",
            "ALERT:",
            "Location:",
            "maps.google.com",
            "SmartTracker STATUS",
            "SmartTracker ALERT"
        )

        // Enhanced SMS commands
        private val SMS_COMMANDS = mapOf(
            "ALARM ON" to "ALARM_ON",
            "ALARM OFF" to "ALARM_OFF",
            "SILENCE" to "SILENCE_ALARM",
            "STATUS" to "GET_STATUS",
            "LOCATION" to "REQUEST_GPS",
            "GPS" to "REQUEST_GPS",
            "WHERE" to "REQUEST_GPS",
            "FIND" to "REQUEST_GPS",
            "GSM" to "GET_GSM_STATUS",
            "PING" to "PING_TEST",
            "START" to "START_TRACKING",
            "STOP" to "STOP_TRACKING",
            "HELP" to "HELP"
        )

        // Authorized phone numbers
        private val AUTHORIZED_NUMBERS = listOf(
            "+905447661357",  // Sizin numaranız (komut gönderme için)
            "5447661357",
            "905447661357",
            "+905063510837",  // ESP32'nin numarası (cevap alma için)
            "5063510837",
            "905063510837"
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
                Log.d(TAG, "SMS received - processing...")

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
                                    Log.d(TAG, "SMS content: $messageBody")

                                    // Process authorized commands first
                                    if (isAuthorizedNumber(originatingAddress) && isCommand(messageBody)) {
                                        handleSMSCommand(context, originatingAddress, messageBody)
                                    }
                                    // Then check for tracker messages
                                    else if (isTrackerMessage(messageBody)) {
                                        Log.d(TAG, "Tracker SMS detected from ESP32 system")
                                        callback?.invoke("Tracker SMS Alert", formatTrackerMessage(messageBody))
                                    } else {
                                        Log.d(TAG, "Regular SMS - not tracker related")
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
            normalizedMessage.startsWith(command) || normalizedMessage == command
        }
    }

    private fun isAuthorizedNumber(phoneNumber: String): Boolean {
        val cleanNumber = phoneNumber.replace("+90", "").replace("+", "").replace(" ", "").replace("-", "")
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
        SMS_COMMANDS.entries.find { normalizedCommand.startsWith(it.key) || normalizedCommand == it.key }?.let { entry ->
            esp32Command = entry.value

            responseMessage = when (entry.key) {
                "ALARM ON" -> {
                    callback?.invoke("SMS Command", "Alarm ENABLED via SMS from $phoneNumber")
                    "✅ ALARM ENABLED\nTracker will alert when item moves away."
                }
                "ALARM OFF" -> {
                    callback?.invoke("SMS Command", "Alarm DISABLED via SMS from $phoneNumber")
                    "🔕 ALARM DISABLED\nTracker monitoring only, no alerts."
                }
                "SILENCE" -> {
                    callback?.invoke("SMS Command", "Alarm SILENCED via SMS from $phoneNumber")
                    "🔇 ALARM SILENCED\nBuzzer stopped remotely for 5 minutes."
                }
                "STATUS" -> {
                    callback?.invoke("SMS Command", "System status requested via SMS from $phoneNumber")
                    "📊 STATUS REQUEST SENT\nChecking all systems..."
                }
                "LOCATION", "GPS", "WHERE", "FIND" -> {
                    callback?.invoke("SMS Command", "GPS location requested via SMS from $phoneNumber")
                    "📍 GPS LOCATION REQUEST SENT\nWaiting for coordinates..."
                }
                "GSM" -> {
                    callback?.invoke("SMS Command", "GSM status requested via SMS")
                    "📶 GSM STATUS REQUEST SENT\nChecking network connection..."
                }
                "START" -> {
                    callback?.invoke("SMS Command", "Tracking started via SMS")
                    "▶️ TRACKING STARTED\nDistance monitoring enabled."
                }
                "STOP" -> {
                    callback?.invoke("SMS Command", "Tracking stopped via SMS")
                    "⏹️ TRACKING STOPPED\nDistance monitoring disabled."
                }
                "PING" -> {
                    callback?.invoke("SMS Command", "System test via SMS")
                    "🔄 SYSTEM TEST INITIATED\nTesting all connections..."
                }
                "HELP" -> {
                    callback?.invoke("SMS Command", "Help requested via SMS")
                    """
                    📋 SMARTTRACKER COMMANDS:
                    
                    ALARM ON/OFF - Enable/disable alerts
                    SILENCE - Stop current alarm
                    LOCATION - Get GPS coordinates  
                    STATUS - System status
                    START/STOP - Begin/end tracking
                    GSM - Network status
                    PING - Test system
                    
                    Reply with any command.
                    """.trimIndent()
                }
                else -> "✅ Command processed"
            }
        } ?: run {
            responseMessage = """
                ❌ Unknown command: "$normalizedCommand"
                
                📋 Valid commands:
                • ALARM ON/OFF - Enable/disable alerts
                • SILENCE - Stop current alarm
                • LOCATION - Get GPS coordinates  
                • STATUS - System status
                • START/STOP - Begin/end tracking
                • GSM - Network status
                • PING - Test system
                • HELP - Show this help
                
                Smart Tracker v2.0
            """.trimIndent()
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
                socket.soTimeout = 8000

                val address = InetAddress.getByName("192.168.4.1") // ESP32 AP IP
                val buffer = command.toByteArray()
                val packet = DatagramPacket(buffer, buffer.size, address, 5000)

                socket.send(packet)

                // Wait for response
                val responseBuffer = ByteArray(512)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)

                try {
                    socket.receive(responsePacket)
                    val response = String(responsePacket.data, 0, responsePacket.length)
                    Log.d(TAG, "ESP32 response: $response")

                    // Handle GPS response specifically
                    if (command == "REQUEST_GPS" && response.startsWith("GPS_DATA;")) {
                        handleGPSResponse(response)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "UDP command sent to ESP32, no response received")
                }

                socket.close()
                Log.d(TAG, "UDP command successfully sent to ESP32: $command")

            } catch (e: Exception) {
                Log.e(TAG, "Error sending UDP command to ESP32: ${e.message}")
            }
        }.start()
    }

    private fun handleGPSResponse(gpsData: String) {
        // Parse GPS response and send formatted SMS
        val parts = gpsData.split(";")
        if (parts.size > 1) {
            if (parts[1] == "NO_FIX") {
                // Send "no GPS" SMS response
                Thread {
                    sendGPSResponseSMS("📍 GPS STATUS: No satellite fix available.\n\nThe tracker cannot determine location at this time. Please try again later or move to an area with better sky visibility.")
                }.start()
            } else {
                // Extract coordinates and send location SMS
                val locationMatch = Regex("""LAT=([\d.-]+);LON=([\d.-]+)""").find(gpsData)
                if (locationMatch != null) {
                    val lat = locationMatch.groupValues[1]
                    val lon = locationMatch.groupValues[2]

                    Thread {
                        val locationMessage = """
                            📍 TRACKER LOCATION:
                            
                            Coordinates: $lat, $lon
                            
                            🗺️ View on map:
                            https://maps.google.com/?q=$lat,$lon
                            
                            ⏰ ${java.text.SimpleDateFormat("HH:mm:ss dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())}
                        """.trimIndent()

                        sendGPSResponseSMS(locationMessage)
                    }.start()
                }
            }
        }
    }

    private fun sendGPSResponseSMS(message: String) {
        try {
            val smsManager = SmsManager.getDefault()
            val phoneNumber = "+905447661357" // Send to authorized number

            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, parts[0], null, null)
            } else {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }

            Log.d(TAG, "GPS response SMS sent to $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending GPS response SMS: ${e.message}")
        }
    }

    private fun sendResponseSMS(phoneNumber: String, message: String) {
        try {
            val smsManager = SmsManager.getDefault()
            val fullMessage = "🚨 Smart Tracker Response:\n\n$message\n\n⏰ ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"

            // Handle long messages by splitting
            val parts = smsManager.divideMessage(fullMessage)

            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, parts[0], null, null)
            } else {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }

            Log.d(TAG, "Response SMS sent to $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending response SMS: ${e.message}")
        }
    }

    private fun formatTrackerMessage(originalMessage: String): String {
        return when {
            originalMessage.contains("SMART TRACKER ALERT", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                val mapsLink = extractGoogleMapsLink(originalMessage)
                "🚨 CRITICAL ALERT: Your item is moving away!\n\n$location\n\n$mapsLink"
            }
            originalMessage.contains("Your item is moving away", ignoreCase = true) ||
                    originalMessage.contains("Device moved away", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                val mapsLink = extractGoogleMapsLink(originalMessage)
                "🔴 THEFT ALERT: Item is being moved!\n\n$location\n\n$mapsLink"
            }
            originalMessage.contains("connection lost", ignoreCase = true) ||
                    originalMessage.contains("BLE disconnected", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                "⚠️ CONNECTION LOST: Tracker disconnected!\n\n$location\n\nPossible theft or device moved out of range."
            }
            originalMessage.contains("WiFi lost", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                "📶 WIFI ALERT: WiFi connection lost!\n\n$location"
            }
            originalMessage.contains("Battery low", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                "🔋 BATTERY WARNING: Tracker battery low!\n\n$location\n\nPlease charge or replace battery soon."
            }
            originalMessage.contains("GPS location", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                val mapsLink = extractGoogleMapsLink(originalMessage)
                "📍 GPS UPDATE: Current location\n\n$location\n\n$mapsLink"
            }
            originalMessage.contains("Distance alert", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                val mapsLink = extractGoogleMapsLink(originalMessage)
                "📏 DISTANCE ALERT: Item moved beyond safe range!\n\n$location\n\n$mapsLink"
            }
            originalMessage.contains("SmartTracker STATUS", ignoreCase = true) -> {
                val location = extractLocation(originalMessage)
                val mapsLink = extractGoogleMapsLink(originalMessage)
                "📊 STATUS UPDATE: Your item is safe\n\n$location\n\n$mapsLink"
            }
            else -> {
                val location = extractLocation(originalMessage)
                val mapsLink = extractGoogleMapsLink(originalMessage)
                val enhancedMessage = "📱 Tracker Alert: $originalMessage"

                if (location.isNotEmpty() && location != "Location data not available") {
                    "$enhancedMessage\n\n$location\n\n$mapsLink"
                } else {
                    enhancedMessage
                }
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
            return "🗺️ View on map: ${linkMatch.value}"
        }

        // Create Google Maps link from coordinates
        val coordRegex = Regex("""([\d.-]+),([\d.-]+)""")
        val coordMatch = coordRegex.find(message)
        if (coordMatch != null) {
            val lat = coordMatch.groupValues[1]
            val lon = coordMatch.groupValues[2]
            return "🗺️ View on map: https://maps.google.com/?q=$lat,$lon"
        }

        // Extract from LAT/LON format
        val latLonRegex = Regex("""LAT=([\d.-]+);LON=([\d.-]+)""")
        val latLonMatch = latLonRegex.find(message)
        if (latLonMatch != null) {
            val lat = latLonMatch.groupValues[1]
            val lon = latLonMatch.groupValues[2]
            return "🗺️ View on map: https://maps.google.com/?q=$lat,$lon"
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