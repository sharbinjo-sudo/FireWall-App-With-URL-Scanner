package com.vpn.fwwithmlb

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.*
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var vpnToggle: Switch
    private lateinit var vpnStatusText: TextView
    private lateinit var vpnSubtitleText: TextView
    private lateinit var appsRecyclerView: RecyclerView
    private lateinit var themeToggleFab: FloatingActionButton
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle
    private lateinit var appsAdapter: AppsAdapter
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingText: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var vpnStartInProgress = false

    private val vpnStartedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            hideLoading()
            vpnStartInProgress = false
            vpnToggle.isEnabled = true
            vpnToggle.isChecked = true
            vpnStatusText.text = "Connected"
            vpnSubtitleText.text = "Your connection is secure"
            appsAdapter.setVpnEnabled(true)
            Toast.makeText(this@MainActivity, "VPN Connected", Toast.LENGTH_SHORT).show()
        }
    }

    private val vpnStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            hideLoading()
            vpnStartInProgress = false
            vpnToggle.isEnabled = true
            vpnToggle.isChecked = false
            vpnStatusText.text = "Disconnected"
            vpnSubtitleText.text = "Your connection is not secure"
            appsAdapter.setVpnEnabled(false)
            Toast.makeText(this@MainActivity, "VPN Disconnected", Toast.LENGTH_SHORT).show()
        }
    }

    private val vpnDisconnectingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            showLoading("Stopping VPN...")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (PreferencesManager.isDarkModeEnabled(this)) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        else AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        toggle = ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_link_scanner -> startActivity(Intent(this, LinkScannerActivity::class.java))
                R.id.nav_threat_log -> startActivity(Intent(this, ThreatLogActivity::class.java))
                R.id.nav_feedback -> openFeedbackForm()
                R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.nav_version -> Toast.makeText(this, "App Version 1.0", Toast.LENGTH_SHORT).show()
                R.id.nav_developer -> Toast.makeText(this, "Developed by Omega", Toast.LENGTH_SHORT).show()
            }
            drawerLayout.closeDrawers()
            true
        }

        vpnToggle = findViewById(R.id.vpnToggle)
        vpnStatusText = findViewById(R.id.vpnStatusText)
        vpnSubtitleText = findViewById(R.id.vpnSubtitleText)
        appsRecyclerView = findViewById(R.id.appsRecyclerView)
        themeToggleFab = findViewById(R.id.themeToggleFab)

        loadingOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xAA000000.toInt())
            visibility = View.GONE
            isClickable = true
            val progress = ProgressBar(this@MainActivity)
            progress.isIndeterminate = true
            addView(progress, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = android.view.Gravity.CENTER })
            loadingText = TextView(this@MainActivity).apply {
                text = "Starting VPN..."
                setTextColor(android.graphics.Color.WHITE)
                textSize = 18f
                setPadding(0, 300, 0, 0)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            addView(loadingText)
        }
        addContentView(loadingOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        requestRuntimePermissions()

        appsRecyclerView.layoutManager = LinearLayoutManager(this)
        appsAdapter = AppsAdapter(loadInstalledApps(), packageManager, this, MyVpnService.isRunning)
        appsRecyclerView.adapter = appsAdapter

        // sync UI from persisted flag first (fast)
        val prefs = getSharedPreferences("firewall_prefs", MODE_PRIVATE)
        val persistedRunning = prefs.getBoolean("vpn_running", false)
        updateVpnUi(persistedRunning)
        vpnToggle.isChecked = persistedRunning

        vpnToggle.setOnCheckedChangeListener { _, isChecked ->
            if (vpnStartInProgress) {
                vpnToggle.isChecked = !isChecked
                return@setOnCheckedChangeListener
            }
            vpnStartInProgress = true
            vpnToggle.isEnabled = false
            if (isChecked) {
                showLoading("Starting VPN...")
                PreferencesManager.setVpnAlwaysOn(this, true)
                startVpn()
            } else {
                showLoading("Stopping VPN...")
                PreferencesManager.setVpnAlwaysOn(this, false)
                stopVpn()
            }
        }

        themeToggleFab.setImageResource(if (PreferencesManager.isDarkModeEnabled(this)) R.drawable.ic_baseline_dark_mode_24 else R.drawable.ic_baseline_light_mode_24)
        themeToggleFab.setOnClickListener {
            val dark = PreferencesManager.isDarkModeEnabled(this)
            PreferencesManager.setDarkMode(this, !dark)
            AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES)
            recreate()
        }
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, 100) else startVpnService()
    }

    private fun startVpnService() {
        val intent = Intent(this, MyVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopVpn() {
        val intent = Intent(this, MyVpnService::class.java).apply { action = "DISCONNECT" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun updateVpnUi(connected: Boolean) {
        vpnStatusText.text = if (connected) "Connected" else "Disconnected"
        vpnSubtitleText.text = if (connected) "Your connection is secure" else "Your connection is not secure"
        appsAdapter.setVpnEnabled(connected)
    }

    private fun openFeedbackForm() {
        val url = "https://docs.google.com/forms/d/e/1FAIpQLSc2gBa4RwUtoNYz3l_zpuG7izPcUnKKMbzr-HriwbmkqVvuDg/viewform?usp=dialog"
        try {
            val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { setPackage("com.android.chrome"); addCategory(Intent.CATEGORY_BROWSABLE) }
            startActivity(chromeIntent)
        } catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun loadInstalledApps(): List<ApplicationInfo> {
        val pm = packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val launchable = pm.queryIntentActivities(launchIntent, 0).mapNotNull { it.activityInfo?.applicationInfo }
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return (launchable + installed).distinctBy { it.packageName }.filter { it.packageName != packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
    }

    fun showLoading(text: String) {
        loadingText.text = text
        loadingOverlay.visibility = View.VISIBLE
    }

  fun hideLoading() {
        loadingOverlay.visibility = View.GONE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) startVpnService()
        else if (requestCode == 100) {
            hideLoading()
            vpnStartInProgress = false
            vpnToggle.isChecked = false
            Toast.makeText(this, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(vpnStartedReceiver, IntentFilter("VPN_STARTED"), Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(vpnStoppedReceiver, IntentFilter("VPN_STOPPED"), Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(vpnDisconnectingReceiver, IntentFilter("VPN_DISCONNECTING"), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(vpnStartedReceiver, IntentFilter("VPN_STARTED"))
                registerReceiver(vpnStoppedReceiver, IntentFilter("VPN_STOPPED"))
                registerReceiver(vpnDisconnectingReceiver, IntentFilter("VPN_DISCONNECTING"))
            }
        } catch (_: Exception) {}

        // Query live service state; service will reply with VPN_STARTED or VPN_STOPPED
        try {
            val query = Intent(this, MyVpnService::class.java).apply { action = "VPN_STATUS_QUERY" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(query) else startService(query)
        } catch (_: Exception) {}

        // read persisted flag as immediate fallback
        val prefs = getSharedPreferences("firewall_prefs", MODE_PRIVATE)
        val persistedRunning = prefs.getBoolean("vpn_running", false)
        updateVpnUi(persistedRunning)
        vpnToggle.isChecked = persistedRunning

        // failsafe: hide loading after 10s
        uiHandler.postDelayed({
            hideLoading()
            vpnStartInProgress = false
            vpnToggle.isEnabled = true
        }, 10000)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(vpnStartedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(vpnStoppedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(vpnDisconnectingReceiver) } catch (_: Exception) {}
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        val searchItem = menu?.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = "Search apps..."
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true.also { appsAdapter.filter(query ?: "") }
            override fun onQueryTextChange(newText: String?) = true.also { appsAdapter.filter(newText ?: "") }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_instructions -> {
                AlertDialog.Builder(this).setTitle("Instructions")
                    .setMessage("""
                        🔹 Switch → Route app traffic through firewall VPN
                        🔹 CheckBox → Block internet completely for the app
                        🔹 Link Scanner → Scan URLs for safety
                        🔹 Threat Log → View detected malicious links
                        🔹 Clipboard Scanner → Runs background checks
                        🔹 Theme Button → Toggle light/dark mode
                        🔹 Search → Quickly find apps
                        🔹 Feedback → Google Form
                        🔹 Settings → Configure app preferences
                    """.trimIndent())
                    .setPositiveButton("OK", null).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
