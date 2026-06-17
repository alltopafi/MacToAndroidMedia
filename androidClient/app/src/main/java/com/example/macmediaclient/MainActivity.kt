package com.example.macmediaclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import coil.load
import java.util.Locale

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
    private lateinit var progressBar: ProgressBar
    private lateinit var elapsedTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var mediaControlsLayout: LinearLayout
    private lateinit var connectionLayout: LinearLayout
    private lateinit var ipInput: EditText

    private var currentIp: String = "192.168.1.12"
    
    private var lastState = MediaStateCache()

    private data class MediaStateCache(
        val isConnected: Boolean = false,
        val isActive: Boolean = false,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val isPlaying: Boolean = false,
        val artworkBase64: String? = null,
        val position: Long = 0,
        val duration: Long = 0
    )

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("MainActivity", "Received broadcast: ${intent?.action}")
            val isConnected = intent?.getBooleanExtra(MacMediaBridgeService.EXTRA_CONNECTED, false) ?: false
            val title = intent?.getStringExtra(MacMediaBridgeService.EXTRA_TITLE)
            val artist = intent?.getStringExtra(MacMediaBridgeService.EXTRA_ARTIST)
            val album = intent?.getStringExtra(MacMediaBridgeService.EXTRA_ALBUM)
            val artworkBase64 = intent?.getStringExtra(MacMediaBridgeService.EXTRA_ARTWORK)
            val position = intent?.getLongExtra(MacMediaBridgeService.EXTRA_POSITION, 0) ?: 0L
            val duration = intent?.getLongExtra(MacMediaBridgeService.EXTRA_DURATION, 0) ?: 0L
            val isPlaying = intent?.getBooleanExtra(MacMediaBridgeService.EXTRA_IS_PLAYING, false) ?: false
            val isActive = intent?.getBooleanExtra(MacMediaBridgeService.EXTRA_IS_ACTIVE, false) ?: false

            Log.d("MainActivity", "State: isConnected=$isConnected, isActive=$isActive, title=$title")
            updateUI(isConnected, isActive, title, artist, album, artworkBase64, position, duration, isPlaying)
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
                setMargins(0, 64, 0, 32)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        // Progress Section
        val progressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 32, 0, 32)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val timeLabelsLayout = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        elapsedTimeText = TextView(this).apply {
            text = "0:00"
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
            }
        }

        totalTimeText = TextView(this).apply {
            text = "0:00"
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            }
        }

        timeLabelsLayout.addView(elapsedTimeText)
        timeLabelsLayout.addView(totalTimeText)
        
        progressContainer.addView(progressBar)
        progressContainer.addView(timeLabelsLayout)
        
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
        mediaControlsLayout.addView(progressContainer)
        mediaControlsLayout.addView(titleText)
        mediaControlsLayout.addView(artistText)
        mediaControlsLayout.addView(albumText)
        mediaControlsLayout.addView(playbackControls)
        rootLayout.addView(mediaControlsLayout)

        val stopBtn = Button(this).apply {
            text = "Stop Service"
            setOnClickListener {
                stopService(Intent(this@MainActivity, MacMediaBridgeService::class.java))
                updateUI(false, false, null, null, null, null, 0, 0, false)
            }
        }
        rootLayout.addView(stopBtn)

        setContentView(rootLayout)

        setupListeners()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                stateReceiver,
                IntentFilter(MacMediaBridgeService.ACTION_STATE_UPDATE),
                RECEIVER_EXPORTED
            )
        } else {
            // Use 0 as the flag or handle older versions that don't support the flag but require the signature if targeting S+
            // Actually for below Tiramisu, the single arg registerReceiver is usually fine unless targeting very high.
            // To be safe and compliant with lint:
            registerReceiver(
                stateReceiver,
                IntentFilter(MacMediaBridgeService.ACTION_STATE_UPDATE)
            )
        }
    }

    private fun setupListeners() {
        playPauseBtn.setOnClickListener {
            val action = if (lastState.isPlaying) "PAUSE" else "PLAY"
            startService(Intent(this, MacMediaBridgeService::class.java).apply {
                this.action = action
            })
        }
        nextBtn.setOnClickListener {
            startService(Intent(this, MacMediaBridgeService::class.java).apply { action = "NEXT" })
        }
        prevBtn.setOnClickListener {
            startService(Intent(this, MacMediaBridgeService::class.java).apply { action = "PREVIOUS" })
        }
    }

    private fun updateUI(
        isConnected: Boolean, 
        isActive: Boolean, 
        title: String?, 
        artist: String?, 
        album: String?, 
        artworkBase64: String?, 
        position: Long, 
        duration: Long, 
        isPlaying: Boolean
    ) {
        // Detect if the server is sending seconds or milliseconds
        // If duration is > 0 and < 10000, it's almost certainly seconds
        val isSeconds = duration > 0 && duration < 10000
        val posMs = if (isSeconds) position * 1000 else position
        val durMs = if (isSeconds) duration * 1000 else duration

        if (isConnected != lastState.isConnected) {
            Log.d("MainActivity", "Updating connection status: $isConnected")
            statusIndicator.setBackgroundColor(if (isConnected) Color.GREEN else Color.RED)
            statusText.text = if (isConnected) " Connected to Mac" else " Disconnected"
        }

        Log.d("MainActivity", "Current isActive: $isActive, lastState.isActive: ${lastState.isActive}")
        if (isActive != lastState.isActive) {
            Log.d("MainActivity", "Setting mediaControlsLayout visibility to: ${if (isActive) "VISIBLE" else "GONE"}")
            mediaControlsLayout.visibility = if (isActive) View.VISIBLE else View.GONE
        }

        if (isActive) {
            if (title != lastState.title) titleText.text = title ?: "No Title"
            if (artist != lastState.artist) artistText.text = artist ?: "Unknown Artist"
            if (album != lastState.album) albumText.text = album ?: "Unknown Album"
            if (isPlaying != lastState.isPlaying) playPauseBtn.text = if (isPlaying) "Pause" else "Play"
            
            // Progress Bar and Timers
            if (durMs > 0) {
                progressBar.max = durMs.toInt()
                // Ensure we use the converted posMs for progress and label
                progressBar.progress = posMs.toInt()
                elapsedTimeText.text = formatTime(posMs)
                totalTimeText.text = formatTime(durMs)
            } else {
                progressBar.progress = 0
                elapsedTimeText.text = "0:00"
                totalTimeText.text = "0:00"
            }

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

        lastState = lastState.copy(
            isConnected = isConnected, isActive = isActive, title = title, artist = artist,
            album = album, isPlaying = isPlaying, artworkBase64 = artworkBase64,
            position = position, duration = duration
        )
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateReceiver)
    }
}
