package com.vpn.fwwithmlb

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class AppsAdapter(
    private val apps: List<ApplicationInfo>,
    private val pm: PackageManager,
    context: Context,
    private var vpnEnabled: Boolean
) : RecyclerView.Adapter<AppsAdapter.AppViewHolder>() {

    private val prefs = context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext
    private var filteredApps: MutableList<ApplicationInfo> = apps.toMutableList()

    // Used to debounce rapid restart requests
    private val restartHandler = Handler(Looper.getMainLooper())
    private var restartPending = false

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appIcon: ImageView = view.findViewById(R.id.appIcon)
        val appName: TextView = view.findViewById(R.id.appName)
        val packageName: TextView = view.findViewById(R.id.packageName)
        val appSwitch: Switch = view.findViewById(R.id.appSwitch)
        val appCheckBox: CheckBox = view.findViewById(R.id.appCheckBox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val appInfo = filteredApps[position]
        val pkg = appInfo.packageName

        holder.appName.text = pm.getApplicationLabel(appInfo)
        holder.packageName.text = pkg
        holder.appIcon.setImageDrawable(pm.getApplicationIcon(appInfo))

        val vpnApps = prefs.getStringSet("vpn_apps", emptySet())!!.toMutableSet()
        val blockedApps = prefs.getStringSet("blocked_apps", emptySet())!!.toMutableSet()

        // remove previous listeners
        holder.appSwitch.setOnCheckedChangeListener(null)
        holder.appCheckBox.setOnCheckedChangeListener(null)

        // 🟢 All toggles enabled (VPN or not)
        holder.appSwitch.isEnabled = true
        holder.appCheckBox.isEnabled = true

        // Default ON for checkbox (internet enabled) if no saved state
        val isBlocked = blockedApps.contains(pkg)
        holder.appCheckBox.isChecked = !isBlocked // inverse logic → checked = internet allowed
        holder.appSwitch.isChecked = vpnApps.contains(pkg)

        // 🌐 Switch → Include in VPN
        holder.appSwitch.setOnCheckedChangeListener { _, isChecked ->
            val updated = prefs.getStringSet("vpn_apps", emptySet())!!.toMutableSet()
            if (isChecked) updated.add(pkg) else updated.remove(pkg)
            prefs.edit().putStringSet("vpn_apps", HashSet(updated)).apply()
            scheduleVpnRestart()
        }

        // 🚫 Checkbox → Block/Unblock app
        holder.appCheckBox.setOnCheckedChangeListener { _, isChecked ->
            val updated = prefs.getStringSet("blocked_apps", emptySet())!!.toMutableSet()
            // inverse logic: checked = internet allowed, unchecked = blocked
            if (!isChecked) updated.add(pkg) else updated.remove(pkg)
            prefs.edit().putStringSet("blocked_apps", HashSet(updated)).apply()
            scheduleVpnRestart()
        }
    }

    override fun getItemCount(): Int = filteredApps.size

    fun filter(query: String) {
        filteredApps = if (query.isEmpty()) {
            apps.toMutableList()
        } else {
            apps.filter {
                pm.getApplicationLabel(it).toString().contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }.toMutableList()
        }
        notifyDataSetChanged()
    }

    fun setVpnEnabled(enabled: Boolean) {
        vpnEnabled = enabled
        notifyDataSetChanged()
    }

    /**
     * 🔁 Delayed (debounced) VPN restart to avoid ANR/freezes.
     */
    private fun scheduleVpnRestart() {
        if (restartPending) {
            restartHandler.removeCallbacksAndMessages(null)
        }
        restartPending = true
        restartHandler.postDelayed({
            restartPending = false
            restartVpn()
        }, 1500) // restart 1.5s after last toggle
    }

    /**
     * 🧠 Restart VPN service safely with new rules.
     */
    private fun restartVpn() {
        try {
            val stopIntent = Intent(appContext, MyVpnService::class.java)
            appContext.stopService(stopIntent)

            val restartIntent = Intent(appContext, MyVpnService::class.java).apply {
                action = "RELOAD_RULES"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, restartIntent)
            } else {
                appContext.startService(restartIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
