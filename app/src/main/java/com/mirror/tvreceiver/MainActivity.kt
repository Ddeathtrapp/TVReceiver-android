package com.mirror.tvreceiver

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity(), WebRTCClient.Listener {

    private lateinit var eglBase: EglBase
    private lateinit var surfaceViewRenderer: SurfaceViewRenderer
    private lateinit var statusText: TextView
    private var client: WebRTCClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflate a simple layout in code so we don't need XML yet
        val root = View.inflate(this, R.layout.activity_main, null)
        setContentView(root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceViewRenderer = findViewById(R.id.video_view)
        statusText = findViewById(R.id.status_text)

        // Initialize EGL + WebRTC surface
        eglBase = EglBase.create()
        surfaceViewRenderer.init(eglBase.eglBaseContext, null)
        surfaceViewRenderer.setEnableHardwareScaler(true)
        surfaceViewRenderer.setMirror(false)

        // SIGNALING_URL is built from Gradle (see app/build.gradle)
        val signalingUrl = BuildConfig.SIGNALING_URL
        client = WebRTCClient(
            context = this,
            eglBase = eglBase,
            signalingUrl = signalingUrl,
            listener = this
        ).also { it.attachRenderer(surfaceViewRenderer) }

        onStatusChanged("Ready")
    }

    override fun onStart() {
        super.onStart()
        client?.connect()
    }

    override fun onStop() {
        super.onStop()
        client?.disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            client?.release()
        } catch (t: Throwable) {
            Log.w("MainActivity", "Error releasing WebRTCClient", t)
        }
        surfaceViewRenderer.release()
        eglBase.release()
    }

    override fun onStatusChanged(status: String) {
        runOnUiThread {
            statusText.text = status
        }
    }

    override fun onError(error: Throwable) {
        Log.e("MainActivity", "WebRTC failure", error)
        onStatusChanged("Error: ${error.message}")
    }
}
