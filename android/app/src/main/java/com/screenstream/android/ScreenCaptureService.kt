package com.screenstream.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_ROOM_CODE = "room_code"
        const val ACTION_STATUS = "com.screenstream.android.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_RUNNING = "running"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screenstream"
    }

    private var signaling: SignalingClient? = null
    private var peerConnection: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: org.webrtc.VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            broadcastStatus("Foreground service could not start: ${e.message ?: "unknown error"}", false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!started && intent != null) {
            startStreaming(intent)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ScreenStream", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenStream")
            .setContentText("Screen sharing is active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startStreaming(intent: Intent) {
        started = true
        broadcastStatus("Preparing screen capture…", true)

        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
            val resultData = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)?.trim().orEmpty()
            val roomCode = intent.getStringExtra(EXTRA_ROOM_CODE)?.trim().orEmpty()

            if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
                fail("Android screen-capture permission is missing.")
                return
            }
            if (serverUrl.isBlank()) {
                fail("Signaling server URL is empty.")
                return
            }
            if (roomCode.isBlank()) {
                fail("Room code is empty.")
                return
            }
            if (!serverUrl.startsWith("wss://") && !serverUrl.startsWith("ws://")) {
                fail("Signaling URL must start with wss:// or ws://")
                return
            }

            broadcastStatus("Initializing WebRTC…", true)
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                    .createInitializationOptions()
            )

            eglBase = EglBase.create()
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
                .createPeerConnectionFactory()

            val pcFactory = factory ?: run {
                fail("WebRTC PeerConnectionFactory could not be created.")
                return
            }

            val configuration = PeerConnection.RTCConfiguration(
                listOf(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                        .createIceServer()
                )
            ).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            peerConnection = pcFactory.createPeerConnection(configuration, peerObserver)
            if (peerConnection == null) {
                fail("WebRTC PeerConnection could not be created.")
                return
            }

            val projectionIntent = Intent(resultData)
            capturer = ScreenCapturerAndroid(
                projectionIntent,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        broadcastStatus("Screen capture was stopped by Android.", false)
                        stopSelf()
                    }
                }
            )

            surfaceTextureHelper = SurfaceTextureHelper.create(
                "ScreenCapture",
                eglBase!!.eglBaseContext
            )
            videoSource = pcFactory.createVideoSource(false)
            val source = videoSource ?: run {
                fail("WebRTC video source could not be created.")
                return
            }

            capturer!!.initialize(surfaceTextureHelper, applicationContext, source.capturerObserver)
            capturer!!.startCapture(720, 1280, 30)
            videoTrack = pcFactory.createVideoTrack("screen-video", source)
            val track = videoTrack ?: run {
                fail("WebRTC screen video track could not be created.")
                return
            }
            peerConnection!!.addTrack(track, listOf("screen-stream"))

            broadcastStatus("Connecting to signaling server…", true)
            signaling = SignalingClient(serverUrl, roomCode, signalingListener)
            signaling?.connect()
        } catch (e: SecurityException) {
            fail("Android denied screen capture: ${e.message ?: "permission or foreground-service restriction"}")
        } catch (e: Exception) {
            fail("Screen sharing failed to start: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private val signalingListener = object : SignalingClient.Listener {
        override fun onConnected() {
            broadcastStatus("Waiting for Viewer…", true)
        }

        override fun onPeerJoined() {
            broadcastStatus("Viewer connected. Negotiating video…", true)
            createOffer()
        }

        override fun onAnswer(sdp: JSONObject) {
            try {
                val type = SessionDescription.Type.fromCanonicalForm(sdp.optString("type"))
                    ?: throw IllegalArgumentException("Invalid SDP type")
                val description = SessionDescription(type, sdp.optString("sdp"))
                peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        broadcastStatus("Viewer connected. Streaming screen…", true)
                    }

                    override fun onSetFailure(error: String) {
                        fail("WebRTC answer rejected: $error")
                    }
                }, description)
            } catch (e: Exception) {
                fail("Invalid WebRTC answer: ${e.message ?: "unknown error"}")
            }
        }

        override fun onIceCandidate(candidate: JSONObject) {
            val pc = peerConnection ?: return
            val ice = IceCandidate(
                candidate.optString("sdpMid").ifBlank { null },
                candidate.optInt("sdpMLineIndex", 0),
                candidate.optString("candidate")
            )
            pc.addIceCandidate(ice)
        }

        override fun onPeerLeft() {
            broadcastStatus("Viewer disconnected. Waiting for Viewer…", true)
        }

        override fun onError(message: String) {
            broadcastStatus("Signaling: $message. Retrying…", true)
        }
    }

    private fun createOffer() {
        val pc = peerConnection ?: run {
            fail("WebRTC connection is unavailable.")
            return
        }

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        signaling?.sendOffer(JSONObject().apply {
                            put("type", description.type.canonicalForm())
                            put("sdp", description.description)
                        })
                    }

                    override fun onSetFailure(error: String) {
                        fail("Could not set WebRTC local description: $error")
                    }
                }, description)
            }

            override fun onCreateFailure(error: String) {
                fail("Could not create WebRTC offer: $error")
            }
        }, MediaConstraints())
    }

    private val peerObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            signaling?.sendIceCandidate(JSONObject().apply {
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate", candidate.sdp)
            })
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED ->
                    broadcastStatus("Viewer connected. Streaming screen…", true)
                PeerConnection.IceConnectionState.FAILED ->
                    broadcastStatus("WebRTC network connection failed. Check network/TURN settings.", true)
                PeerConnection.IceConnectionState.DISCONNECTED ->
                    broadcastStatus("WebRTC connection interrupted. Waiting for reconnect…", true)
                else -> Unit
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out MediaStream>) {}
    }

    private fun fail(message: String) {
        broadcastStatus(message, false)
        stopSelf()
    }

    private fun broadcastStatus(message: String, running: Boolean) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, message)
            putExtra(EXTRA_RUNNING, running)
        })
    }

    override fun onDestroy() {
        started = false
        try {
            capturer?.stopCapture()
        } catch (_: Exception) {
        }
        capturer?.dispose()
        surfaceTextureHelper?.dispose()
        videoSource?.dispose()
        videoTrack?.dispose()
        peerConnection?.dispose()
        factory?.dispose()
        eglBase?.release()
        signaling?.close()
        broadcastStatus("Screen sharing stopped.", false)
        super.onDestroy()
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }
}
