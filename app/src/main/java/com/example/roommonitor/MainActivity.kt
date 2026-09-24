package com.example.roommonitor

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val port = 5000
    private val pollMs = 2000L
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var baseUrl: String? = null
    private var polling = false

    private lateinit var ipInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var status: TextView
    private lateinit var roomTemp: TextView
    private lateinit var humidity: TextView
    private lateinit var userTemp: TextView
    private lateinit var updated: TextView

    private val pollRunnable = object : Runnable {
        override fun run() {
            val url = baseUrl ?: return
            io.execute {
                val result = runCatching { fetch(url) }
                ui.post {
                    if (!polling) return@post
                    result.onSuccess { render(it) }
                        .onFailure { status.text = "Connection failed: ${it.message}" }
                }
            }
            ui.postDelayed(this, pollMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipInput = findViewById(R.id.ipInput)
        connectBtn = findViewById(R.id.connectBtn)
        status = findViewById(R.id.status)
        roomTemp = findViewById(R.id.roomTemp)
        humidity = findViewById(R.id.humidity)
        userTemp = findViewById(R.id.userTemp)
        updated = findViewById(R.id.updated)

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        ipInput.setText(prefs.getString("ip", ""))

        connectBtn.setOnClickListener {
            val ip = ipInput.text.toString().trim()
            if (ip.isEmpty()) {
                status.text = "Enter the Pi's IP address"
                return@setOnClickListener
            }
            prefs.edit().putString("ip", ip).apply()
            stopPolling()
            baseUrl = "http://$ip:$port/data"
            status.text = "Connecting to $ip..."
            startPolling()
        }
    }

    private fun startPolling() {
        polling = true
        ui.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        ui.removeCallbacks(pollRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (baseUrl != null && !polling) startPolling()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    private fun fetch(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        try {
            return JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun fmt(o: JSONObject, key: String, unit: String): String =
        if (o.isNull(key)) "--" else "${o.getDouble(key)}$unit"

    private fun render(o: JSONObject) {
        roomTemp.text = fmt(o, "room_temp", " °C")
        humidity.text = fmt(o, "humidity", " %")
        userTemp.text = fmt(o, "user_temp", " °C")
        updated.text = "Last update: ${o.optString("timestamp", "--")}"

        val errs = o.optJSONObject("errors")
        status.text = if (errs != null && errs.length() > 0) {
            "Connected, sensor issue: " + errs.keys().asSequence().joinToString { "$it (${errs.getString(it)})" }
        } else {
            "Connected"
        }
    }
}
