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
import okio.ByteString
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.AudioDeviceModule
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
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Handles WebRTC peer connection lifecycle along with the websocket signaling channel.
 */
class WebRTCClient(
    context: Context,
    private val eglBaseContext: EglBase.Context,
    private val remoteRenderer: SurfaceViewRenderer,
    private val signalingUrl: String,
    private val listener: Listener? = null
) : WebSocketListener() {

    interface Listener {
        fun onStatusChanged(status: Status)
        fun onError(error: Throwable)
    }

    enum class Status { CONNECTING, CONNECTED, DISCONNECTED }

    private val loggerTag = "WebRTCClient"
    private val okHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioDeviceModule: AudioDeviceModule
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var webSocket: WebSocket? = null

    init {
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        audioDeviceModule = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBaseContext,
            /* enableIntelVp8Encoder */ true,
            /* enableH264HighProfile */ true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun connect() {
        if (webSocket != null) return
        listener?.onStatusChanged(Status.CONNECTING)
        postToMain { ensurePeerConnection() }
        val request = Request.Builder()
            .url(signalingUrl)
            .build()
        webSocket = okHttpClient.newWebSocket(request, this)
    }

    fun disconnect() {
        webSocket?.close(NORMAL_CLOSURE_STATUS, "client closing")
        webSocket = null
        listener?.onStatusChanged(Status.DISCONNECTED)
    }

    fun release() {
        disconnect()
        peerConnection?.dispose()
        peerConnection = null
        audioDeviceModule.release()
        peerConnectionFactory.dispose()
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
        PeerConnectionFactory.stopInternalTracingCapture()
        PeerConnectionFactory.shutdownInternalTracer()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        listener?.onStatusChanged(Status.CONNECTED)
        sendIdentify()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val message = JSONObject(text)
            when (message.optString("type")) {
                "offer" -> handleRemoteOffer(message.optString("sdp"))
                "candidate" -> handleRemoteCandidate(message)
                "ping" -> webSocket.send("""{"type":"pong"}""")
            }
        } catch (jsonError: JSONException) {
            listener?.onError(jsonError)
            Log.e(loggerTag, "Invalid signaling message: $text", jsonError)
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        // Only text messages are expected from the signaling service.
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        listener?.onStatusChanged(Status.DISCONNECTED)
        this.webSocket = null
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        listener?.onError(t)
        listener?.onStatusChanged(Status.DISCONNECTED)
        this.webSocket = null
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
            override fun onIceCandidate(iceCandidate: IceCandidate) {
                sendLocalCandidate(iceCandidate)
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    mainHandler.post {
                        track.addSink(remoteRenderer)
                    }
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                val videoTrack = stream?.videoTracks?.firstOrNull()
                videoTrack?.let { track ->
                    mainHandler.post {
                        track.addSink(remoteRenderer)
                    }
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d(loggerTag, "ICE connection state: $newState")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.d(loggerTag, "Peer connection state: $newState")
            }

            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
            override fun onSelectedCandidatePairChanged(event: PeerConnection.CandidatePairChangeEvent?) {}
            override fun onRenegotiationNeeded() {}
        })
    }

    private fun sendIdentify() {
        val identifyPayload = JSONObject()
            .put("type", "identify")
            .put("role", "receiver")
            .put("platform", "android-tv")
            .put("model", Build.MODEL ?: "unknown")
            .put("manufacturer", Build.MANUFACTURER ?: "unknown")

        if (webSocket?.send(identifyPayload.toString()) != true) {
            listener?.onError(IllegalStateException("Unable to send identify handshake"))
        }
    }

    private fun handleRemoteOffer(sdp: String?) {
        if (sdp.isNullOrEmpty()) return
        postToMain {
            ensurePeerConnection()
            val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
            peerConnection?.setRemoteDescription(object : SimpleSdpObserver("setRemoteDescription") {
                override fun onSetSuccess() {
                    createAnswer()
                }
            }, sessionDescription)
        }
    }

    private fun handleRemoteCandidate(candidateJson: JSONObject) {
        val candidate = candidateJson.optString("candidate")
        val sdpMid = candidateJson.optString("sdpMid")
        val sdpMLineIndex = candidateJson.optInt("sdpMLineIndex")
        if (candidate.isNullOrEmpty()) return
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
        peerConnection?.createAnswer(object : SimpleSdpObserver("createAnswer") {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver("setLocalDescription") {
                    override fun onSetSuccess() {
                        sendAnswer(sessionDescription)
                    }
                }, sessionDescription)
            }
        }, constraints)
    }

    private fun sendAnswer(sessionDescription: SessionDescription) {
        val payload = JSONObject()
            .put("type", "answer")
            .put("sdp", sessionDescription.description)
        if (webSocket?.send(payload.toString()) != true) {
            listener?.onError(IllegalStateException("Unable to send answer"))
        }
    }

    private fun sendLocalCandidate(iceCandidate: IceCandidate) {
        val payload = JSONObject()
            .put("type", "candidate")
            .put("candidate", iceCandidate.sdp)
            .put("sdpMid", iceCandidate.sdpMid)
            .put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
        webSocket?.send(payload.toString())
    }

    private open class SimpleSdpObserver(private val operation: String) : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {
            Log.e("WebRTCClient", "SDP create failed for $operation: $error")
        }

        override fun onSetFailure(error: String) {
            Log.e("WebRTCClient", "SDP set failed for $operation: $error")
        }
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    companion object {
        private const val NORMAL_CLOSURE_STATUS = 1000
    }
}
