package com.example.roommonitor

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
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
    private lateinit var statusDot: View
    private lateinit var roomTemp: TextView
    private lateinit var humidity: TextView
    private lateinit var userTemp: TextView
    private lateinit var updated: TextView
    private lateinit var issue: TextView

    private val pollRunnable = object : Runnable {
        override fun run() {
            val url = baseUrl ?: return
            io.execute {
                val result = runCatching { fetch(url) }
                ui.post {
                    if (!polling) return@post
                    result.onSuccess { render(it) }
                        .onFailure { setStatus("No signal", R.color.bad) }
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
        statusDot = findViewById(R.id.statusDot)
        roomTemp = findViewById(R.id.roomTemp)
        humidity = findViewById(R.id.humidity)
        userTemp = findViewById(R.id.userTemp)
        updated = findViewById(R.id.updated)
        issue = findViewById(R.id.issue)

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        ipInput.setText(prefs.getString("ip", ""))

        connectBtn.setOnClickListener {
            val ip = ipInput.text.toString().trim()
            if (ip.isEmpty()) {
                setStatus("Enter an IP", R.color.bad)
                return@setOnClickListener
            }
            prefs.edit().putString("ip", ip).apply()
            stopPolling()
            baseUrl = "http://$ip:$port/data"
            setStatus("Connecting", R.color.muted)
            startPolling()
        }
    }

    private fun setStatus(text: String, colorRes: Int) {
        status.text = text
        val c = ContextCompat.getColor(this, colorRes)
        ViewCompat.setBackgroundTintList(statusDot, android.content.res.ColorStateList.valueOf(c))
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

    private fun num(o: JSONObject, key: String): String =
        if (o.isNull(key)) "--" else String.format(Locale.US, "%.1f", o.getDouble(key))

    private fun render(o: JSONObject) {
        roomTemp.text = num(o, "room_temp")
        humidity.text = num(o, "humidity")
        userTemp.text = num(o, "user_temp")
        updated.text = o.optString("timestamp", "")

        val errs = o.optJSONObject("errors")
        if (errs != null && errs.length() > 0) {
            setStatus("Live", R.color.ok)
            issue.text = errs.keys().asSequence().joinToString("\n") { "${it.uppercase()}: ${errs.getString(it)}" }
            issue.visibility = View.VISIBLE
        } else {
            setStatus("Live", R.color.ok)
            issue.visibility = View.GONE
        }
    }
}
