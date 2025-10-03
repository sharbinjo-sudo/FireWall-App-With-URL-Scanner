package com.vpn.fwwithmlb

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
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

        // Clear old listeners
        holder.appSwitch.setOnCheckedChangeListener(null)
        holder.appCheckBox.setOnCheckedChangeListener(null)

        holder.appSwitch.isChecked = vpnApps.contains(pkg)
        holder.appCheckBox.isChecked = blockedApps.contains(pkg)

        holder.appSwitch.isEnabled = vpnEnabled
        holder.appCheckBox.isEnabled = vpnEnabled

        if (!vpnEnabled) return

        // Switch = VPN routing
        holder.appSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!NetworkUtils.isNetworkAvailable(appContext)) {
                holder.appSwitch.isChecked = vpnApps.contains(pkg)
                Toast.makeText(appContext, "❌ No internet connection", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }

            val updated = prefs.getStringSet("vpn_apps", emptySet())!!.toMutableSet()
            if (isChecked) updated.add(pkg) else updated.remove(pkg)
            prefs.edit().putStringSet("vpn_apps", updated).apply()

            restartVpn()
        }

        // CheckBox = Block Internet
        holder.appCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (!NetworkUtils.isNetworkAvailable(appContext)) {
                holder.appCheckBox.isChecked = blockedApps.contains(pkg)
                Toast.makeText(appContext, "❌ No internet connection", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }

            val updated = prefs.getStringSet("blocked_apps", emptySet())!!.toMutableSet()
            if (isChecked) updated.add(pkg) else updated.remove(pkg)
            prefs.edit().putStringSet("blocked_apps", updated).apply()

            restartVpn()
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

    // 🔹 Restart VPN so changes apply instantly
    private fun restartVpn() {
        appContext.stopService(Intent(appContext, MyVpnService::class.java))
        appContext.startService(Intent(appContext, MyVpnService::class.java))
    }
}