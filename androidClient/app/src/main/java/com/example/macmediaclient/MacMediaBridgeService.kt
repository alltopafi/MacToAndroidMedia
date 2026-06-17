package com.example.macmediaclient

import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MacMediaBridgeService : MediaSessionService() {
    companion object {
        const val ACTION_CONNECTION_STATUS = "com.example.macmediaclient.CONNECTION_STATUS"
        const val EXTRA_CONNECTED = "connected"
    }
    private var mediaSession: MediaSession? = null
    private lateinit var dummyPlayer: ExoPlayer
    private var macClient: MacMediaClient? = null
    private var currentIp: String = "192.168.1.12"

    override fun onCreate() {
        super.onCreate()
        Log.d("MacMediaBridgeService", "onCreate called")
        dummyPlayer = ExoPlayer.Builder(this).build()
        
        dummyPlayer.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    if (playWhenReady) macClient?.postCommand("play")
                    else macClient?.postCommand("pause")
                }
            }
        })
        
        mediaSession = MediaSession.Builder(this, dummyPlayer).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ip = intent?.getStringExtra("IP_ADDRESS")
        Log.d("MacMediaBridgeService", "onStartCommand with IP: $ip")
        ip?.let {
            currentIp = it
            startClient()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startClient() {
        Log.d("MacMediaBridgeService", "Starting client for IP: $currentIp")
        macClient?.disconnect()
        macClient = MacMediaClient(currentIp) { state ->
            Log.d("MacMediaBridgeService", "State changed: isConnected=${state.isConnected}, title=${state.title}")
            val intent = Intent(ACTION_CONNECTION_STATUS).apply {
                putExtra(EXTRA_CONNECTED, state.isConnected)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

            runOnUiThread {
                if (!state.isActive) {
                    dummyPlayer.pause()
                    return@runOnUiThread
                }

                val cleanIp = currentIp.trim()
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .removePrefix("ws://")
                    .removePrefix("wss://")
                    .removeSuffix("/")
                
                val hostWithPort = if (cleanIp.contains(":")) cleanIp else "$cleanIp:8000"
                val artworkUrl = "http://$hostWithPort/api/artwork?t=${System.currentTimeMillis()}"
                
                val metadata = MediaMetadata.Builder()
                    .setTitle(state.title ?: "Unknown Title")
                    .setArtist(state.artist ?: "Unknown Artist")
                    .setAlbumTitle(state.album ?: "Unknown Album")
                    .setArtworkUri(Uri.parse(artworkUrl))
                    .build()

                val silentUri = Uri.parse("android.resource://${packageName}/${R.raw.silent}")
                val mediaItem = MediaItem.Builder()
                    .setMediaId("mac_media")
                    .setMediaMetadata(metadata)
                    .setUri(silentUri)
                    .build()

                if (dummyPlayer.currentMediaItem?.mediaMetadata?.title != metadata.title) {
                    dummyPlayer.setMediaItem(mediaItem)
                    dummyPlayer.prepare()
                } else {
                    dummyPlayer.replaceMediaItem(0, mediaItem)
                }

                if (state.isPlaying) {
                    dummyPlayer.play()
                } else {
                    dummyPlayer.pause()
                }
            }
        }
        macClient?.connect()
    }
    
    private fun runOnUiThread(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        macClient?.disconnect()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
