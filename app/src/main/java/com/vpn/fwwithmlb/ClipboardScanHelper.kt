package com.vpn.fwwithmlb

import android.content.Context
import android.util.Log

object ClipboardScanHelper {

    fun handleUrl(context: Context, url: String) {
        Log.d("ClipboardScanHelper", "📋 Scanning from Accessibility: $url")
        // Directly reuse your existing ThreatLog + VirusTotal code
        ThreatLogActivity.saveLog(context, url, "Clipboard")

        // TODO: Call your existing VirusTotal scan method here
        // For now, just log + save
    }
}