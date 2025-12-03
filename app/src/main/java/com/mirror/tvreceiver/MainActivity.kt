package com.mirror.tvreceiver

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity(), WebRTCClient.Listener {

    private lateinit var remoteRenderer: SurfaceViewRenderer
    private lateinit var eglBase: EglBase
    private var webRtcClient: WebRTCClient? = null
    private val signalingUrl: String by lazy { BuildConfig.SIGNALING_URL }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        remoteRenderer = SurfaceViewRenderer(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            keepScreenOn = true
            systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        setContentView(remoteRenderer)

        eglBase = EglBase.create()
        remoteRenderer.init(eglBase.eglBaseContext, null)
        remoteRenderer.setEnableHardwareScaler(true)
        remoteRenderer.setMirror(false)

        webRtcClient = WebRTCClient(
            context = this,
            eglBaseContext = eglBase.eglBaseContext,
            remoteRenderer = remoteRenderer,
            signalingUrl = signalingUrl,
            listener = this
        )
    }

    override fun onStart() {
        super.onStart()
        webRtcClient?.connect()
    }

    override fun onStop() {
        webRtcClient?.disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        webRtcClient?.release()
        remoteRenderer.release()
        eglBase.release()
        super.onDestroy()
    }

    override fun onStatusChanged(status: WebRTCClient.Status) {
        Log.d(TAG, "WebRTC status = $status (signaling: $signalingUrl)")
    }

    override fun onError(error: Throwable) {
        Log.e(TAG, "WebRTC failure", error)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
