package com.vpn.fwwithmlb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream

class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val CHANNEL_ID = "firewall_channel"
    private val NOTIFICATION_ID = 1
    private var vpnThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "DISCONNECT" -> {
                disconnectVpn()
                return START_NOT_STICKY
            }
            "START_ALWAYS_ON" -> {
                if (PreferencesManager.isVpnAlwaysOn(this)) {
                    startVpnTunnel()
                }
            }
            "STOP_ALWAYS_ON" -> {
                stopVpnTunnel()
            }
            else -> {
                startVpnTunnel()
            }
        }
        return START_STICKY
    }

    /**
     * Build and establish the VPN tunnel
     */
    private fun startVpnTunnel() {
        if (vpnInterface != null) {
            Log.d("MyVpnService", "VPN already running, skipping start")
            return
        }

        Log.d("MyVpnService", "Starting VPN tunnel...")

        val builder = Builder()
            .setSession("FW with MLB Firewall")
            .addAddress("10.0.0.2", 32) // dummy private IP
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")

        // 🔹 Load preferences
        val prefs = getSharedPreferences("firewall_prefs", MODE_PRIVATE)
        val blockedApps = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()

        // ✅ Block only selected apps
        packageManager.getInstalledApplications(0).forEach { app ->
            try {
                if (blockedApps.contains(app.packageName)) {
                    builder.addDisallowedApplication(app.packageName)
                }
            } catch (e: Exception) {
                Log.e("MyVpnService", "Failed to disallow ${app.packageName}", e)
            }
        }

        vpnInterface = builder.establish()

        if (vpnInterface != null) {
            startPacketLoop(blockedApps)
            sendBroadcast(Intent("VPN_STARTED"))
            startForeground(NOTIFICATION_ID, buildNotification())
        } else {
            Log.e("MyVpnService", "Failed to establish VPN")
            stopSelf()
        }
    }

    /**
     * Stop VPN gracefully
     */
    private fun stopVpnTunnel() {
        Log.d("MyVpnService", "Stopping VPN tunnel...")
        vpnThread?.interrupt()
        vpnThread = null
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(true)
        stopSelf()
        sendBroadcast(Intent("VPN_STOPPED"))
    }

    /**
     * Simple packet loop
     * (Currently drops everything, replace with proper filtering if needed)
     */
    private fun startPacketLoop(blockedApps: Set<String>) {
        vpnThread = Thread {
            try {
                val fd = vpnInterface?.fileDescriptor ?: return@Thread
                val input = FileInputStream(fd)
                val output = FileOutputStream(fd)
                val buffer = ByteArray(32767)

                while (!Thread.interrupted()) {
                    val length = input.read(buffer)
                    if (length > 0) {
                        // 🚫 Drop packets instead of forwarding
                        Log.d("MyVpnService", "Dropped packet size=$length for blockedApps=$blockedApps")
                        continue
                    }
                }
            } catch (e: Exception) {
                Log.e("MyVpnService", "Packet loop error", e)
            }
        }
        vpnThread?.start()
    }

    /**
     * Build VPN foreground notification
     */
    private fun buildNotification(): Notification {
        val disconnectIntent = Intent(this, MyVpnService::class.java).apply { action = "DISCONNECT" }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 0, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
        )

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val activityPendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Firewall Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FW with MLB")
            .setContentText("Firewall is running")
            .setSmallIcon(R.drawable.ic_firewall)
            .setLargeIcon(getAppIconBitmap())
            .setContentIntent(activityPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun getAppIconBitmap(): Bitmap? {
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun disconnectVpn() {
        Log.d("MyVpnService", "Firewall Disconnect requested")
        stopVpnTunnel()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpnTunnel()
    }
}