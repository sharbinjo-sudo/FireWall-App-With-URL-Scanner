package com.vpn.fwwithmlb

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.appcompat.app.AppCompatDelegate

class SettingsFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_preferences, rootKey)

        // 🔹 Threat Log Preference
        val threatLogPref: Preference? = findPreference("view_threatlog")
        threatLogPref?.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), ThreatLogActivity::class.java)
            startActivity(intent)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        when (key) {

            // 🌙 Dark Mode toggle
            "dark_mode" -> {
                val enabled = sharedPreferences.getBoolean(key, false)
                PreferencesManager.setDarkMode(requireContext(), enabled)

                AppCompatDelegate.setDefaultNightMode(
                    if (enabled) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )

                requireActivity().recreate() // refresh UI

                Toast.makeText(
                    requireContext(),
                    if (enabled) "🌙 Dark Mode Enabled" else "☀️ Light Mode Enabled",
                    Toast.LENGTH_SHORT
                ).show()
            }

            // 🚀 Auto Start
            "auto_start" -> {
                val enabled = sharedPreferences.getBoolean(key, false)
                Toast.makeText(
                    requireContext(),
                    if (enabled) "🚀 Auto Start Enabled (App will run on boot)" else "Auto Start Disabled",
                    Toast.LENGTH_SHORT
                ).show()
                // BootReceiver handles actual startup
            }

            // 🔐 VPN Always-On
            "vpn_always_on" -> {
                val enabled = sharedPreferences.getBoolean(key, false)
                PreferencesManager.setVpnAlwaysOn(requireContext(), enabled)

                Toast.makeText(
                    requireContext(),
                    if (enabled) "🔐 Always-On VPN Activated" else "VPN Always-On Disabled",
                    Toast.LENGTH_SHORT
                ).show()

                val vpnIntent = Intent(requireContext(), MyVpnService::class.java).apply {
                    action = if (enabled) "START_ALWAYS_ON" else "DISCONNECT"
                }
                requireContext().startService(vpnIntent)

                // 🔄 Sync with MainActivity toggle via broadcast
                val syncIntent = Intent("VPN_ALWAYS_ON_CHANGED").apply {
                    putExtra("enabled", enabled)
                }
                requireContext().sendBroadcast(syncIntent)
            }

            // 📋 Clipboard Scanner (✅ fixed block)
            "clipboard_scanner" -> {
                val enabled = sharedPreferences.getBoolean(key, false)
                Toast.makeText(
                    requireContext(),
                    if (enabled) "📋 Clipboard Scanner Enabled" else "📋 Clipboard Scanner Disabled",
                    Toast.LENGTH_SHORT
                ).show()

                val serviceIntent = Intent(requireContext(), ClipboardMonitorService::class.java)
                if (enabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        requireContext().startForegroundService(serviceIntent)
                    } else {
                        requireContext().startService(serviceIntent)
                    }
                } else {
                    requireContext().stopService(serviceIntent)
                }
            }

            // ⛔ Block Background Apps
            "block_background_apps" -> {
                val enabled = sharedPreferences.getBoolean(key, true)
                Toast.makeText(
                    requireContext(),
                    if (enabled) "⛔ Blocking background apps" else "✔️ Allowing background apps",
                    Toast.LENGTH_SHORT
                ).show()
            }

            // 📩 SMS Scanner
            "sms_scanner" -> {
                val enabled = sharedPreferences.getBoolean(key, true)
                Toast.makeText(
                    requireContext(),
                    if (enabled) "📩 SMS Scanner Enabled" else "SMS Scanner Disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }

            // 🌐 Threat Data Sharing
            "share_anonymous_data" -> {
                val enabled = sharedPreferences.getBoolean(key, false)
                Toast.makeText(
                    requireContext(),
                    if (enabled) "🌐 Sharing anonymous threat data" else "Stopped sharing threat data",
                    Toast.LENGTH_SHORT
                ).show()
                // TODO: integrate blockchain logging
            }

            // 🔔 Alerts toggle
            "alerts_enabled" -> {
                val enabled = sharedPreferences.getBoolean(key, true)
                Toast.makeText(
                    requireContext(),
                    if (enabled) "🔔 Alerts Enabled" else "Alerts Disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }

            // 🔊 Alert Sound
            "alert_sound" -> {
                val value = sharedPreferences.getString(key, "default")
                Toast.makeText(
                    requireContext(),
                    "🔊 Alert sound set to: $value",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}