package com.vpn.fwwithmlb

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class LinkScannerActivity : AppCompatActivity() {

    private lateinit var urlInput: TextInputEditText
    private lateinit var scanButton: Button
    private lateinit var resultCard: MaterialCardView
    private lateinit var resultText: TextView
    private lateinit var resultIcon: ImageView
    private lateinit var domainText: TextView
    private lateinit var openBrowserBtn: Button

    private var lastScannedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_link_scanner)

        val toolbar: Toolbar = findViewById(R.id.scannerToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Link Scanner"

        urlInput = findViewById(R.id.urlInput)
        scanButton = findViewById(R.id.scanButton)
        resultCard = findViewById(R.id.resultCard)
        resultText = findViewById(R.id.resultText)
        resultIcon = findViewById(R.id.resultIcon)
        domainText = findViewById(R.id.domainText)
        openBrowserBtn = findViewById(R.id.openBrowserBtn)
        openBrowserBtn.visibility = View.GONE

        val incomingLink = intent?.dataString
        if (!incomingLink.isNullOrEmpty()) {
            urlInput.setText(incomingLink)
        }

        scanButton.setOnClickListener {
            var url = urlInput.text.toString().trim()

            if (!NetworkUtils.isNetworkAvailable(this)) {
                showResult("❌ No internet connection.",
                    android.R.drawable.ic_delete,
                    android.R.color.holo_red_light, "-", null)
                return@setOnClickListener
            }

            url = normalizeUrl(url)

            if (url.isEmpty() || !Patterns.WEB_URL.matcher(url).matches()) {
                showResult("⚠️ Invalid URL format",
                    android.R.drawable.ic_dialog_alert,
                    android.R.color.holo_orange_light, "-", null)
            } else {
                checkUrlWithVirusTotal(url)
            }
        }

        openBrowserBtn.setOnClickListener {
            lastScannedUrl?.let { url ->
                val uri = Uri.parse(url)
                try {
                    val chromeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.android.chrome")
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(chromeIntent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
                    startActivity(Intent.createChooser(fallbackIntent, "Open with Browser"))
                }
            }
        }
    }

    // ✅ Normalize different formats (hxxp, no scheme, etc.)
    private fun normalizeUrl(input: String): String {
        var url = input

        if (url.startsWith("hxxp://", true)) {
            url = url.replaceFirst("hxxp://", "http://", true)
        } else if (url.startsWith("hxxps://", true)) {
            url = url.replaceFirst("hxxps://", "https://", true)
        }

        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "http://$url"
        }

        return url
    }

    private fun showResult(
        text: String,
        iconRes: Int,
        colorRes: Int,
        domain: String,
        url: String?
    ) {
        resultText.text = text
        resultIcon.setImageResource(iconRes)
        resultCard.setCardBackgroundColor(getColor(colorRes))
        domainText.text = "Domain: $domain"
        resultCard.visibility = View.VISIBLE
        lastScannedUrl = url

        openBrowserBtn.visibility =
            if (url != null && text.contains("Safe", true)) View.VISIBLE else View.GONE
    }

    private fun checkUrlWithVirusTotal(url: String) {
        Thread {
            try {
                val apiKey = "79cb539731c911688aabc98159b95561dd38619d73178137885739903e828d34"
                val apiUrl = "https://www.virustotal.com/api/v3/urls"

                val encodedUrl =
                    Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray())

                val conn = URL(apiUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("x-apikey", apiKey)
                conn.doOutput = true
                val data = "url=$url"
                DataOutputStream(conn.outputStream).use { it.writeBytes(data) }

                if (conn.responseCode != 200) {
                    runOnUiThread {
                        showResult("⚠️ API Error: ${conn.responseCode}",
                            android.R.drawable.ic_dialog_alert,
                            android.R.color.holo_orange_light, "-", url)
                    }
                    return@Thread
                }

                val analysisUrl = "https://www.virustotal.com/api/v3/urls/$encodedUrl"
                val conn2 = URL(analysisUrl).openConnection() as HttpURLConnection
                conn2.requestMethod = "GET"
                conn2.setRequestProperty("x-apikey", apiKey)

                val response2 = BufferedReader(InputStreamReader(conn2.inputStream)).use { it.readText() }
                val json = JSONObject(response2)
                val stats = json.getJSONObject("data")
                    .getJSONObject("attributes")
                    .getJSONObject("last_analysis_stats")

                val malicious = stats.optInt("malicious", 0)
                val phishing = stats.optInt("phishing", 0)
                val harmless = stats.optInt("harmless", 0)

                val host = try { URL(url).host ?: "-" } catch (e: Exception) { "-" }

                runOnUiThread {
                    when {
                        malicious > 0 || phishing > 0 -> {
                            showResult("⚠️ Malicious link detected",
                                android.R.drawable.stat_notify_error,
                                android.R.color.holo_red_light, host, url)

                            ThreatLogActivity.saveLog(this, url, "Manual")
                        }
                        harmless > 0 -> {
                            showResult("🔒 Safe link",
                                android.R.drawable.ic_lock_lock,
                                android.R.color.holo_green_light, host, url)
                        }
                        else -> {
                            showResult("ℹ️ Unknown result",
                                android.R.drawable.ic_dialog_info,
                                android.R.color.darker_gray, host, url)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showResult("❌ Error: ${e.message}",
                        android.R.drawable.ic_delete,
                        android.R.color.holo_red_light, "-", url)
                }
            }
        }.start()
    }
}