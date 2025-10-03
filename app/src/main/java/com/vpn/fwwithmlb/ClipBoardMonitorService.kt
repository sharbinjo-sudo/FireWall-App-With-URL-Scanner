package com.vpn.fwwithmlb

import android.app.*
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ClipboardMonitorService : Service() {

    private lateinit var clipboard: ClipboardManager
    private val CHANNEL_ID = "clipboard_scan_channel"

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = clipboard.primaryClip
        val item = clip?.getItemAt(0)?.text?.toString() ?: return@OnPrimaryClipChangedListener

        Log.d("ClipboardService", "📋 Copied text: $item")

        if (!PreferencesManager.isClipboardScannerEnabled(this)) {
            Log.d("ClipboardService", "Clipboard Scanner disabled.")
            return@OnPrimaryClipChangedListener
        }

        val normalized = normalizeUrl(item)
        if (normalized != null) {
            Log.d("ClipboardService", "🔗 Detected URL in clipboard: $normalized")
            startScan(normalized)
        } else {
            Log.d("ClipboardService", "❌ Not a valid URL: $item")
        }
    }

    override fun onCreate() {
        super.onCreate()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(listener)

        // Keep service alive with persistent notification
        startForeground(1, buildForegroundNotification("Monitoring clipboard for links..."))
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboard.removePrimaryClipChangedListener(listener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ✅ Normalize URLs (handle hxxp, www, etc.)
    private fun normalizeUrl(input: String): String? {
        var url = input.trim()

        if (url.startsWith("hxxp://", true)) {
            url = url.replaceFirst("hxxp://", "http://", true)
        } else if (url.startsWith("hxxps://", true)) {
            url = url.replaceFirst("hxxps://", "https://", true)
        }

        if (!url.startsWith("http://", true) &&
            !url.startsWith("https://", true) &&
            !url.startsWith("ftp://", true)
        ) {
            // If it looks like a domain, prepend http://
            if (url.contains(".") && !url.contains(" ")) {
                url = "http://$url"
            } else {
                return null
            }
        }
        return url
    }

    private fun startScan(url: String) {
        // Temporary scanning notification (foreground already running)
        val scanNotification = buildForegroundNotification("Scanning copied link...")
        startForeground(2, scanNotification)

        Thread {
            try {
                scanUrl(url)
            } finally {
                // Return to idle monitoring notification
                startForeground(1, buildForegroundNotification("Monitoring clipboard for links..."))
            }
        }.start()
    }

    private fun scanUrl(url: String) {
        try {
            val apiKey = "79cb539731c911688aabc98159b95561dd38619d73178137885739903e828d34" // replace with valid
            val apiUrl = "https://www.virustotal.com/api/v3/urls"

            // Step 1: Submit URL
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("x-apikey", apiKey)
            conn.doOutput = true
            val data = "url=${URLEncoder.encode(url, "UTF-8")}"
            DataOutputStream(conn.outputStream).use { it.writeBytes(data) }

            val postResp = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val analysisId = JSONObject(postResp).getJSONObject("data").getString("id")

            // Step 2: Poll results
            val analysisUrl = "https://www.virustotal.com/api/v3/analyses/$analysisId"
            var malicious = 0
            var phishing = 0
            var harmless = 0
            var finished = false

            repeat(12) {
                Thread.sleep(2000)
                val conn2 = URL(analysisUrl).openConnection() as HttpURLConnection
                conn2.requestMethod = "GET"
                conn2.setRequestProperty("x-apikey", apiKey)

                val response2 = BufferedReader(InputStreamReader(conn2.inputStream)).use { it.readText() }
                val json = JSONObject(response2)

                val status = json.getJSONObject("data").getJSONObject("attributes").getString("status")
                if (status == "completed") {
                    val stats = json.getJSONObject("data")
                        .getJSONObject("attributes")
                        .getJSONObject("stats")

                    malicious = stats.optInt("malicious", 0)
                    phishing = stats.optInt("phishing", 0)
                    harmless = stats.optInt("harmless", 0)
                    finished = true
                    return@repeat
                }
            }

            // Step 3: Save + Notify
            if (finished && (malicious > 0 || phishing > 0)) {
                Log.w("ClipboardService", "⚠️ Malicious Clipboard link: $url")
                ThreatLogActivity.saveLog(this, url, "Clipboard")

                if (PreferencesManager.areAlertsEnabled(this)) {
                    when (PreferencesManager.getAlertSound(this)) {
                        "emergency" -> playEmergencySound()
                        "silent" -> {}
                        else -> playDefaultSound()
                    }
                    showNotification("⚠️ Malicious Clipboard link detected!", url, url)
                }

            } else if (finished && harmless > 0) {
                Log.i("ClipboardService", "✅ Safe Clipboard link: $url")
                showNotification("✅ Safe Clipboard link", url, url)

            } else {
                Log.d("ClipboardService", "ℹ️ Unknown Clipboard link: $url")
                showNotification("ℹ️ Unknown Clipboard link", url, url)
            }

        } catch (e: Exception) {
            Log.e("ClipboardService", "Error scanning Clipboard URL", e)
            showNotification("❌ Scan Failed", "Error: ${e.message}", "")
        }
    }

    private fun playDefaultSound() {
        try {
            val mp = MediaPlayer.create(this, R.raw.alert)
            mp?.setOnCompletionListener { it.release() }
            mp?.start()
        } catch (e: Exception) {
            Log.e("ClipboardService", "Failed to play default sound", e)
        }
    }

    private fun playEmergencySound() {
        try {
            val resId = try { R.raw.emergency } catch (_: Exception) { R.raw.alert }
            val mp = MediaPlayer.create(this, resId)
            mp?.setOnCompletionListener { it.release() }
            mp?.start()
        } catch (e: Exception) {
            Log.e("ClipboardService", "Failed to play emergency sound", e)
        }
    }

    private fun showNotification(title: String, message: String, url: String) {
        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Clipboard Scanner Alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
            }
            manager?.createNotificationChannel(channel)
        }

        val scanIntent = Intent(this, LinkScannerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (url.isNotEmpty()) {
                putExtra(Intent.EXTRA_TEXT, url)
                data = Uri.parse(url)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, scanIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message.take(60))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_firewall)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(Color.RED)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_search, "Scan in App", pendingIntent)
            .build()

        manager?.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun buildForegroundNotification(message: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "foreground_clipboard_channel",
                "Foreground Clipboard Scanning",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, "foreground_clipboard_channel")
            .setContentTitle("Clipboard Scanner")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_firewall)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}