package com.mirror.tvreceiver

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity(), WebRTCClient.Listener {

    private lateinit var eglBase: EglBase
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var webrtcClient: WebRTCClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        remoteView = findViewById(R.id.remote_view)
        eglBase = EglBase.create()
        remoteView.init(eglBase.eglBaseContext, null)
        remoteView.setEnableHardwareScaler(true)
        remoteView.setMirror(false)

        webrtcClient = WebRTCClient(
            context = this,
            eglBase = eglBase,
            listener = this,
            remoteRenderer = remoteView,
            signalingUrl = BuildConfig.SIGNALING_URL
        )
    }

    override fun onStart() {
        super.onStart()
        webrtcClient.connect()
    }

    override fun onStop() {
        webrtcClient.disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        webrtcClient.release()
        remoteView.release()
        eglBase.release()
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
