package com.mirror.tvreceiver

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class WebRTCClient(
    private val context: Context,
    private val eglBase: EglBase,
    private val listener: Listener,
    private val remoteRenderer: SurfaceViewRenderer,
    private val signalingUrl: String
) {

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String)
    }

    private val loggerTag = "WebRTCClient"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val okHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    private val audioDeviceModule = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
    private val peerConnectionFactory: PeerConnectionFactory

    private var webSocket: WebSocket? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var remoteVideoTrack: VideoTrack? = null

    init {
        val initializationOptions = PeerConnectionFactory.InitializationOptions
            .builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())

        remoteRenderer.setEnableHardwareScaler(true)
        remoteRenderer.setMirror(false)
    }

    fun connect() {
        if (webSocket != null) return
        postToMain { ensurePeerConnection() }
        val request = Request.Builder()
            .url(signalingUrl)
            .build()
        webSocket = okHttpClient.newWebSocket(request, signalingListener)
    }

    fun disconnect() {
        webSocket?.close(NORMAL_CLOSURE_STATUS, "client closing")
        webSocket = null
        postToMain { closePeerConnection() }
        listener.onDisconnected()
    }

    fun release() {
        disconnect()
        audioSource?.dispose()
        audioSource = null
        audioDeviceModule.release()
        peerConnectionFactory.dispose()
    }

    private val signalingListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            listener.onConnected()
            sendIdentify(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleSignalingMessage(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            this@WebRTCClient.webSocket = null
            listener.onDisconnected()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            this@WebRTCClient.webSocket = null
            reportError("Signaling failure: ${t.message}")
        }
    }

    private fun ensurePeerConnection() {
        if (peerConnection != null) return

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.BALANCED
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                sendLocalCandidate(candidate)
            }

            override fun onAddStream(stream: MediaStream) {
                attachRemoteTrack(stream.videoTracks.firstOrNull())
            }

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                attachRemoteTrack(receiver?.track() as? VideoTrack)
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d(loggerTag, "ICE state: $newState")
                if (newState == PeerConnection.IceConnectionState.DISCONNECTED ||
                    newState == PeerConnection.IceConnectionState.FAILED ||
                    newState == PeerConnection.IceConnectionState.CLOSED
                ) {
                    listener.onDisconnected()
                }
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dataChannel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        })
    }

    private fun handleSignalingMessage(message: String) {
        runCatching { JSONObject(message) }
            .onFailure { reportError("Invalid signaling message: ${it.message}") }
            .onSuccess { json ->
                when (json.optString("type")) {
                    "offer" -> handleRemoteOffer(json.optString("sdp"))
                    "candidate" -> handleRemoteCandidate(json)
                    "ping" -> webSocket?.send("""{"type":"pong"}""")
                }
            }
    }

    private fun sendIdentify(webSocket: WebSocket) {
        val payload = JSONObject()
            .put("type", "identify")
            .put("role", "receiver")
            .put("platform", "android")
            .put("model", Build.MODEL ?: "unknown")
            .put("manufacturer", Build.MANUFACTURER ?: "unknown")
        webSocket.send(payload.toString())
    }

    private fun handleRemoteOffer(sdp: String?) {
        if (sdp.isNullOrEmpty()) {
            reportError("Received empty SDP offer")
            return
        }

        postToMain {
            ensurePeerConnection()
            val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
            peerConnection?.setRemoteDescription(object : LoggingSdpObserver("setRemoteDescription") {
                override fun onSetSuccess() {
                    createAnswer()
                }
            }, offer)
        }
    }

    private fun handleRemoteCandidate(json: JSONObject) {
        val candidate = json.optString("candidate")
        val sdpMid = json.optString("sdpMid")
        val sdpMLineIndex = json.optInt("sdpMLineIndex", -1)
        if (candidate.isNullOrEmpty() || sdpMid.isNullOrEmpty() || sdpMLineIndex < 0) {
            return
        }

        postToMain {
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
            peerConnection?.addIceCandidate(iceCandidate)
        }
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createAnswer(object : LoggingSdpObserver("createAnswer") {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection?.setLocalDescription(object : LoggingSdpObserver("setLocalDescription") {
                    override fun onSetSuccess() {
                        sendAnswer(sessionDescription)
                    }
                }, sessionDescription)
            }
        }, constraints)
    }

    private fun sendAnswer(description: SessionDescription) {
        val payload = JSONObject()
            .put("type", "answer")
            .put("sdp", description.description)
        if (webSocket?.send(payload.toString()) != true) {
            reportError("Unable to send answer")
        }
    }

    private fun sendLocalCandidate(candidate: IceCandidate) {
        val payload = JSONObject()
            .put("type", "candidate")
            .put("candidate", candidate.sdp)
            .put("sdpMid", candidate.sdpMid)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
        webSocket?.send(payload.toString())
    }

    private fun attachRemoteTrack(videoTrack: VideoTrack?) {
        if (videoTrack == null) return
        remoteVideoTrack?.removeSink(remoteRenderer)
        remoteVideoTrack = videoTrack
        mainHandler.post { videoTrack.addSink(remoteRenderer) }
    }

    private fun closePeerConnection() {
        remoteVideoTrack?.removeSink(remoteRenderer)
        remoteVideoTrack = null
        peerConnection?.close()
        peerConnection = null
    }

    private fun reportError(message: String) {
        Log.e(loggerTag, message)
        listener.onError(message)
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private open class LoggingSdpObserver(private val operation: String) : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {
            Log.e("WebRTCClient", "SDP create failed for $operation: $error")
        }

        override fun onSetFailure(error: String) {
            Log.e("WebRTCClient", "SDP set failed for $operation: $error")
        }
    }

    companion object {
        private const val NORMAL_CLOSURE_STATUS = 1000
    }
}
