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

/**
 * MyVpnService — VPN that uses Builder.addAllowedApplication() for strict rules,
 * and also notifies the native bridge (if present) with blocked UIDs.
 *
 * IMPORTANT:
 * - On unrooted Android, to *fully prevent* apps from using the network outside the VPN,
 *   the user must enable Always-On VPN with Lockdown in system settings for this VPN app.
 *   Without that, excluded apps may still use the device network and will not be blocked.
 */
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

        try {
            // Load blocked packages from prefs
            val prefs = getSharedPreferences("firewall_prefs", MODE_PRIVATE)
            val blockedPackages = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()
            Log.d(TAG, "Blocked packages from prefs: $blockedPackages")

            // Get list of all launchable apps (visible to user)
            val pm = packageManager
            val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launchablePkgs = pm.queryIntentActivities(launchIntent, 0)
                .mapNotNull { it.activityInfo?.applicationInfo?.packageName }
                .toSet()

            // Allowed apps = all launchable apps MINUS blocked
            val allowedPkgs = launchablePkgs.filterNot { blockedPackages.contains(it) }

            Log.i(TAG, "Allowed apps count=${allowedPkgs.size} Blocked count=${blockedPackages.size}")

            // Build VPN: capture all IPv4; restrict to allowed apps only (strict rule)
            val builder = Builder()
                .setSession("FW with MLB Firewall")
                .addAddress("10.0.0.2", 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0) // capture IPv4

            // IMPORTANT: addAllowedApplication restricts the VPN to these apps only.
            // If you want the blocked apps to be unable to use network at all, the user
            // must enable Always-on VPN + Lockdown for this app (Android settings).
            for (pkg in allowedPkgs) {
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Allowed app not found: $pkg")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed addAllowedApplication($pkg): ${e.message}")
                }
            }

            // Establish VPN
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface.")
                stopSelf()
                return
            }

            // Send blocked UIDs to native layer (best-effort)
            try {
                val blockedUids = mutableListOf<Int>()
                for (pkg in blockedPackages) {
                    try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        blockedUids.add(ai.uid)
                        Log.i(TAG, "Will block UID=${ai.uid} for package=$pkg")
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Cannot resolve package to UID: $pkg")
                    }
                }
                if (blockedUids.isNotEmpty()) {
                    // Call native (if present). JNI signature must exist in NativeBridge.kt
                    NativeBridge.setBlockedUidsNative(blockedUids.toIntArray())
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error preparing blocked UIDs for native: ${e.message}")
            }

            // Start native bridge (best-effort) with integer fd if available
            try {
                val fd = vpnInterface?.fd
                if (fd != null) {
                    NativeBridge.startNative(fd)
                    Log.i(TAG, "NativeBridge.startNative called with fd=$fd")
                } else {
                    Log.w(TAG, "vpnInterface.fd was null")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "NativeBridge.startNative failed: ${e.message}")
            }

            // Optional packet monitor (debug)
            vpnThread = Thread {
                try {
                    val inFd = vpnInterface?.fileDescriptor ?: return@Thread
                    val input = FileInputStream(inFd)
                    val buffer = ByteArray(32767)
                    while (!Thread.interrupted()) {
                        val r = input.read(buffer)
                        if (r > 0) {
                            val packet = ByteBuffer.wrap(buffer, 0, r)
                            val first = packet.get(0).toInt() and 0xFF
                            val version = first shr 4
                            if (version == 4) {
                                Log.v(TAG, "Captured IPv4 packet length=$r")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "VPN read thread ended: ${e.message}")
                }
            }
            vpnThread?.start()

            // Foreground notification
            startForeground(NOTIFICATION_ID, buildNotification())
            sendBroadcast(Intent("VPN_STARTED"))
            Log.i(TAG, "VPN started successfully (allowedPkgs=${allowedPkgs.size}).")

            // NOTE TO DEVELOPER / TESTER:
            // If you want full blocking (blocked apps cannot access network),
            // go to Android Settings -> Network & internet -> VPN -> (this app)
            // and enable "Always-on" and "Block connections without VPN" (Lockdown).
            // Without that, apps excluded from the VPN may still reach the network directly.

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN: ${e.message}", e)
            stopSelf()
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN...")
        try {
            vpnThread?.interrupt()
            vpnThread = null

            // best-effort notify native to stop
            try {
                NativeBridge.stopNative()
            } catch (e: Throwable) {
                Log.w(TAG, "NativeBridge.stopNative failed: ${e.message}")
            }

            vpnInterface?.close()
            vpnInterface = null
            stopForeground(true)
            sendBroadcast(Intent("VPN_STOPPED"))
            Log.i(TAG, "VPN stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN: ${e.message}", e)
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
            .setContentTitle("Firewall VPN Active")
            .setContentText("Strict rules applied for selected apps")
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
