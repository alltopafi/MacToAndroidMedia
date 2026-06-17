package com.example.macmediaclient

import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.CommandButton
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import androidx.media3.common.ForwardingPlayer

class MacMediaBridgeService : MediaSessionService() {
    companion object {
        const val ACTION_STATE_UPDATE = "com.example.macmediaclient.STATE_UPDATE"
        const val ACTION_CONNECTION_STATUS = "com.example.macmediaclient.CONNECTION_STATUS"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_ARTWORK = "artwork"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_IS_ACTIVE = "is_active"
    }
    private var mediaSession: MediaSession? = null
    private lateinit var dummyPlayer: ExoPlayer
    private var macClient: MacMediaClient? = null
    private var currentIp: String = "192.168.1.12"
    private var lastIsPlaying: Boolean = false
    // Valid silent WAV
    private val SILENT_URI = Uri.parse("data:audio/wav;base64,UklGRjIAAABXQVZFZm10IBAAAAABAAEAIlYAAESsAAACABAAZGF0YRAAAAAAAAAAAAAAAAAAAAAAAAAA")

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.d("MacMediaBridgeService", "onCreate called")
        dummyPlayer = ExoPlayer.Builder(this).build()
        dummyPlayer.repeatMode = Player.REPEAT_MODE_ONE // Keep the silent track looping
        
        dummyPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("MacMediaBridgeService", "Player error: ${error.errorCodeName} (${error.errorCode}): ${error.message}", error)
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED || 
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
                    
                    Log.d("MacMediaBridgeService", "Attempting recovery...")
                    Handler(Looper.getMainLooper()).postDelayed({
                        dummyPlayer.prepare()
                        if (macClient?.isConnected() == true && lastIsPlaying) {
                            dummyPlayer.play()
                        }
                    }, 1000)
                }
            }
        })
        
        val forwardingPlayer = @UnstableApi object : ForwardingPlayer(dummyPlayer) {
            override fun seekToNext() {
                macClient?.postCommand("next")
            }
            override fun seekToPrevious() {
                macClient?.postCommand("previous")
            }
            override fun play() {
                macClient?.postCommand("play")
            }
            override fun pause() {
                macClient?.postCommand("pause")
            }
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(COMMAND_PLAY_PAUSE)
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .build()
            }
        }
        
        mediaSession = MediaSession.Builder(this, forwardingPlayer).build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null) {
            if (!player.playWhenReady || player.mediaItemCount == 0) {
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "TOGGLE_PLAYBACK" -> {
                if (dummyPlayer.playWhenReady) {
                    macClient?.postCommand("pause")
                } else {
                    macClient?.postCommand("play")
                }
            }
            "PLAY" -> macClient?.postCommand("play")
            "PAUSE" -> macClient?.postCommand("pause")
            "NEXT" -> macClient?.postCommand("next")
            "PREVIOUS" -> macClient?.postCommand("previous")
            else -> {
                val ip = intent?.getStringExtra("IP_ADDRESS")
                Log.d("MacMediaBridgeService", "onStartCommand with IP: $ip")
                ip?.let {
                    currentIp = it
                    startClient()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startClient() {
        Log.d("MacMediaBridgeService", "Starting client for IP: $currentIp")
        macClient?.disconnect()
        macClient = MacMediaClient(currentIp) { state ->
            Log.d("MacMediaBridgeService", "State changed: isConnected=${state.isConnected}, title=${state.title}")
            
            sendStateUpdate(state)

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
                
                val metadata = MediaMetadata.Builder()
                    .setTitle(state.title ?: "Unknown Title")
                    .setArtist(state.artist ?: "Unknown Artist")
                    .setAlbumTitle(state.album ?: "Unknown Album")
                
                state.artworkBase64?.let { base64 ->
                    try {
                        val decodedString = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                        metadata.setArtworkData(decodedString, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    } catch (e: Exception) {
                        Log.e("MacMediaBridgeService", "Failed to decode artwork", e)
                    }
                }

                val builtMetadata = metadata.build()

                val mediaItem = MediaItem.Builder()
                    .setMediaId("mac_media")
                    .setMediaMetadata(builtMetadata)
                    .setUri(SILENT_URI)
                    .build()

                val currentItem = dummyPlayer.currentMediaItem
                if (currentItem?.mediaId != "mac_media") {
                    dummyPlayer.setMediaItem(mediaItem)
                    dummyPlayer.prepare()
                } else if (currentItem.mediaMetadata.title != builtMetadata.title) {
                    // Only replace if metadata changed significantly
                    dummyPlayer.replaceMediaItem(0, mediaItem)
                }

                lastIsPlaying = state.isPlaying
                if (state.isPlaying) {
                    dummyPlayer.playWhenReady = true
                    dummyPlayer.play()
                } else {
                    dummyPlayer.playWhenReady = false
                    dummyPlayer.pause()
                }
            }
        }
        
        // Initial setup to ensure the player has a timeline, which helps the notification appear
        val mediaItem = MediaItem.Builder()
            .setMediaId("mac_media_init")
            .setUri(SILENT_URI)
            .setMimeType(MimeTypes.AUDIO_WAV)
            .build()
        dummyPlayer.setMediaItem(mediaItem)
        dummyPlayer.prepare()

        macClient?.connect()
        // Send initial state update to ensure UI knows we're connecting
        macClient?.let { sendStateUpdate(MediaState(isConnected = false)) }
    }
    
    private fun sendStateUpdate(state: MediaState) {
        val intent = Intent(ACTION_STATE_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_CONNECTED, state.isConnected)
            putExtra(EXTRA_TITLE, state.title)
            putExtra(EXTRA_ARTIST, state.artist)
            putExtra(EXTRA_ALBUM, state.album)
            putExtra(EXTRA_ARTWORK, state.artworkBase64)
            putExtra(EXTRA_POSITION, state.position)
            putExtra(EXTRA_DURATION, state.duration)
            putExtra(EXTRA_IS_PLAYING, state.isPlaying)
            putExtra(EXTRA_IS_ACTIVE, state.isActive)
        }
        sendBroadcast(intent)
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
