package com.watchtogether.app.network

import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RelayClient(
    private val client: OkHttpClient,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onState(data: JSONObject)
        fun onReaction(emoji: String)
        fun onChat(senderName: String, text: String, isSelf: Boolean)
        fun onVoice(audioBase64: String)
        fun onVoiceChunk(audioBase64: String, isSpeaking: Boolean)
        fun onReadChunk(request: JSONObject)
        fun onError(message: String)
        fun onRoomExpired(wasHost: Boolean)
        fun onHostClosed()
        fun onNote(message: String)
        fun onLeftRoom()
    }

    private val ui = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newFixedThreadPool(4)

    @Volatile var socket: WebSocket? = null
        private set

    var connected = false
        private set
    var connecting = false
        private set
    var host = false
        private set
    var code = ""
        private set
    var token = ""
        private set
    var base = ""
        private set
    var invite = ""
        private set

    private var reconnectAttempts = 0
    private var isDestroyed = false
    private var generation = 0

    private val reconnectTask = Runnable {
        if (!isDestroyed && token.isNotEmpty() && !connected) {
            connect(isReconnect = true)
        }
    }

    fun createRoom(
        serverEndpoint: String,
        creatorKey: String,
        fileSize: Long,
        mimeType: String,
        onSuccess: (code: String, invite: String) -> Unit,
        onFailure: (error: String) -> Unit
    ) {
        base = serverEndpoint
        connecting = true
        host = true
        val attempt = ++generation
        val body = JSONObject().put("size", fileSize).put("mime", mimeType).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$base/rooms").header("Authorization", "Bearer $creatorKey").post(body).build()

        worker.execute {
            try {
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Server returned ${response.code}" }
                    val data = JSONObject(response.body!!.string())
                    ui.post {
                        if (!isDestroyed && connecting && generation == attempt) {
                            code = data.getString("code")
                            token = data.getString("hostToken")
                            invite = data.getString("invite")
                            connect()
                            onSuccess(code, invite)
                        }
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    if (generation == attempt) {
                        connecting = false
                        onFailure(e.message ?: "Connection failed")
                    }
                }
            }
        }
    }

    fun joinRoom(
        serverEndpoint: String,
        roomCode: String,
        joinToken: String,
        onSuccess: (mime: String, size: Long) -> Unit,
        onFailure: (error: String) -> Unit
    ) {
        base = serverEndpoint
        code = roomCode
        host = false
        connecting = true
        val attempt = ++generation
        val request = Request.Builder().url("$base/rooms/$code/join")
            .header("Authorization", "Bearer $joinToken")
            .post(ByteArray(0).toRequestBody(null)).build()

        worker.execute {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 409) {
                        throw IllegalStateException("Host is still connecting. Please wait a moment and tap Join again.")
                    }
                    check(response.isSuccessful) { "Invite expired, used, or unavailable (${response.code}). Ask host for a new room." }
                    val json = JSONObject(response.body!!.string())
                    val guestToken = json.getString("guestToken")
                    val m = json.optString("mime", "")
                    val s = json.optLong("size", 0L)
                    ui.post {
                        if (!isDestroyed && connecting && generation == attempt) {
                            token = guestToken
                            connect()
                            onSuccess(m, s)
                        }
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    if (generation == attempt) {
                        connecting = false
                        onFailure(e.message ?: "Join failed")
                    }
                }
            }
        }
    }

    fun connect(isReconnect: Boolean = false) {
        val reconnectParam = if (isReconnect || reconnectAttempts > 0 || token.isNotEmpty()) "?reconnect=1" else ""
        val wsUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/rooms/$code/ws$reconnectParam"
        val request = Request.Builder().url(wsUrl).header("Authorization", "Bearer $token").build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ui.post {
                    if (!isDestroyed && socket === webSocket) {
                        reconnectAttempts = 0
                        ui.removeCallbacks(reconnectTask)
                        connected = true
                        connecting = false
                        listener.onConnected()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JSONObject(text)
                    if (data.optString("type") == "read" && host && socket === webSocket) {
                        listener.onReadChunk(data)
                    } else ui.post {
                        if (socket === webSocket) when (data.optString("type")) {
                            "state" -> listener.onState(data)
                            "reaction" -> listener.onReaction(data.optString("emoji", "🍿"))
                            "chat" -> {
                                val textMsg = data.optString("text")
                                val sender = data.optString("sender")
                                val senderName = data.optString("name", if (sender == "host") "Host" else "Friend")
                                val isSelf = (host && sender == "host") || (!host && sender == "guest")
                                listener.onChat(senderName, textMsg, isSelf)
                            }
                            "voice" -> {
                                val audio = data.optString("audio")
                                if (audio.isNotEmpty()) listener.onVoice(audio)
                            }
                            "voice_chunk" -> {
                                val audio = data.optString("audio")
                                val isSpeaking = data.optBoolean("speaking", false)
                                listener.onVoiceChunk(audio, isSpeaking)
                            }
                            "error" -> listener.onError(data.optString("message"))
                        }
                    }
                } catch (_: Exception) {
                    ui.post { listener.onNote("Invalid server message") }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ui.post {
                    if (socket === webSocket) {
                        val is404 = response?.code == 404 || t.message?.contains("404") == true
                        val is409 = response?.code == 409 || t.message?.contains("409") == true
                        if (is404) {
                            val wasHost = host
                            leave()
                            listener.onRoomExpired(wasHost)
                        } else if (token.isNotEmpty() && !isDestroyed) {
                            val msg = if (is409) "Server synchronizing session" else (t.message ?: "network connection lost")
                            scheduleReconnect(msg, is409 = is409)
                        } else {
                            leave()
                            listener.onNote("Connection lost: ${t.message}. Create or join a room again.")
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, codeVal: Int, reason: String) {
                ui.post {
                    if (socket === webSocket) {
                        socket = null
                        if (codeVal == 1000 && reason == "Left room") {
                            leave()
                            listener.onLeftRoom()
                        } else if (codeVal == 1001 && reason.contains("closed", ignoreCase = true)) {
                            leave()
                            listener.onHostClosed()
                        } else if (token.isNotEmpty() && !isDestroyed) {
                            scheduleReconnect(if (reason.isNotEmpty()) reason else "closed $codeVal")
                        } else {
                            leave()
                            listener.onNote("Room closed. Create or join again.")
                        }
                    }
                }
            }
        })
    }

    private fun scheduleReconnect(errorMsg: String, is409: Boolean = false) {
        socket = null
        connected = false
        reconnectAttempts++
        val delay = when {
            is409 -> (2000L * reconnectAttempts).coerceIn(2000L, 5000L)
            reconnectAttempts <= 5 -> (1000L * reconnectAttempts).coerceIn(1200L, 3000L)
            reconnectAttempts <= 15 -> 3500L
            else -> 5000L
        }
        val noteMsg = if (reconnectAttempts > 15) {
            "Connection paused · Retrying… (Check your internet or Wi-Fi data)"
        } else {
            "Connection interrupted · Reconnecting in ${delay / 1000}s (attempt $reconnectAttempts)…"
        }
        listener.onNote(noteMsg)
        ui.removeCallbacks(reconnectTask)
        ui.postDelayed(reconnectTask, delay)
    }

    fun send(data: JSONObject) {
        socket?.send(data.toString())
    }

    fun sendBinary(bytes: ByteString): Boolean {
        return socket?.send(bytes) ?: false
    }

    fun leave() {
        val roomToRevoke = code
        val tokenToRevoke = token
        val wasHost = host
        if (wasHost && roomToRevoke.isNotEmpty() && tokenToRevoke.isNotEmpty()) {
            worker.execute {
                try {
                    val req = Request.Builder()
                        .url("$base/rooms/$roomToRevoke")
                        .delete()
                        .header("Authorization", "Bearer $tokenToRevoke")
                        .build()
                    client.newCall(req).execute().close()
                } catch (_: Exception) {}
            }
        }
        generation++
        reconnectAttempts = 0
        ui.removeCallbacks(reconnectTask)
        val old = socket
        socket = null
        old?.close(1000, "Left room")
        token = ""
        code = ""
        invite = ""
        connected = false
        connecting = false
    }

    fun destroy() {
        isDestroyed = true
        leave()
        worker.shutdownNow()
    }
}

