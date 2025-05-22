package com.example.smarttracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
            "Smart Tracker"
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

                                    // Tracker ile ilgili SMS'leri filtrele
                                    if (isTrackerMessage(messageBody)) {
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

    private fun formatTrackerMessage(originalMessage: String): String {
        return when {
            originalMessage.contains("DELAY SMS", ignoreCase = true) -> {
                "Delayed alert: Your item was out of range. Last known location: ${extractLocation(originalMessage)}"
            }
            originalMessage.contains("uzaklasiyor", ignoreCase = true) ||
                    originalMessage.contains("uzaklaşıyor", ignoreCase = true) -> {
                "ALERT: Your item is moving away! ${extractGoogleMapsLink(originalMessage)}"
            }
            originalMessage.contains("ALERT", ignoreCase = true) -> {
                "Tracker Alert: ${originalMessage}"
            }
            else -> originalMessage
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