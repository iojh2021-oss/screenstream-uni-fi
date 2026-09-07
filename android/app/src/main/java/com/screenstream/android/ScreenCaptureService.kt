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
        private const val NOTIFICATION_ID = 1001
    }

    private var signaling: SignalingClient? = null
    private var peerConnection: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: org.webrtc.VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (signaling == null && intent != null) startStreaming(intent)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("screenstream", "ScreenStream", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, "screenstream")
            .setContentTitle("ScreenStream")
            .setContentText("Screen sharing is active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startStreaming(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)?.trim().orEmpty()
        val roomCode = intent.getStringExtra(EXTRA_ROOM_CODE)?.trim().orEmpty()
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null || serverUrl.isBlank() || roomCode.isBlank()) {
            stopSelf()
            return
        }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
        )
        eglBase = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()

        val configuration = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = factory?.createPeerConnection(configuration, peerObserver)

        val projectionIntent = Intent(resultData)
        capturer = ScreenCapturerAndroid(projectionIntent, object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        })
        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCapture", eglBase!!.eglBaseContext)
        videoSource = factory?.createVideoSource(false)
        capturer?.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)
        capturer?.startCapture(720, 1280, 30)
        videoTrack = factory?.createVideoTrack("screen-video", videoSource)
        peerConnection?.addTrack(videoTrack, listOf("screen-stream"))

        signaling = SignalingClient(serverUrl, roomCode, signalingListener)
        signaling?.connect()
    }

    private val signalingListener = object : SignalingClient.Listener {
        override fun onPeerJoined() { createOffer() }
        override fun onAnswer(sdp: JSONObject) {
            val type = SessionDescription.Type.fromCanonicalForm(sdp.optString("type")) ?: return
            peerConnection?.setRemoteDescription(
                SimpleSdpObserver(),
                SessionDescription(type, sdp.optString("sdp"))
            )
        }
        override fun onIceCandidate(candidate: JSONObject) {
            peerConnection?.addIceCandidate(
                IceCandidate(
                    candidate.optString("sdpMid"),
                    candidate.optInt("sdpMLineIndex"),
                    candidate.optString("candidate")
                )
            )
        }
        override fun onPeerLeft() { peerConnection?.close(); peerConnection = null }
        override fun onError(message: String) { stopSelf() }
    }

    private fun createOffer() {
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        signaling?.sendOffer(JSONObject().apply {
                            put("type", description.type.canonicalForm())
                            put("sdp", description.description)
                        })
                    }
                }, description)
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
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out MediaStream>) {}
    }

    override fun onDestroy() {
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose()
        surfaceTextureHelper?.dispose()
        videoSource?.dispose()
        videoTrack?.dispose()
        peerConnection?.dispose()
        factory?.dispose()
        eglBase?.release()
        signaling?.close()
        super.onDestroy()
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }
}
