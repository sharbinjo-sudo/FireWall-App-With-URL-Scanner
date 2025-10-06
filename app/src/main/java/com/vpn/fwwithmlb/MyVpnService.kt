package com.vpn.fwwithmlb

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    private val CHANNEL_ID = "firewall_channel"
    private val NOTIFICATION_ID = 1
    private val TAG = "MyVpnService"

    companion object {
        @Volatile
        var isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        try {
            when (action) {
                "DISCONNECT" -> {
                    // immediate UI-visible state change
                    setRunningPref(false)
                    sendBroadcastWithPackage(Intent("VPN_DISCONNECTING"))
                    sendDisconnectingNotification()
                    stopVpn()
                    return START_NOT_STICKY
                }

                "RELOAD_RULES" -> {
                    serviceScope.launch { reloadRules() }
                    return START_NOT_STICKY
                }

                "VPN_STATUS_QUERY" -> {
                    val status = if (running.get()) "VPN_STARTED" else "VPN_STOPPED"
                    sendBroadcastWithPackage(Intent(status))
                    return START_NOT_STICKY
                }

                else -> {
                    if (running.get()) return START_STICKY
                    running.set(true)
                    isRunning = true
                    // immediate notification so system doesn't ANR
                    startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
                    // Do heavy work off main thread
                    serviceScope.launch { safeStartVpn() }
                    return START_STICKY
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand error: ${e.message}", e)
            stopSelfAndReset()
            return START_NOT_STICKY
        }
    }

    private suspend fun safeStartVpn() = withContext(Dispatchers.IO) {
        try {
            startVpnInternal()
        } catch (e: Exception) {
            Log.e(TAG, "safeStartVpn failed: ${e.message}", e)
            stopSelfAndReset()
        }
    }

    private suspend fun startVpnInternal() = withContext(Dispatchers.IO) {
        val prefs = getSharedPreferences("firewall_prefs", MODE_PRIVATE)
        val blockedPackages = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()
        val pm = packageManager

        val builder = Builder()
            .setSession("FW with MLB Firewall")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)

        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        if (blockedPackages.isNotEmpty() && blockedPackages.size < installed.size / 2) {
            for (pkg in blockedPackages) try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
        } else {
            for (app in installed) try {
                if (!blockedPackages.contains(app.packageName))
                    builder.addAllowedApplication(app.packageName)
            } catch (_: Exception) {}
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            stopSelfAndReset()
            return@withContext
        }

        // Mark running immediately and persist
        setRunningPref(true)
        isRunning = true

        // small delay ensures UI receivers are ready
        delay(250)
        sendBroadcastWithPackage(Intent("VPN_STARTED"))
        updateNotificationConnected()
        Log.i(TAG, "VPN connected")

        startNativeFirewall(blockedPackages, pm)
        startPacketMonitor()
    }

    private fun startNativeFirewall(blockedPackages: Set<String>, pm: PackageManager) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val blockedUids = blockedPackages.mapNotNull {
                    try { pm.getApplicationInfo(it, 0).uid } catch (_: Exception) { null }
                }.toIntArray()
                if (blockedUids.isNotEmpty()) {
                    try { NativeBridge.setBlockedUidsNative(blockedUids) } catch (_: Throwable) {}
                }
                vpnInterface?.fd?.let { fd ->
                    try { NativeBridge.startNative(fd) } catch (_: Throwable) { Log.w(TAG, "native start failed") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startNativeFirewall error: ${e.message}", e)
            }
        }
    }

    private fun startPacketMonitor() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val input = FileInputStream(vpnInterface?.fileDescriptor ?: return@launch)
                val buffer = ByteArray(8192)
                while (running.get()) {
                    val r = try { input.read(buffer) } catch (_: Exception) { -1 }
                    if (r <= 0) delay(50)
                }
                try { input.close() } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
    }

    private suspend fun reloadRules() = withContext(Dispatchers.IO) {
        try {
            val prefs = getSharedPreferences("firewall_prefs", MODE_PRIVATE)
            val blockedPackages = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()
            val pm = packageManager
            val blockedUids = blockedPackages.mapNotNull {
                try { pm.getApplicationInfo(it, 0).uid } catch (_: Exception) { null }
            }.toIntArray()
            if (blockedUids.isNotEmpty()) try { NativeBridge.setBlockedUidsNative(blockedUids) } catch (_: Throwable) {}
        } catch (_: Exception) {}
    }

    private fun stopVpn() {
        if (!running.get()) {
            stopSelf()
            return
        }

        // mark not running immediately so UI queries read correct state
        setRunningPref(false)
        isRunning = false
        running.set(false)

        serviceScope.launch(Dispatchers.IO) {
            try {
                try { NativeBridge.stopNative() } catch (_: Throwable) {}
                try { vpnInterface?.close() } catch (_: Exception) {}
                vpnInterface = null
                delay(500)
                stopForeground(STOP_FOREGROUND_REMOVE)
                sendBroadcastWithPackage(Intent("VPN_STOPPED"))
                updateNotificationStopped()
                Log.i(TAG, "VPN stopped")
            } catch (e: Exception) {
                Log.e(TAG, "stopVpn error: ${e.message}", e)
            } finally {
                stopSelf()
            }
        }
    }

    private fun stopSelfAndReset() {
        running.set(false)
        isRunning = false
        setRunningPref(false)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        sendBroadcastWithPackage(Intent("VPN_STOPPED"))
        stopSelf()
    }

    private fun buildNotification(statusText: String): Notification {
        val disconnectIntent = Intent(this, MyVpnService::class.java).apply { action = "DISCONNECT" }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1001, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 1002, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Firewall VPN Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Firewall VPN")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_firewall)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotificationConnected() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification("Connected — secure"))
    }

    private fun sendDisconnectingNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification("Disconnecting..."))
    }

    private fun updateNotificationStopped() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification("Disconnected — not secure"))
    }

    private fun setRunningPref(value: Boolean) {
        try {
            getSharedPreferences("firewall_prefs", MODE_PRIVATE)
                .edit().putBoolean("vpn_running", value).apply()
        } catch (_: Exception) {}
    }

    private fun sendBroadcastWithPackage(intent: Intent) {
        try {
            intent.setPackage(packageName)
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        serviceScope.cancel()
    }
}
