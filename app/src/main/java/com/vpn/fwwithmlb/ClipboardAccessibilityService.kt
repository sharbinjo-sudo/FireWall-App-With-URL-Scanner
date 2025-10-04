package com.vpn.fwwithmlb

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

class ClipboardAccessibilityService : AccessibilityService() {

    private lateinit var clipboard: ClipboardManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        Log.d("ClipboardService", "✅ Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Check clipboard on every event (this keeps service alive)
        val clip = clipboard.primaryClip
        val item = clip?.getItemAt(0)?.text?.toString() ?: return

        if (item.startsWith("http://") || item.startsWith("https://")) {
            Log.d("ClipboardService", "🔗 Clipboard URL detected via Accessibility: $item")
            ClipboardScanHelper.handleUrl(this, item)
        }
    }

    override fun onInterrupt() {
        Log.d("ClipboardService", "⚠️ Accessibility Service interrupted")
    }
}