package com.example.macmediaclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import coil.load

class MainActivity : AppCompatActivity() {
    private lateinit var statusIndicator: ImageView
    private lateinit var statusText: TextView
    private lateinit var artworkImage: ImageView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var albumText: TextView
    private lateinit var playPauseBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var prevBtn: Button
    private lateinit var mediaControlsLayout: LinearLayout
    private lateinit var connectionLayout: LinearLayout
    private lateinit var ipInput: EditText

    private var currentIp: String = "192.168.1.12"
    
    // Cached state to prevent redundant updates
    private var lastState = MediaStateCache()

    private data class MediaStateCache(
        val isConnected: Boolean = false,
        val isActive: Boolean = false,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val isPlaying: Boolean = false,
        val artworkBase64: String? = null
    )

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isConnected = intent?.getBooleanExtra(MacMediaBridgeService.EXTRA_CONNECTED, false) ?: false
            val title = intent?.getStringExtra(MacMediaBridgeService.EXTRA_TITLE)
            val artist = intent?.getStringExtra(MacMediaBridgeService.EXTRA_ARTIST)
            val album = intent?.getStringExtra(MacMediaBridgeService.EXTRA_ALBUM)
            val artworkBase64 = intent?.getStringExtra(MacMediaBridgeService.EXTRA_ARTWORK)
            val isPlaying = intent?.getBooleanExtra(MacMediaBridgeService.EXTRA_IS_PLAYING, false) ?: false
            val isActive = intent?.getBooleanExtra(MacMediaBridgeService.EXTRA_IS_ACTIVE, false) ?: false

            updateUI(isConnected, isActive, title, artist, album, artworkBase64, isPlaying)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Connection Status
        val statusLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 32)
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
        rootLayout.addView(statusLayout)

        // IP Input Section
        connectionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        ipInput = EditText(this).apply {
            hint = "Mac IP Address"
            setText("192.168.1.12")
        }
        val connectBtn = Button(this).apply {
            text = "Connect to Mac"
            setOnClickListener {
                val ip = ipInput.text.toString().trim()
                if (ip.isNotBlank()) {
                    currentIp = ip
                    val intent = Intent(this@MainActivity, MacMediaBridgeService::class.java)
                    intent.putExtra("IP_ADDRESS", ip)
                    startForegroundService(intent)
                    Toast.makeText(this@MainActivity, "Connecting...", Toast.LENGTH_SHORT).show()
                }
            }
        }
        connectionLayout.addView(ipInput)
        connectionLayout.addView(connectBtn)
        rootLayout.addView(connectionLayout)

        // Media Controls Section
        mediaControlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }

        artworkImage = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(600, 600).apply {
                setMargins(0, 64, 0, 64)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        
        titleText = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        
        artistText = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
        }

        albumText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val playbackControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        prevBtn = Button(this).apply { text = "Prev" }
        playPauseBtn = Button(this).apply { text = "Play" }
        nextBtn = Button(this).apply { text = "Next" }

        playbackControls.addView(prevBtn)
        playbackControls.addView(playPauseBtn)
        playbackControls.addView(nextBtn)

        mediaControlsLayout.addView(artworkImage)
        mediaControlsLayout.addView(titleText)
        mediaControlsLayout.addView(artistText)
        mediaControlsLayout.addView(albumText)
        mediaControlsLayout.addView(playbackControls)
        rootLayout.addView(mediaControlsLayout)

        val stopBtn = Button(this).apply {
            text = "Stop Service"
            setOnClickListener {
                stopService(Intent(this@MainActivity, MacMediaBridgeService::class.java))
                updateUI(false, false, null, null, null, null, false)
            }
        }
        rootLayout.addView(stopBtn)

        setContentView(rootLayout)

        setupListeners()

        LocalBroadcastManager.getInstance(this).registerReceiver(
            stateReceiver,
            IntentFilter(MacMediaBridgeService.ACTION_STATE_UPDATE)
        )
    }

    private fun setupListeners() {
        playPauseBtn.setOnClickListener {
            startService(Intent(this, MacMediaBridgeService::class.java).apply {
                action = "TOGGLE_PLAYBACK"
            })
        }
        nextBtn.setOnClickListener {
            startService(Intent(this, MacMediaBridgeService::class.java).apply { action = "NEXT" })
        }
        prevBtn.setOnClickListener {
            startService(Intent(this, MacMediaBridgeService::class.java).apply { action = "PREVIOUS" })
        }
    }

    private fun updateUI(isConnected: Boolean, isActive: Boolean, title: String?, artist: String?, album: String?, artworkBase64: String?, isPlaying: Boolean) {
        // 1. Connection Status Update
        if (isConnected != lastState.isConnected) {
            if (isConnected) {
                statusIndicator.setBackgroundColor(Color.GREEN)
                statusText.text = " Connected to Mac"
            } else {
                statusIndicator.setBackgroundColor(Color.RED)
                statusText.text = " Disconnected"
            }
        }

        // 2. Active Session Visibility
        if (isActive != lastState.isActive) {
            mediaControlsLayout.visibility = if (isActive) View.VISIBLE else View.GONE
        }

        if (isActive) {
            // 3. Metadata Updates
            if (title != lastState.title) titleText.text = title ?: "No Title"
            if (artist != lastState.artist) artistText.text = artist ?: "Unknown Artist"
            if (album != lastState.album) albumText.text = album ?: "Unknown Album"

            // 4. Playback State
            if (isPlaying != lastState.isPlaying) {
                playPauseBtn.text = if (isPlaying) "Pause" else "Play"
            }
            
            // 5. Artwork Update (from WebSocket Base64)
            if (artworkBase64 != null && artworkBase64 != lastState.artworkBase64) {
                try {
                    val decodedString = android.util.Base64.decode(artworkBase64, android.util.Base64.DEFAULT)
                    artworkImage.load(decodedString) {
                        crossfade(true)
                        placeholder(android.R.drawable.ic_menu_report_image)
                        error(android.R.drawable.ic_menu_report_image)
                    }
                } catch (e: Exception) {
                    artworkImage.setImageResource(android.R.drawable.ic_menu_report_image)
                }
            }
        }

        // Cache the current state
        lastState = lastState.copy(
            isConnected = isConnected,
            isActive = isActive,
            title = title,
            artist = artist,
            album = album,
            isPlaying = isPlaying,
            artworkBase64 = artworkBase64
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
    }
}
