package com.example.macmediaclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {
    private lateinit var statusIndicator: ImageView
    private lateinit var statusText: TextView

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isConnected = intent?.getBooleanExtra(MacMediaBridgeService.EXTRA_CONNECTED, false) ?: false
            Log.d("MainActivity", "Received connection status broadcast: isConnected=$isConnected")
            updateStatus(isConnected)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        
        val statusLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 64)
        }

        statusIndicator = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(40, 40)
            setBackgroundColor(Color.GRAY)
        }

        statusText = TextView(this).apply {
            text = " Disconnected"
            textSize = 18f
        }

        statusLayout.addView(statusIndicator)
        statusLayout.addView(statusText)
        
        val ipInput = EditText(this).apply {
            hint = "Mac IP Address (e.g. 192.168.1.12)"
            setText("192.168.1.12")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        val connectBtn = Button(this).apply {
            text = "Connect to Mac"
            setOnClickListener {
                val ip = ipInput.text.toString().trim()
                Log.d("MainActivity", "Connect button clicked with IP: $ip")
                if (ip.isNotBlank()) {
                    val intent = Intent(this@MainActivity, MacMediaBridgeService::class.java)
                    intent.putExtra("IP_ADDRESS", ip)
                    startForegroundService(intent)
                    Toast.makeText(this@MainActivity, "Connecting...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val stopBtn = Button(this).apply {
            text = "Stop Service"
            setOnClickListener {
                stopService(Intent(this@MainActivity, MacMediaBridgeService::class.java))
                updateStatus(false)
            }
        }
        
        layout.addView(statusLayout)
        layout.addView(ipInput)
        layout.addView(connectBtn)
        layout.addView(stopBtn)
        setContentView(layout)

        LocalBroadcastManager.getInstance(this).registerReceiver(
            connectionReceiver,
            IntentFilter(MacMediaBridgeService.ACTION_CONNECTION_STATUS)
        )
    }

    private fun updateStatus(isConnected: Boolean) {
        if (isConnected) {
            statusIndicator.setBackgroundColor(Color.GREEN)
            statusText.text = " Connected to Mac"
        } else {
            statusIndicator.setBackgroundColor(Color.RED)
            statusText.text = " Disconnected"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(connectionReceiver)
    }
}
