package com.vpn.fwwithmlb

import android.Manifest
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
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

    private val vpnStartedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            vpnToggle.isChecked = true
            vpnStatusText.text = "Connected"
            vpnSubtitleText.text = "Your connection is secure"
            Toast.makeText(this@MainActivity, "VPN Connected", Toast.LENGTH_SHORT).show()

            appsAdapter = AppsAdapter(loadInstalledApps(), packageManager, this@MainActivity, true)
            appsRecyclerView.adapter = appsAdapter
        }
    }

    private val vpnStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            vpnToggle.isChecked = false
            vpnStatusText.text = "Disconnected"
            vpnSubtitleText.text = "Your connection is not secure"
            Toast.makeText(this@MainActivity, "VPN Disconnected", Toast.LENGTH_SHORT).show()

            appsAdapter = AppsAdapter(loadInstalledApps(), packageManager, this@MainActivity, false)
            appsRecyclerView.adapter = appsAdapter
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ✅ Apply theme before inflating UI
        if (PreferencesManager.isDarkModeEnabled(this)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Toolbar + Drawer
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)

        toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_link_scanner -> startActivity(Intent(this, LinkScannerActivity::class.java))
                R.id.nav_threat_log -> startActivity(Intent(this, ThreatLogActivity::class.java))
                R.id.nav_feedback -> {
                    val formUrl =
                        "https://docs.google.com/forms/d/e/1FAIpQLSc2gBa4RwUtoNYz3l_zpuG7izPcUnKKMbzr-HriwbmkqVvuDg/viewform?usp=dialog"
                    try {
                        val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(formUrl)).apply {
                            setPackage("com.android.chrome")
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(chromeIntent)
                    } catch (e: Exception) {
                        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(formUrl)).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            setPackage(null)
                        }
                        startActivity(Intent.createChooser(fallbackIntent, "Open Feedback Form"))
                    }
                }
                R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.nav_version -> Toast.makeText(this, "App Version 1.0", Toast.LENGTH_SHORT).show()
                R.id.nav_developer -> Toast.makeText(this, "Developed by Omega", Toast.LENGTH_SHORT).show()
            }
            drawerLayout.closeDrawers()
            true
        }

        // UI
        vpnToggle = findViewById(R.id.vpnToggle)
        vpnStatusText = findViewById(R.id.vpnStatusText)
        vpnSubtitleText = findViewById(R.id.vpnSubtitleText)
        appsRecyclerView = findViewById(R.id.appsRecyclerView)
        themeToggleFab = findViewById(R.id.themeToggleFab)

        // ✅ Start Clipboard Monitor if enabled
        if (PreferencesManager.isClipboardScannerEnabled(this)) {
            val clipIntent = Intent(this, ClipboardMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(clipIntent)
            } else {
                startService(clipIntent)
            }
        }

        // ✅ Sync with saved Always-On VPN preference
        vpnToggle.isChecked = PreferencesManager.isVpnAlwaysOn(this)
        if (vpnToggle.isChecked) {
            vpnStatusText.text = "Connected"
            vpnSubtitleText.text = "Your connection is secure"
            startVpn()
        } else {
            vpnStatusText.text = "Disconnected"
            vpnSubtitleText.text = "Your connection is not secure"
        }

        // ✅ Sync FAB icon with theme
        themeToggleFab.setImageResource(
            if (PreferencesManager.isDarkModeEnabled(this))
                R.drawable.ic_baseline_dark_mode_24
            else
                R.drawable.ic_baseline_light_mode_24
        )

        // Runtime permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    200
                )
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS),
                201
            )
        }

        // VPN toggle switch
        vpnToggle.setOnCheckedChangeListener { _, isChecked ->
            PreferencesManager.setVpnAlwaysOn(this, isChecked)
            try {
                if (isChecked) {
                    if (!NetworkUtils.isNetworkAvailable(this)) {
                        vpnToggle.isChecked = false
                        Toast.makeText(
                            this,
                            "❌ No internet connection. Try again later.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnCheckedChangeListener
                    }

                    val intent = VpnService.prepare(this)
                    if (intent != null) {
                        startActivityForResult(intent, 100)
                    } else {
                        startVpn()
                    }
                } else {
                    val disconnectIntent = Intent(this, MyVpnService::class.java).apply {
                        action = "DISCONNECT"
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(disconnectIntent)
                    } else {
                        startService(disconnectIntent)
                    }
                }
            } catch (e: Exception) {
                vpnToggle.isChecked = false
                Toast.makeText(this, "⚠️ Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // RecyclerView
        appsRecyclerView.layoutManager = LinearLayoutManager(this)
        appsAdapter = AppsAdapter(loadInstalledApps(), packageManager, this, vpnToggle.isChecked)
        appsRecyclerView.adapter = appsAdapter

        // ✅ FAB theme toggle
        themeToggleFab.setOnClickListener {
            val isDark = PreferencesManager.isDarkModeEnabled(this)
            if (isDark) {
                PreferencesManager.setDarkMode(this, false)
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                themeToggleFab.setImageResource(R.drawable.ic_baseline_light_mode_24)
            } else {
                PreferencesManager.setDarkMode(this, true)
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                themeToggleFab.setImageResource(R.drawable.ic_baseline_dark_mode_24)
            }
            recreate()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, MyVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun loadInstalledApps(): List<ApplicationInfo> {
        val pm = packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launchable = pm.queryIntentActivities(launchIntent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }

        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        return (launchable + installed)
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            startVpn()
        } else if (requestCode == 100) {
            vpnToggle.isChecked = false
            vpnStatusText.text = "Disconnected"
            vpnSubtitleText.text = "Your connection is not secure"
            Toast.makeText(this, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    // ✅ Fixed section: registerReceiver crash solved
    override fun onStart() {
        super.onStart()

        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.RECEIVER_NOT_EXPORTED
        } else {
            0
        }

        ContextCompat.registerReceiver(
            this,
            vpnStartedReceiver,
            IntentFilter("VPN_STARTED"),
            receiverFlags
        )

        ContextCompat.registerReceiver(
            this,
            vpnStoppedReceiver,
            IntentFilter("VPN_STOPPED"),
            receiverFlags
        )
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(vpnStartedReceiver)
            unregisterReceiver(vpnStoppedReceiver)
        } catch (e: IllegalArgumentException) {
            // Ignore: already unregistered
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)

        val searchItem = menu?.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView

        searchView?.queryHint = "Search apps..."
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                appsAdapter.filter(query ?: "")
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                appsAdapter.filter(newText ?: "")
                return true
            }
        })

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_instructions -> {
                AlertDialog.Builder(this)
                    .setTitle("Instructions")
                    .setMessage(
                        """
                        🔹 Switch → Route app traffic through firewall VPN
                        🔹 CheckBox → Block internet completely for the app
                        🔹 Link Scanner → Scan URLs for safety
                        🔹 Threat Log → View detected malicious links
                        🔹 Clipboard Scanner → Runs in background to catch copied links
                        🔹 Theme Button → Toggle light/dark mode
                        🔹 Search → Quickly find apps
                        🔹 Feedback → Google Form
                        🔹 Settings → Configure app preferences
                        """.trimIndent()
                    )
                    .setPositiveButton("OK", null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
