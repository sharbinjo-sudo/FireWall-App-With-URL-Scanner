package com.vpn.fwwithmlb

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.nio.ByteBuffer

class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null

    private val CHANNEL_ID = "firewall_channel"
    private val NOTIFICATION_ID = 1
    private val TAG = "MyVpnService"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "DISCONNECT" -> {
                stopVpn()
                return START_NOT_STICKY
            }
            "RELOAD_RULES" -> {
                Log.d(TAG, "♻️ Reloading rules...")
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
            Log.d(TAG, "VPN already running — skip start.")
            return
        }

        // Start foreground service immediately to avoid timeout
        startForeground(NOTIFICATION_ID, buildNotification("Starting Firewall VPN…"))

        Thread {
            try {
                val prefs = getSharedPreferences("firewall_prefs", MODE_PRIVATE)
                val blockedPackages = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()
                val pm = packageManager

                val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val launchablePkgs = pm.queryIntentActivities(launchIntent, 0)
                    .mapNotNull { it.activityInfo?.applicationInfo?.packageName }
                    .toSet()

                val allowedPkgs = launchablePkgs.filterNot { blockedPackages.contains(it) }

                val builder = Builder()
                    .setSession("FW with MLB Firewall")
                    .addAddress("10.0.0.2", 32)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)

                for (pkg in allowedPkgs) {
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to allow $pkg: ${e.message}")
                    }
                }

                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    Log.e(TAG, "❌ Failed to establish VPN interface.")
                    stopForeground(true)
                    stopSelf()
                    return@Thread
                }

                try {
                    val blockedUids = blockedPackages.mapNotNull {
                        try { pm.getApplicationInfo(it, 0).uid } catch (_: Exception) { null }
                    }
                    if (blockedUids.isNotEmpty()) {
                        NativeBridge.setBlockedUidsNative(blockedUids.toIntArray())
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Error sending UIDs to native: ${e.message}")
                }

                vpnInterface?.fd?.let {
                    try {
                        NativeBridge.startNative(it)
                        Log.i(TAG, "NativeBridge started with fd=$it")
                    } catch (e: Throwable) {
                        Log.w(TAG, "NativeBridge.startNative failed: ${e.message}")
                    }
                }

                vpnThread = Thread {
                    try {
                        val inFd = vpnInterface?.fileDescriptor ?: return@Thread
                        val input = FileInputStream(inFd)
                        val buffer = ByteArray(32767)
                        while (!Thread.interrupted()) {
                            val r = input.read(buffer)
                            if (r > 0) {
                                val version = (buffer[0].toInt() and 0xF0) shr 4
                                if (version == 4) Log.v(TAG, "Captured IPv4 packet length=$r")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "VPN read thread ended: ${e.message}")
                    }
                }.also { it.start() }

                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification("Firewall VPN Active"))

                sendBroadcast(Intent("VPN_STARTED"))
                Log.i(TAG, "✅ VPN started successfully (${allowedPkgs.size} allowed apps).")

            } catch (e: Exception) {
                Log.e(TAG, "Error starting VPN: ${e.message}", e)
                stopForeground(true)
                stopSelf()
            }
        }.start()
    }

    private fun stopVpn() {
        Log.i(TAG, "🛑 Stopping VPN...")
        try {
            vpnThread?.interrupt()
            vpnThread = null

            try {
                NativeBridge.stopNative()
            } catch (e: Throwable) {
                Log.w(TAG, "NativeBridge.stopNative failed: ${e.message}")
            }

            vpnInterface?.close()
            vpnInterface = null
            stopForeground(true)
            sendBroadcast(Intent("VPN_STOPPED"))
            Log.i(TAG, "✅ VPN stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN: ${e.message}", e)
        }
    }

    private fun buildNotification(text: String): Notification {
        val disconnectIntent = Intent(this, MyVpnService::class.java).apply { action = "DISCONNECT" }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 0, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                    )
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                    )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Firewall VPN Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Firewall VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_firewall)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
