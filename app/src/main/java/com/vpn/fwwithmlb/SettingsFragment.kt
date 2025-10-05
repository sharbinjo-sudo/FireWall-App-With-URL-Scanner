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
            startActivity(Intent(requireContext(), ThreatLogActivity::class.java))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {

            "dark_mode" -> {
                val enabled = sharedPreferences?.getBoolean(key, false) ?: false
                PreferencesManager.setDarkMode(requireContext(), enabled)

                AppCompatDelegate.setDefaultNightMode(
                    if (enabled) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )
                requireActivity().recreate()
                Toast.makeText(
                    requireContext(),
                    if (enabled) "🌙 Dark Mode Enabled" else "☀️ Light Mode Enabled",
                    Toast.LENGTH_SHORT
                ).show()
            }

            "auto_start" -> {
                val enabled = sharedPreferences?.getBoolean(key, false) ?: false
                Toast.makeText(
                    requireContext(),
                    if (enabled) "🚀 Auto Start Enabled (App will run on boot)" else "Auto Start Disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }

            "vpn_always_on" -> {
                val enabled = sharedPreferences?.getBoolean(key, false) ?: false
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

                val syncIntent = Intent("VPN_ALWAYS_ON_CHANGED").apply {
                    putExtra("enabled", enabled)
                }
                requireContext().sendBroadcast(syncIntent)
            }

            "clipboard_scanner" -> {
                val enabled = sharedPreferences?.getBoolean(key, false) ?: false
                Toast.makeText(
                    requireContext(),
                    if (enabled) "📋 Clipboard Scanner Enabled" else "📋 Clipboard Scanner Disabled",
                    Toast.LENGTH_SHORT
                ).show()

                val serviceIntent = Intent(requireContext(), ClipboardMonitorService::class.java)
                if (enabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        requireContext().startForegroundService(serviceIntent)
                    else
                        requireContext().startService(serviceIntent)
                } else {
                    requireContext().stopService(serviceIntent)
                }
            }

            "block_background_apps" -> {
                val enabled = sharedPreferences?.getBoolean(key, true) ?: true
                Toast.makeText(
                    requireContext(),
                    if (enabled) "⛔ Blocking background apps" else "✔️ Allowing background apps",
                    Toast.LENGTH_SHORT
                ).show()
            }

            "sms_scanner" -> {
                val enabled = sharedPreferences?.getBoolean(key, true) ?: true
                Toast.makeText(
                    requireContext(),
                    if (enabled) "📩 SMS Scanner Enabled" else "SMS Scanner Disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }

            "share_anonymous_data" -> {
                val enabled = sharedPreferences?.getBoolean(key, false) ?: false
                Toast.makeText(
                    requireContext(),
                    if (enabled) "🌐 Sharing anonymous threat data" else "Stopped sharing threat data",
                    Toast.LENGTH_SHORT
                ).show()
            }

            "alerts_enabled" -> {
                val enabled = sharedPreferences?.getBoolean(key, true) ?: true
                Toast.makeText(
                    requireContext(),
                    if (enabled) "🔔 Alerts Enabled" else "Alerts Disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }

            "alert_sound" -> {
                val value = sharedPreferences?.getString(key, "default")
                Toast.makeText(
                    requireContext(),
                    "🔊 Alert sound set to: $value",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
