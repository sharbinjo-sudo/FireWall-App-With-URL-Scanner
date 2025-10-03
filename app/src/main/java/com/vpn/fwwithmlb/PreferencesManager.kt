package com.vpn.fwwithmlb

import android.content.Context
import androidx.preference.PreferenceManager

object PreferencesManager {

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context)

    // 🌙 Dark Mode
    fun isDarkModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean("dark_mode", false)

    fun setDarkMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("dark_mode", enabled).apply()
    }

    // 🔐 VPN Always-On
    fun isVpnAlwaysOn(context: Context): Boolean =
        prefs(context).getBoolean("vpn_always_on", false)

    fun setVpnAlwaysOn(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("vpn_always_on", enabled).apply()
    }

    // 📩 SMS Scanner
    fun isSmsScannerEnabled(context: Context): Boolean =
        prefs(context).getBoolean("sms_scanner", true)

    fun setSmsScannerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("sms_scanner", enabled).apply()
    }

    // 📋 Clipboard Scanner
    fun isClipboardScannerEnabled(context: Context): Boolean =
        prefs(context).getBoolean("clipboard_scanner", false)

    fun setClipboardScannerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("clipboard_scanner", enabled).apply()
    }

    // 🌐 Threat Sharing
    fun isThreatSharingEnabled(context: Context): Boolean =
        prefs(context).getBoolean("share_anonymous_data", false)

    fun setThreatSharingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("share_anonymous_data", enabled).apply()
    }

    // 🔔 Alerts
    fun areAlertsEnabled(context: Context): Boolean =
        prefs(context).getBoolean("alerts_enabled", true)

    fun setAlertsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("alerts_enabled", enabled).apply()
    }

    // 🔊 Alert Sound
    fun getAlertSound(context: Context): String =
        prefs(context).getString("alert_sound", "default") ?: "default"

    fun setAlertSound(context: Context, value: String) {
        prefs(context).edit().putString("alert_sound", value).apply()
    }
}