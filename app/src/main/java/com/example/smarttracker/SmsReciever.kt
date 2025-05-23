package com.example.smarttracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        var callback: ((String, String) -> Unit)? = null

        // Tracker mesajlarını tanımlayan anahtar kelimeler
        private val TRACKER_KEYWORDS = listOf(
            "DELAY SMS",
            "Esyaniz uzaklasiyor",
            "Eşyanız uzaklaşıyor",
            "ALERT",
            "ESP32_TRACKER",
            "Smart Tracker",
            "RECONNECT",
            "WIFI_ALERT"
        )

        // SMS komutları
        private val SMS_COMMANDS = listOf(
            "ALARM ON",
            "ALARM OFF",
            "SILENCE",
            "STATUS",
            "LOCATION"
        )

        // Yetkili numaralar (gerçek uygulamada ayarlardan alınmalı)
        private val AUTHORIZED_NUMBERS = listOf(
            "+90XXXXXXXXXX", // Buraya kendi numaranızı yazın
            "XXXXXXXXXX"     // Sadece numara kısmı da kabul edilir
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
                Log.d(TAG, "SMS received intent")

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

                                    // Önce komut kontrolü yap
                                    if (isAuthorizedNumber(originatingAddress) && isCommand(messageBody)) {
                                        handleSMSCommand(context, originatingAddress, messageBody)
                                    }
                                    // Sonra tracker mesajı kontrolü
                                    else if (isTrackerMessage(messageBody)) {
                                        Log.d(TAG, "Tracker SMS detected")
                                        callback?.invoke("SMS Alert", formatTrackerMessage(messageBody))
                                    } else {
                                        Log.d(TAG, "Non-tracker SMS ignored")
                                    }
                                } else {
                                    Log.e(TAG, "Failed to create SMS message from PDU")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing individual SMS PDU: ${e.message}")
                            }
                        }
                    } else {
                        Log.w(TAG, "No PDUs found in SMS intent")
                    }
                } else {
                    Log.w(TAG, "No extras found in SMS intent")
                }
            } else {
                Log.d(TAG, "Received non-SMS intent: ${intent.action}")
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
        return SMS_COMMANDS.any { command ->
            messageBody.trim().uppercase().startsWith(command)
        }
    }

    private fun isAuthorizedNumber(phoneNumber: String): Boolean {
        return AUTHORIZED_NUMBERS.any { authorizedNumber ->
            phoneNumber.contains(authorizedNumber.replace("+90", "").replace("+", ""))
        }
    }

    private fun handleSMSCommand(context: Context, phoneNumber: String, command: String) {
        Log.d(TAG, "Processing SMS command: $command from $phoneNumber")

        val normalizedCommand = command.trim().uppercase()
        var responseMessage = ""

        when {
            normalizedCommand.startsWith("ALARM ON") -> {
                // ESP32'ye alarm açma komutu gönder (UDP veya BLE üzerinden)
                sendESP32Command("ALARM_ON")
                responseMessage = "✅ Alarm ENABLED\nTracker will sound when item moves away."

                // App callback'ine bildir
                callback?.invoke("SMS Command", "Alarm enabled via SMS")
            }

            normalizedCommand.startsWith("ALARM OFF") -> {
                sendESP32Command("ALARM_OFF")
                responseMessage = "🔕 Alarm DISABLED\nTracker will work silently."

                callback?.invoke("SMS Command", "Alarm disabled via SMS")
            }

            normalizedCommand.startsWith("SILENCE") -> {
                sendESP32Command("SILENCE_ALARM")
                responseMessage = "🔇 Alarm SILENCED\nBuzzer stopped remotely."

                callback?.invoke("SMS Command", "Alarm silenced via SMS")
            }

            normalizedCommand.startsWith("STATUS") -> {
                // ESP32'den durum bilgisi iste
                sendESP32Command("GET_STATUS")
                responseMessage = "📊 STATUS REQUEST SENT\nWaiting for tracker response..."

                callback?.invoke("SMS Command", "Status requested via SMS")
            }

            normalizedCommand.startsWith("LOCATION") -> {
                sendESP32Command("GET_LOCATION")
                responseMessage = "📍 LOCATION REQUEST SENT\nWaiting for GPS coordinates..."

                callback?.invoke("SMS Command", "Location requested via SMS")
            }

            else -> {
                responseMessage = "❓ Unknown command.\nValid commands:\n• ALARM ON\n• ALARM OFF\n• SILENCE\n• STATUS\n• LOCATION"
            }
        }

        // Yanıt SMS'i gönder
        sendResponseSMS(phoneNumber, responseMessage)
    }

    private fun sendESP32Command(command: String) {
        // ESP32'ye komut gönderme (UDP üzerinden)
        Thread {
            try {
                val socket = java.net.DatagramSocket()
                val address = java.net.InetAddress.getByName("192.168.4.1") // ESP32 AP IP
                val buffer = command.toByteArray()
                val packet = java.net.DatagramPacket(buffer, buffer.size, address, 5000)

                socket.send(packet)
                socket.close()

                Log.d(TAG, "Command sent to ESP32 via UDP: $command")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending command to ESP32: ${e.message}")
            }
        }.start()
    }

    private fun sendResponseSMS(phoneNumber: String, message: String) {
        try {
            val smsManager = SmsManager.getDefault()

            // Uzun mesajları parçalara böl
            val parts = smsManager.divideMessage("🔍 SmartTracker Response:\n$message")

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
            originalMessage.contains("DELAY SMS", ignoreCase = true) -> {
                "⏰ Delayed alert: Your item was out of range. Last known location: ${extractLocation(originalMessage)}"
            }
            originalMessage.contains("uzaklasiyor", ignoreCase = true) ||
                    originalMessage.contains("uzaklaşıyor", ignoreCase = true) -> {
                "🚨 CRITICAL: Your item is moving away! ${extractGoogleMapsLink(originalMessage)}"
            }
            originalMessage.contains("RECONNECT", ignoreCase = true) -> {
                "🔄 Tracker reconnected: Device is back online. ${extractLocation(originalMessage)}"
            }
            originalMessage.contains("WIFI_ALERT", ignoreCase = true) -> {
                "📶 WiFi Alert: Connection lost to tracker. ${extractLocation(originalMessage)}"
            }
            originalMessage.contains("ALERT", ignoreCase = true) -> {
                "⚠️ Tracker Alert: ${originalMessage}"
            }
            else -> "📱 Tracker Message: ${originalMessage}"
        }
    }

    private fun extractLocation(message: String): String {
        // Koordinat formatını ara (örn: 41.008240,28.978359)
        val locationRegex = Regex("""(\d+\.\d+),(\d+\.\d+)""")
        val match = locationRegex.find(message)

        return if (match != null) {
            val lat = match.groupValues[1]
            val lon = match.groupValues[2]
            "Lat: $lat, Lon: $lon"
        } else {
            "Location data not available"
        }
    }

    private fun extractGoogleMapsLink(message: String): String {
        // Google Maps linkini ara
        val linkRegex = Regex("""https://maps\.google\.com/\?q=[\d.,]+""")
        val match = linkRegex.find(message)

        return if (match != null) {
            "View location: ${match.value}"
        } else {
            extractLocation(message)
        }
    }
}