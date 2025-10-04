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
import android.util.Patterns
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ClipboardMonitorService : Service() {

    private lateinit var clipboard: ClipboardManager
    private val CHANNEL_ID = "clipboard_scan_channel"
    private val FOREGROUND_ID = 2001

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = clipboard.primaryClip
        val itemRaw = clip?.getItemAt(0)?.text?.toString() ?: return@OnPrimaryClipChangedListener

        val item = itemRaw.trim()
        Log.d("ClipboardService", "📋 Copied text: $item")

        if (!PreferencesManager.isClipboardScannerEnabled(this)) {
            Log.d("ClipboardService", "Clipboard Scanner disabled in settings.")
            return@OnPrimaryClipChangedListener
        }

        val normalized = normalizeUrlCandidate(item)
        if (normalized == null) {
            Log.d("ClipboardService", "Not a URL (after normalization): $item")
            return@OnPrimaryClipChangedListener
        }

        Log.d("ClipboardService", "🔗 Normalized URL for scanning: $normalized")
        startScan(normalized)
    }

    override fun onCreate() {
        super.onCreate()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(listener)

        createNotificationChannel()
        // Service will always stay in foreground with base notification
        startForeground(FOREGROUND_ID, buildForegroundNotification("Clipboard scanner active"))
        Log.d("ClipboardService", "Service created and clipboard listener registered.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep service running persistently
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            clipboard.removePrimaryClipChangedListener(listener)
        } catch (_: Exception) { }
        Log.d("ClipboardService", "Service destroyed and listener removed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Normalize links (handles hxxp, bare domains, defanged forms)
    private fun normalizeUrlCandidate(input: String): String? {
        var s = input.trim()
        if (s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length - 1).trim()
        if (s.startsWith("hxxps://", true)) s = s.replaceFirst(Regex("(?i)hxxps://"), "https://")
        if (s.startsWith("hxxp://", true)) s = s.replaceFirst(Regex("(?i)hxxp://"), "http://")

        if (s.startsWith("http://", true) || s.startsWith("https://", true)) return s
        if (Patterns.WEB_URL.matcher(s).matches()) return if (s.startsWith("//")) "http:$s" else "http://$s"

        val undeff = s.replace("[.]", ".").replace("(.)", ".")
        if (Patterns.WEB_URL.matcher(undeff).matches()) return if (undeff.startsWith("http")) undeff else "http://$undeff"

        return null
    }

    private fun startScan(url: String) {
        Thread {
            scanUrl(url)
        }.start()
    }

    private fun scanUrl(url: String) {
        Log.d("ClipboardService", "➡️ Submitting URL to VirusTotal: $url")

        val apiKey = "YOUR_API_KEY" // ⚠️ replace with valid VT key
        val apiUrl = "https://www.virustotal.com/api/v3/urls"

        try {
            // Submit URL
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("x-apikey", apiKey)
            conn.doOutput = true
            val data = "url=${URLEncoder.encode(url, "UTF-8")}"
            DataOutputStream(conn.outputStream).use { it.writeBytes(data) }

            val postResp = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val analysisId = JSONObject(postResp).getJSONObject("data").getString("id")

            // Poll results
            val analysisUrl = "https://www.virustotal.com/api/v3/analyses/$analysisId"
            var malicious = 0
            var phishing = 0
            var finished = false

            repeat(20) {
                Thread.sleep(2000)
                val conn2 = URL(analysisUrl).openConnection() as HttpURLConnection
                conn2.requestMethod = "GET"
                conn2.setRequestProperty("x-apikey", apiKey)
                val response2 = BufferedReader(InputStreamReader(conn2.inputStream)).use { it.readText() }

                val json = JSONObject(response2)
                val status = json.getJSONObject("data").getJSONObject("attributes").getString("status")
                if (status == "completed") {
                    val stats = json.getJSONObject("data").getJSONObject("attributes").getJSONObject("stats")
                    malicious = stats.optInt("malicious", 0)
                    phishing = stats.optInt("phishing", 0)
                    finished = true
                    return@repeat
                }
            }

            // ✅ Only act on malicious results
            if (finished && (malicious > 0 || phishing > 0)) {
                Log.w("ClipboardService", "⚠️ Malicious Clipboard link detected: $url")
                ThreatLogActivity.saveLog(this, url, "Clipboard")

                if (PreferencesManager.areAlertsEnabled(this)) {
                    when (PreferencesManager.getAlertSound(this)) {
                        "emergency" -> playEmergencySound()
                        "silent" -> {}
                        else -> playDefaultSound()
                    }
                }

                showNotification(
                    "⚠️ Malicious Clipboard link detected!",
                    "Detected by $malicious engines ($phishing phishing)",
                    url
                )
            } else {
                Log.i("ClipboardService", "Ignored safe/unknown link: $url")
            }

        } catch (e: Exception) {
            Log.e("ClipboardService", "Error scanning Clipboard URL", e)
            showNotification("❌ Scan Failed", "Error: ${e.message}", url)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "Clipboard Scanner Alerts", NotificationManager.IMPORTANCE_HIGH
            )
            channel.enableLights(true)
            channel.lightColor = Color.RED
            channel.enableVibration(true)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, message: String, url: String) {
        createNotificationChannel()
        val manager = getSystemService(NotificationManager::class.java)

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
            .setContentText(message.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_firewall)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_search, "Open Scanner", pendingIntent)
            .build()

        manager?.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun buildForegroundNotification(message: String): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clipboard Scanner")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_firewall)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
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
}