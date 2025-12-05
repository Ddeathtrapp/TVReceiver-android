package com.mirror.tvreceiver

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity(), WebRTCClient.Listener {

    private lateinit var remoteView: SurfaceViewRenderer
    private var eglBase: EglBase? = null
    private var webrtcClient: WebRTCClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(null)

        remoteView = findViewById(R.id.remote_view)
        remoteView.setZOrderMediaOverlay(false)
        remoteView.setZOrderOnTop(false)

        try {
            eglBase = EglBase.create().also { base ->
                remoteView.init(base.eglBaseContext, null)
                remoteView.setEnableHardwareScaler(true)
                remoteView.setMirror(false)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Renderer init failed", e)
        }

        eglBase?.let { base ->
            webrtcClient = WebRTCClient(
                context = this,
                eglBase = base,
                listener = this,
                remoteRenderer = remoteView,
                signalingUrl = BuildConfig.SIGNALING_URL
            )
        }
    }

    override fun onStart() {
        super.onStart()
        webrtcClient?.connect()
    }

    override fun onStop() {
        webrtcClient?.disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        webrtcClient?.release()
        webrtcClient = null
        try {
            remoteView.release()
        } catch (e: Exception) {
            Log.w("MainActivity", "Error releasing remoteView", e)
        }
        eglBase?.release()
        eglBase = null
        super.onDestroy()
    }

    override fun onConnected() {
        Log.i("MainActivity", "Connected to signaling server")
    }

    override fun onDisconnected() {
        Log.i("MainActivity", "Disconnected from signaling server")
    }

    override fun onError(message: String) {
        Log.e("MainActivity", "WebRTC error: $message")
    }
}
