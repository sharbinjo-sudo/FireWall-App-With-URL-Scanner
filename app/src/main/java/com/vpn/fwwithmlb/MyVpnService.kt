package com.vpn.fwwithmlb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Android VPN-based Firewall Service
 * Blocks network for apps selected in MainActivity using Android VPNService API.
 */
class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private val CHANNEL_ID = "firewall_channel"
    private val NOTIFICATION_ID = 1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "DISCONNECT" -> {
                stopVpn()
                return START_NOT_STICKY
            }
            "RELOAD_RULES" -> {
                Log.d("MyVpnService", "Reloading firewall rules...")
                stopVpn()
                startVpn()
                return START_STICKY
            }
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (vpnInterface != null) {
            Log.d("MyVpnService", "VPN already active — skipping restart.")
            return
        }

        try {
            val builder = Builder()
                .setSession("FW with MLB Firewall")
                .addAddress("10.0.0.2", 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            // Load blocked apps from SharedPreferences
            val prefs = getSharedPreferences("firewall_prefs", MODE_PRIVATE)
            val blockedApps = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()

            val pm = packageManager
            val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            // Fetch all launchable apps
            val allApps = pm.queryIntentActivities(launchIntent, 0)
                .mapNotNull { it.activityInfo?.applicationInfo?.packageName }
                .toSet()

            // Allowed = All apps MINUS blocked
            val allowedApps = allApps.filterNot { blockedApps.contains(it) }

            Log.d("MyVpnService", "Blocked apps: $blockedApps")
            Log.d("MyVpnService", "Allowed apps: $allowedApps")

            // Only allowed apps get network access
            for (pkg in allowedApps) {
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: Exception) {
                    Log.w("MyVpnService", "Cannot add allowed app: $pkg")
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e("MyVpnService", "❌ Failed to establish VPN interface.")
                stopSelf()
                return
            }

            // Start background packet monitoring (dropping all VPN traffic)
            vpnThread = Thread {
                Log.d("MyVpnService", "Firewall active. Monitoring packets...")
                try {
                    val fd = vpnInterface?.fileDescriptor ?: return@Thread
                    val input = FileInputStream(fd)
                    val output = FileOutputStream(fd)
                    val buffer = ByteArray(32767)

                    while (!Thread.interrupted()) {
                        val length = input.read(buffer)
                        if (length > 0) {
                            val packet = ByteBuffer.wrap(buffer, 0, length)
                            Log.v("MyVpnService", "Dropped packet of size=$length, firstByte=${packet.get(0)}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MyVpnService", "Packet loop error", e)
                }
            }
            vpnThread?.start()

            // Start Foreground Notification
            startForeground(NOTIFICATION_ID, buildNotification())

            sendBroadcast(Intent("VPN_STARTED"))
            Log.i("MyVpnService", "✅ VPN Firewall started successfully.")
        } catch (e: Exception) {
            Log.e("MyVpnService", "Error starting VPN", e)
            stopSelf()
        }
    }

    private fun stopVpn() {
        Log.d("MyVpnService", "Stopping VPN Firewall...")
        try {
            vpnThread?.interrupt()
            vpnThread = null
            vpnInterface?.close()
            vpnInterface = null
            stopForeground(true)
            stopSelf()
            sendBroadcast(Intent("VPN_STOPPED"))
        } catch (e: Exception) {
            Log.e("MyVpnService", "Error stopping VPN", e)
        }
    }

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
            val channel = NotificationChannel(
                CHANNEL_ID, "Firewall Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FW with MLB Firewall")
            .setContentText("VPN Firewall is active")
            .setSmallIcon(R.drawable.ic_firewall)
            .setContentIntent(activityPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}