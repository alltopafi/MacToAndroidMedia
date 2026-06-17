package com.example.macmediaclient

import android.util.Log
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class MediaState(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkBase64: String? = null,
    val position: Long = 0,
    val duration: Long = 0,
    val isPlaying: Boolean = false,
    val isActive: Boolean = false,
    val isConnected: Boolean = false
)

class MacMediaClient(private val ipAddress: String, private val onStateChanged: (MediaState) -> Unit) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // For WebSocket
        .writeTimeout(5, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY) // Avoid issues with system proxies for local connections
        .build()
        
    private var webSocket: WebSocket? = null
    private var lastState = MediaState()

    fun isConnected(): Boolean = lastState.isConnected

    private fun getBaseUrl(protocol: String): String {
        val trimmed = ipAddress.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("ws://")
            .removePrefix("wss://")
            .removeSuffix("/")
        
        return if (trimmed.contains(":")) {
            "$protocol://$trimmed"
        } else {
            "$protocol://$trimmed:8000"
        }
    }

    fun connect() {
        val url = getBaseUrl("ws") + "/api/ws"
        Log.d("MacMediaClient", "Attempting to connect to: $url")
        
        val request = Request.Builder()
            .url(url)
            .build()
            
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("MacMediaClient", "WebSocket Opened successfully")
                lastState = lastState.copy(isConnected = true)
                onStateChanged(lastState)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.v("MacMediaClient", "Received message: $text")
                try {
                    val json = JSONObject(text)
                    lastState = lastState.copy(
                        title = json.optString("title").takeIf { it != "null" && it.isNotEmpty() },
                        artist = json.optString("artist").takeIf { it != "null" && it.isNotEmpty() },
                        album = json.optString("album").takeIf { it != "null" && it.isNotEmpty() },
                        artworkBase64 = json.optString("artwork").takeIf { it != "null" && it.isNotEmpty() },
                        position = json.optLong("elapsed_time", json.optLong("position", json.optLong("elapsed", 0))),
                        duration = json.optLong("duration", 0),
                        isPlaying = json.optBoolean("is_playing", false),
                        isActive = json.optBoolean("is_active", false),
                        isConnected = true
                    )
                    onStateChanged(lastState)
                } catch (e: Exception) {
                    Log.e("MacMediaClient", "Failed to parse JSON", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("MacMediaClient", "WebSocket Closing: $code / $reason")
                lastState = lastState.copy(isConnected = false)
                onStateChanged(lastState)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("MacMediaClient", "WebSocket Failure: ${t.message}", t)
                response?.let {
                    Log.e("MacMediaClient", "Response code: ${it.code}, message: ${it.message}")
                }
                lastState = lastState.copy(isConnected = false)
                onStateChanged(lastState)
            }
        })
    }

    fun disconnect() {
        Log.d("MacMediaClient", "Disconnecting WebSocket")
        webSocket?.close(1000, "App closed")
        webSocket = null
        lastState = lastState.copy(isConnected = false)
        onStateChanged(lastState)
    }

    fun postCommand(action: String) {
        val url = getBaseUrl("http") + "/api/control/$action"
        Log.d("MacMediaClient", "Sending command: $url")
        
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .build()
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("MacMediaClient", "Failed to send command $action", e)
            }
            override fun onResponse(call: Call, response: Response) {
                Log.d("MacMediaClient", "Command $action response: ${response.code}")
                response.close()
            }
        })
    }
}
