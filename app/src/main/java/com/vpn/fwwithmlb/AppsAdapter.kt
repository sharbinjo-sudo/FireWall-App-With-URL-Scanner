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

        // Load saved states
        var vpnApps = prefs.getStringSet("vpn_apps", null)
        var blockedApps = prefs.getStringSet("blocked_apps", null)
        val savedVersion = prefs.getInt("saved_app_version", -1)
        val currentVersion = try {
            pm.getPackageInfo(appContext.packageName, 0).versionCode
        } catch (e: Exception) {
            -1
        }

        val isFirstLaunchOrUpdate = savedVersion != currentVersion

        // 🧩 On first install OR after each update: all switches ON, checkboxes UNCHECKED
        if (isFirstLaunchOrUpdate) {
            vpnApps = apps.map { it.packageName }.toMutableSet() // all ON
            blockedApps = apps.map { it.packageName }.toMutableSet() // all unchecked = blocked
            prefs.edit()
                .putStringSet("vpn_apps", vpnApps)
                .putStringSet("blocked_apps", blockedApps)
                .putInt("saved_app_version", currentVersion)
                .apply()
        }

        val vpnSet = vpnApps?.toMutableSet() ?: mutableSetOf()
        val blockedSet = blockedApps?.toMutableSet() ?: mutableSetOf()

        // Remove listeners to avoid unwanted triggers during binding
        holder.appSwitch.setOnCheckedChangeListener(null)
        holder.appCheckBox.setOnCheckedChangeListener(null)

        // 🔘 Switch setup
        holder.appSwitch.isEnabled = true
        holder.appSwitch.isChecked = vpnSet.contains(pkg)

        // ☐ Checkbox setup
        val isBlocked = blockedSet.contains(pkg)
        holder.appCheckBox.isEnabled = true
        holder.appCheckBox.isChecked = !isBlocked

        // 🌐 Switch toggle → add/remove from VPN
        holder.appSwitch.setOnCheckedChangeListener { _, isChecked ->
            val updated = prefs.getStringSet("vpn_apps", emptySet())!!.toMutableSet()
            if (isChecked) updated.add(pkg) else updated.remove(pkg)
            prefs.edit().putStringSet("vpn_apps", HashSet(updated)).apply()
            scheduleVpnRestart()
        }

        // 🚫 Checkbox toggle → add/remove from blocked list
        holder.appCheckBox.setOnCheckedChangeListener { _, isChecked ->
            val updated = prefs.getStringSet("blocked_apps", emptySet())!!.toMutableSet()
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

    private fun scheduleVpnRestart() {
        if (restartPending) restartHandler.removeCallbacksAndMessages(null)
        restartPending = true
        restartHandler.postDelayed({
            restartPending = false
            restartVpn()
        }, 1500)
    }

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
