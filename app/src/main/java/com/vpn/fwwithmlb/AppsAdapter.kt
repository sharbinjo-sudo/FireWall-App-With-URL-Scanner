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
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

class AppsAdapter(
    private val apps: List<ApplicationInfo>,
    private val pm: PackageManager,
    context: Context,
    private var vpnEnabled: Boolean
) : RecyclerView.Adapter<AppsAdapter.AppViewHolder>() {

    private val prefs = context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext
    private val contextRef = WeakReference(context)
    private var filteredApps: MutableList<ApplicationInfo> = apps.toMutableList()

    private var reloadJob: Job? = null
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controlsEnabled = vpnEnabled

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appIcon: ImageView = view.findViewById(R.id.appIcon)
        val appName: TextView = view.findViewById(R.id.appName)
        val packageName: TextView = view.findViewById(R.id.packageName)
        val appSwitch: Switch = view.findViewById(R.id.appSwitch)
        val appCheckBox: CheckBox = view.findViewById(R.id.appCheckBox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val appInfo = filteredApps[position]
        val pkg = appInfo.packageName

        holder.appName.text = pm.getApplicationLabel(appInfo)
        holder.packageName.text = pkg
        holder.appIcon.setImageDrawable(pm.getApplicationIcon(appInfo))

        val vpnApps = prefs.getStringSet("vpn_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        val blockedApps = prefs.getStringSet("blocked_apps", emptySet())?.toMutableSet() ?: mutableSetOf()

        holder.appSwitch.setOnCheckedChangeListener(null)
        holder.appCheckBox.setOnCheckedChangeListener(null)

        holder.appSwitch.isEnabled = controlsEnabled
        holder.appCheckBox.isEnabled = controlsEnabled

        holder.appSwitch.isChecked = vpnApps.contains(pkg)
        holder.appCheckBox.isChecked = !blockedApps.contains(pkg)

        holder.appSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!controlsEnabled) return@setOnCheckedChangeListener
            val updated = prefs.getStringSet("vpn_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
            if (isChecked) updated.add(pkg) else updated.remove(pkg)
            prefs.edit().putStringSet("vpn_apps", HashSet(updated)).apply()
            scheduleRulesReload()
        }

        holder.appCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (!controlsEnabled) return@setOnCheckedChangeListener
            val updated = prefs.getStringSet("blocked_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
            if (!isChecked) updated.add(pkg) else updated.remove(pkg)
            prefs.edit().putStringSet("blocked_apps", HashSet(updated)).apply()
            scheduleRulesReload()
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
        controlsEnabled = enabled
        notifyDataSetChanged()
    }

    private fun scheduleRulesReload() {
        reloadJob?.cancel()
        reloadJob = adapterScope.launch {
            delay(2500)
            withContext(Dispatchers.Main) { performRulesReloadWithUiFeedback() }
        }
    }

    private fun performRulesReloadWithUiFeedback() {
        try {
            val ctx = contextRef.get()
            if (ctx is MainActivity) {
                ctx.showLoading("Updating rules...")
                mainHandler.postDelayed({ ctx.hideLoading() }, 2000)
            }
            val intent = Intent(appContext, MyVpnService::class.java).apply { action = "RELOAD_RULES" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ContextCompat.startForegroundService(appContext, intent)
            else
                appContext.startService(intent)
        } catch (_: Exception) {}
    }

    fun cleanup() {
        reloadJob?.cancel()
        adapterScope.cancel()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        cleanup()
    }
}
