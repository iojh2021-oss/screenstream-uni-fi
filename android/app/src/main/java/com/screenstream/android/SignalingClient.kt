package com.screenstream.android

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val url: String,
    private val room: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onPeerJoined()
        fun onAnswer(sdp: JSONObject)
        fun onIceCandidate(candidate: JSONObject)
        fun onPeerLeft()
        fun onError(message: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor()
    private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var reconnectScheduled = false

    fun connect() {
        if (closed) return
        val normalized = url.trim()
        if (!normalized.startsWith("wss://") && !normalized.startsWith("ws://")) {
            listener.onError("Signaling URL must start with ws:// or wss://")
            return
        }

        synchronized(this) {
            reconnectScheduled = false
        }

        socket = client.newWebSocket(
            Request.Builder().url(normalized).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    if (closed) {
                        webSocket.close(1000, "closed")
                        return
                    }
                    webSocket.send(JSONObject().apply {
                        put("type", "join")
                        put("room", room)
                        put("role", "phone")
                    }.toString())
                    listener.onConnected()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val message = JSONObject(text)
                        when (message.optString("type")) {
                            "joined" -> listener.onConnected()
                            "peer-joined" -> listener.onPeerJoined()
                            "answer" -> listener.onAnswer(message)
                            "ice-candidate" -> listener.onIceCandidate(message)
                            "peer-left" -> listener.onPeerLeft()
                            "error" -> listener.onError(
                                message.optString("message", "Signaling error")
                            )
                        }
                    } catch (e: Exception) {
                        listener.onError("Invalid signaling message: ${e.message ?: "malformed JSON"}")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!closed) scheduleReconnect("Signaling connection closed ($code).")
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: okhttp3.Response?
                ) {
                    if (!closed) scheduleReconnect("Signaling connection failed: ${t.message ?: "network error"}")
                }
            }
        )
    }

    private fun scheduleReconnect(message: String) {
        listener.onError(message)
        synchronized(this) {
            if (closed || reconnectScheduled) return
            reconnectScheduled = true
        }
        reconnectExecutor.schedule({
            synchronized(this) { reconnectScheduled = false }
            connect()
        }, 3, TimeUnit.SECONDS)
    }

    fun sendOffer(sdp: JSONObject) = send("offer", sdp)
    fun sendIceCandidate(candidate: JSONObject) = send("ice-candidate", candidate)

    private fun send(type: String, payload: JSONObject) {
        val message = JSONObject(payload.toString()).apply {
            put("type", type)
            put("room", room)
            put("role", "phone")
        }
        socket?.send(message.toString())
    }

    fun close() {
        closed = true
        socket?.close(1000, "closed")
        socket = null
        reconnectExecutor.shutdownNow()
        client.dispatcher.executorService.shutdown()
    }
}
