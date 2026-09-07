package com.screenstream.android

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val url: String,
    private val room: String,
    private val listener: Listener
) {
    interface Listener {
        fun onPeerJoined()
        fun onAnswer(sdp: JSONObject)
        fun onIceCandidate(candidate: JSONObject)
        fun onPeerLeft()
        fun onError(message: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null

    fun connect() {
        val normalized = url.trim()
        if (!normalized.startsWith("wss://") && !normalized.startsWith("ws://")) {
            listener.onError("Signaling URL must start with ws:// or wss://")
            return
        }
        socket = client.newWebSocket(Request.Builder().url(normalized).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send(JSONObject().apply {
                    put("type", "join")
                    put("room", room)
                    put("role", "phone")
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        "peer-joined" -> listener.onPeerJoined()
                        "answer" -> listener.onAnswer(message)
                        "ice-candidate" -> listener.onIceCandidate(message)
                        "peer-left" -> listener.onPeerLeft()
                        "error" -> listener.onError(message.optString("message", "Signaling error"))
                    }
                } catch (e: Exception) {
                    listener.onError("Invalid signaling message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                listener.onError(t.message ?: "WebSocket connection failed")
            }
        })
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
        socket?.close(1000, "closed")
        socket = null
        client.dispatcher.executorService.shutdown()
    }
}
