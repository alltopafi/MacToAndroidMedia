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
import android.os.Build
import android.content.Context
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import androidx.media3.common.ForwardingPlayer
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

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
        private const val CHANNEL_ID = "mac_connection_channel"
        private const val NOTIFICATION_ID = 1001
    }
    private var mediaSession: MediaSession? = null
    private lateinit var dummyPlayer: ExoPlayer
    private var macClient: MacMediaClient? = null
    private var currentIp: String = "Jesses-MacBook-Pro.local"
    private var lastIsPlaying: Boolean = false
    private var wasConnected: Boolean = false
    private var lastArtworkBase64: String? = null
    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var lastAlbum: String? = null
    private var lastArtworkId: Int = 0 // Track unique artwork occurrences
    // Valid silent WAV data URI to avoid resource corruption issues
    private val SILENT_URI = Uri.parse("data:audio/wav;base64,UklGRiwAAABXQVZFZm10IBAAAAABAAEARKwAAESsAAABAAgAZGF0YQgAAACAgICAgICAgA==")

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.d("MacMediaBridgeService", "onCreate called")
        dummyPlayer = ExoPlayer.Builder(this).build()
        
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        dummyPlayer.setAudioAttributes(audioAttributes, true)
        dummyPlayer.repeatMode = Player.REPEAT_MODE_ONE // Keep the silent track looping
        
        // Initial setup to ensure the player has a timeline and media item immediately
        val initMediaItem = MediaItem.Builder()
            .setMediaId("mac_media_init")
            .setUri(SILENT_URI)
            .setMimeType(MimeTypes.AUDIO_WAV)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle("Connecting to Mac...")
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build())
            .build()
        dummyPlayer.setMediaItem(initMediaItem)
        dummyPlayer.prepare()
        dummyPlayer.playWhenReady = true // Start playing the silent track to trigger the media notification

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
            override fun getDuration(): Long {
                return macClient?.lastState?.duration?.let { dur ->
                    if (dur > 0 && dur < 10000) dur * 1000 else dur
                } ?: super.getDuration()
            }

            override fun getCurrentPosition(): Long {
                return macClient?.lastState?.let { state ->
                    val dur = state.duration
                    val pos = state.position
                    if (dur in 1..9999) pos * 1000 else pos
                } ?: super.getCurrentPosition()
            }

            override fun isPlaying(): Boolean {
                return macClient?.lastState?.isPlaying ?: super.isPlaying()
            }

            override fun getPlayWhenReady(): Boolean {
                return macClient?.lastState?.isPlaying ?: super.getPlayWhenReady()
            }

            override fun getPlaybackState(): Int {
                return if (macClient?.lastState?.isActive == true) STATE_READY else STATE_IDLE
            }

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
            override fun stop() {
                macClient?.disconnect()
                stopSelf()
            }
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(COMMAND_PLAY_PAUSE)
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_STOP)
                    .add(COMMAND_GET_METADATA)
                    .add(COMMAND_GET_TIMELINE)
                    .build()
            }

            override fun getMediaMetadata(): MediaMetadata {
                return dummyPlayer.mediaMetadata
            }
        }
        
        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(this, 0, sessionActivityIntent, PendingIntent.FLAG_IMMUTABLE)
        
        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
        
        mediaSession?.let { addSession(it) }
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
        Log.d("MacMediaBridgeService", "onStartCommand action: ${intent?.action}")
        when (intent?.action) {
            "TOGGLE_PLAYBACK" -> {
                if (dummyPlayer.playWhenReady) {
                    macClient?.postCommand("pause")
                } else {
                    macClient?.postCommand("play")
                }
            }
            "PLAY" -> {
                Log.d("MacMediaBridgeService", "Handling PLAY command")
                macClient?.postCommand("play")
            }
            "PAUSE" -> {
                Log.d("MacMediaBridgeService", "Handling PAUSE command")
                macClient?.postCommand("pause")
            }
            "NEXT" -> {
                Log.d("MacMediaBridgeService", "Handling NEXT command")
                macClient?.postCommand("next")
            }
            "PREVIOUS" -> {
                Log.d("MacMediaBridgeService", "Handling PREVIOUS command")
                macClient?.postCommand("previous")
            }
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
        wasConnected = false
        lastArtworkBase64 = null
        lastTitle = null
        lastArtist = null
        lastAlbum = null
        
        macClient = MacMediaClient(currentIp) { state ->
            // Skip redundant processing if metadata and play state haven't changed
            // We still want to send state updates for progress (position)
            
            if (state.isConnected && !wasConnected) {
                showConnectionNotification()
            }
            wasConnected = state.isConnected

            // Update metadata tracking IMMEDIATELY to avoid redundant large broadcasts
            val titleChanged = state.title != lastTitle
            val artistChanged = state.artist != lastArtist
            val albumChanged = state.album != lastAlbum
            val artworkChanged = state.artworkBase64 != lastArtworkBase64
            val playingChanged = state.isPlaying != lastIsPlaying

            if (artworkChanged) {
                lastArtworkBase64 = state.artworkBase64
                lastArtworkId++ // Increment to signal new artwork
            }

            // Send state update BEFORE runOnUiThread
            sendStateUpdate(state, artworkChanged)

            if (!titleChanged && !artistChanged && !albumChanged && !artworkChanged && !playingChanged) {
                // Check if we need to update player activity status even if metadata is same
                runOnUiThread {
                    val playerIsActive = dummyPlayer.playbackState != Player.STATE_IDLE
                    if (state.isActive != playerIsActive) {
                        if (!state.isActive) dummyPlayer.pause()
                    }
                }
                return@MacMediaClient
            }

            lastTitle = state.title
            lastArtist = state.artist
            lastAlbum = state.album
            lastIsPlaying = state.isPlaying
            if (artworkChanged) {
                lastArtworkBase64 = state.artworkBase64
            }

            // Heavy lifting in background: decode artwork
            var decodedArtwork: ByteArray? = null
            if (artworkChanged) {
                state.artworkBase64?.let { base64 ->
                    try {
                        decodedArtwork = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    } catch (e: Exception) {
                        Log.e("MacMediaBridgeService", "Failed to decode artwork", e)
                    }
                }
            }

            runOnUiThread {
                // Player access must be on the main thread
                if (!state.isActive) {
                    if (dummyPlayer.playbackState != Player.STATE_IDLE) {
                        dummyPlayer.pause()
                    }
                    return@runOnUiThread
                }

                val currentItem = dummyPlayer.currentMediaItem
                val needsNewItem = currentItem?.mediaId != "mac_media" || titleChanged || artistChanged || albumChanged || artworkChanged

                if (needsNewItem) {
                    val metadataBuilder = MediaMetadata.Builder()
                        .setTitle(state.title ?: "Unknown Title")
                        .setArtist(state.artist ?: "Unknown Artist")
                        .setAlbumTitle(state.album ?: "Unknown Album")
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    
                    if (artworkChanged) {
                        metadataBuilder.setArtworkData(decodedArtwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    } else {
                        // Keep existing artwork data if only text changed
                        currentItem?.mediaMetadata?.artworkData?.let {
                            metadataBuilder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        }
                    }

                    val mediaItem = MediaItem.Builder()
                        .setMediaId("mac_media")
                        .setMediaMetadata(metadataBuilder.build())
                        .setUri(SILENT_URI)
                        .setMimeType(MimeTypes.AUDIO_WAV)
                        .build()

                    if (currentItem?.mediaId != "mac_media") {
                        dummyPlayer.setMediaItem(mediaItem)
                        dummyPlayer.prepare()
                    } else {
                        dummyPlayer.replaceMediaItem(0, mediaItem)
                    }
                }

                if (state.isPlaying != dummyPlayer.playWhenReady) {
                    dummyPlayer.playWhenReady = state.isPlaying
                }
                
                if (state.isPlaying) {
                    if (dummyPlayer.playbackState == Player.STATE_IDLE) {
                        dummyPlayer.prepare()
                    }
                    dummyPlayer.play()
                } else {
                    dummyPlayer.pause()
                }
            }
        }
        
        macClient?.connect()
        // Send initial state update to ensure UI knows we're starting to connect
        sendStateUpdate(MediaState(isConnected = false, isActive = false), artworkChanged = false)
    }
    
    private fun sendStateUpdate(state: MediaState, artworkChanged: Boolean) {
        val intent = Intent(ACTION_STATE_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_CONNECTED, state.isConnected)
            putExtra(EXTRA_TITLE, state.title)
            putExtra(EXTRA_ARTIST, state.artist)
            putExtra(EXTRA_ALBUM, state.album)
            
            // Only send artwork if it's new and small enough, or compress it
            if (artworkChanged && state.artworkBase64 != null) {
                val artwork = state.artworkBase64
                if (artwork.length > 100_000) { // If > 100KB base64
                    try {
                        val bytes = android.util.Base64.decode(artwork, android.util.Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            val stream = ByteArrayOutputStream()
                            // Shrink it down for the UI preview
                            val scaled = Bitmap.createScaledBitmap(bitmap, 400, 400, true)
                            scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                            val compressedBase64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.DEFAULT)
                            putExtra(EXTRA_ARTWORK, compressedBase64)
                        }
                    } catch (e: Exception) {
                        Log.e("MacMediaBridgeService", "Failed to compress artwork for broadcast", e)
                    }
                } else {
                    putExtra(EXTRA_ARTWORK, artwork)
                }
            }

            putExtra(EXTRA_POSITION, state.position)
            putExtra(EXTRA_DURATION, state.duration)
            putExtra(EXTRA_IS_PLAYING, state.isPlaying)
            putExtra(EXTRA_IS_ACTIVE, state.isActive)
        }
        sendBroadcast(intent)
    }

    private fun showConnectionNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mac Connection Status",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // Using a generic system icon
            .setContentTitle(getString(R.string.notification_connected_title))
            .setContentText(getString(R.string.notification_connected_content, currentIp))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun runOnUiThread(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        Log.d("MacMediaBridgeService", "onGetSession called for ${controllerInfo.packageName}")
        return mediaSession
    }

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
