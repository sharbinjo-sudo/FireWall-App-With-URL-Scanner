package com.vpn.fwwithmlb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {

            // ✅ Restart Clipboard Scanner if enabled
            if (PreferencesManager.isClipboardScannerEnabled(context)) {
                val clipIntent = Intent(context, ClipboardMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(clipIntent)
                } else {
                    context.startService(clipIntent)
                }
            }

            // ✅ Restart VPN if Always-On enabled
            if (PreferencesManager.isVpnAlwaysOn(context)) {
                val vpnIntent = Intent(context, MyVpnService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(vpnIntent)
                } else {
                    context.startService(vpnIntent)
                }
            }
        }
    }
}