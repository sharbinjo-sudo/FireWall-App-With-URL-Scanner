package com.vpn.fwwithmlb

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import org.json.JSONArray
import org.json.JSONObject

class ThreatLogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ThreatLogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_threat_log)

        val toolbar: MaterialToolbar = findViewById(R.id.threatLogToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Threat Log"

        recyclerView = findViewById(R.id.threatLogRecycler)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = ThreatLogAdapter(loadLogs(this))
        recyclerView.adapter = adapter
    }

    companion object {
        fun saveLog(context: Context, url: String, source: String) {
            val prefs = context.getSharedPreferences("threat_log", Context.MODE_PRIVATE)
            val logs = JSONArray(prefs.getString("logs", "[]"))

            // ✅ Save as proper JSONObject
            val logEntry = JSONObject().apply {
                put("url", url)
                put("source", source)
                put("time", System.currentTimeMillis())
            }

            logs.put(logEntry)
            prefs.edit().putString("logs", logs.toString()).apply()
        }

        fun loadLogs(context: Context): List<ThreatLogItem> {
            val prefs = context.getSharedPreferences("threat_log", Context.MODE_PRIVATE)
            val logs = JSONArray(prefs.getString("logs", "[]"))
            val list = mutableListOf<ThreatLogItem>()

            for (i in 0 until logs.length()) {
                try {
                    val obj = logs.getJSONObject(i)
                    list.add(
                        ThreatLogItem(
                            url = obj.getString("url"),
                            source = obj.getString("source"),
                            time = obj.getLong("time")
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace() // In case of corrupted/old data
                }
            }

            return list.reversed() // latest first
        }

        fun clearLogs(context: Context) {
            val prefs = context.getSharedPreferences("threat_log", Context.MODE_PRIVATE)
            prefs.edit().putString("logs", "[]").apply()
        }
    }
}