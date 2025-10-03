package com.vpn.fwwithmlb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.regex.Pattern

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) return

        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)

        for (msg in msgs) {
            val body = msg.messageBody ?: continue

            // ✅ Check preference before scanning
            if (!PreferencesManager.isSmsScannerEnabled(context)) {
                Log.d("SmsReceiver", "📩 SMS Scanner disabled, ignoring message.")
                return
            }

            // Regex → catch http/https + hxxp
            val urlPattern = Pattern.compile("(https?://[\\w-]+(\\.[\\w-]+)+(/\\S*)?|hxxp://\\S+)")
            val matcher = urlPattern.matcher(body)

            while (matcher.find()) {
                var url = matcher.group() ?: continue

                // Normalize obfuscation (hxxp → http)
                url = url.replace("hxxp", "http")

                Log.d("SmsReceiver", "🔗 Found SMS URL: $url")

                // Start scanning service (foreground-safe)
                val serviceIntent = Intent(context, SmsScanService::class.java).apply {
                    putExtra("url", url)
                    putExtra("sender", msg.displayOriginatingAddress)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}