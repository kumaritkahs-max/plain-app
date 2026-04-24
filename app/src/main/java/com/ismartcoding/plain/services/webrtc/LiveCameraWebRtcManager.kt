package com.ismartcoding.plain.services.webrtc

import android.content.Context
import com.ismartcoding.lib.logcat.LogCat
import com.ismartcoding.plain.services.recording.VideoFramePhoto
import com.ismartcoding.plain.services.recording.VideoFrameRecorder
import com.ismartcoding.plain.web.websocket.WebRtcSignalingMessage
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.File

class LiveCameraWebRtcManager(private val context: Context) {
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var eglBase: EglBase? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private val sessions = mutableMapOf<String, LivePeerSession>()

    @Volatile var facing: String = "back"
        private set

    // ---- Local recording / photo capture state ----
    private val captureWidth = 1280
    private val captureHeight = 720
    private var recorder: VideoFrameRecorder? = null
    @Volatile var recordingStartedAt: Long = 0L; private set
    @Volatile private var pendingPhoto: ((VideoFrame) -> Unit)? = null
    private val frameSink = VideoSink { frame ->
        try {
            recorder?.onFrame(frame)
            pendingPhoto?.let { cb -> pendingPhoto = null; cb(frame) }
        } catch (e: Throwable) {
            LogCat.e("live camera: frame sink error: ${e.message}")
        }
    }
    private var frameSinkAttached = false

    fun start(initialFacing: String): Boolean {
        try {
            facing = if (initialFacing == "front") "front" else "back"
            LogCat.d("live camera: starting with facing=$facing")
            eglBase = EglBase.create()
            val (f, a) = createSimpleWebRtcFactory(context, eglBase!!)
            factory = f; adm = a
            val enumerator = Camera2Enumerator(context)
            val devices = enumerator.deviceNames
            LogCat.d("live camera: ${devices.size} device(s) available: ${devices.joinToString()}")
            val deviceName = pickDevice(enumerator, facing) ?: run {
                LogCat.e("live camera: no camera devices found (Camera2 returned empty list)")
                return false
            }
            LogCat.d("live camera: opening device=$deviceName")
            val cap = enumerator.createCapturer(deviceName, object : org.webrtc.CameraVideoCapturer.CameraEventsHandler {
                override fun onCameraError(err: String) { LogCat.e("live camera: device error: $err") }
                override fun onCameraDisconnected() { LogCat.e("live camera: device disconnected") }
                override fun onCameraFreezed(err: String) { LogCat.e("live camera: device frozen: $err") }
                override fun onCameraOpening(name: String) { LogCat.d("live camera: opening $name") }
                override fun onFirstFrameAvailable() { LogCat.d("live camera: first frame available") }
                override fun onCameraClosed() { LogCat.d("live camera: device closed") }
            }) ?: run {
                LogCat.e("live camera: createCapturer failed for $deviceName")
                return false
            }
            capturer = cap
            surfaceHelper = SurfaceTextureHelper.create("LiveCameraThread", eglBase!!.eglBaseContext)
            videoSource = factory!!.createVideoSource(false)
            cap.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
            cap.startCapture(1280, 720, 30)
            videoTrack = factory!!.createVideoTrack("live_camera_video", videoSource)
            videoTrack?.addSink(frameSink); frameSinkAttached = true
            LogCat.d("live camera: capture started 1280x720@30, track id=${videoTrack?.id()}")
            return true
        } catch (e: Throwable) {
            LogCat.e("live camera: start failed: ${e.javaClass.simpleName}: ${e.message}")
            e.stackTrace.take(8).forEach { LogCat.e("    at $it") }
            return false
        }
    }

    fun switchFacing() {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                facing = if (isFrontCamera) "front" else "back"
            }
            override fun onCameraSwitchError(errorDescription: String) {
                LogCat.e("live camera switch failed: $errorDescription")
            }
        })
    }

    fun handleSignaling(clientId: String, message: WebRtcSignalingMessage) {
        LogCat.d("live camera: signaling type=${message.type} from client=$clientId")
        when (message.type) {
            "ready" -> {
                val factory = factory ?: run { LogCat.e("live camera: ignoring 'ready' — factory is null"); return }
                val track = videoTrack ?: run { LogCat.e("live camera: ignoring 'ready' — videoTrack is null"); return }
                sessions.remove(clientId)?.release()
                val s = LivePeerSession(clientId, "camera", factory, track, null)
                sessions[clientId] = s
                s.createPeerConnectionAndOffer()
            }
            "answer" -> if (!message.sdp.isNullOrBlank()) sessions[clientId]?.handleAnswer(message.sdp)
                else LogCat.e("live camera: 'answer' missing sdp from $clientId")
            "ice_candidate" -> if (!message.candidate.isNullOrBlank()) sessions[clientId]?.handleIceCandidate(message)
            else -> LogCat.d("live camera: ignoring unknown signaling type=${message.type}")
        }
    }

    /** Begin H.264/MP4 recording to [outputFile]. Returns true on success. */
    @Synchronized
    fun startRecording(outputFile: File): Boolean {
        if (recorder != null) return false
        if (videoTrack == null) return false
        val r = VideoFrameRecorder(outputFile, captureWidth, captureHeight)
        if (!r.start()) return false
        recorder = r
        recordingStartedAt = System.currentTimeMillis()
        return true
    }

    /** Stop the current recording. Returns Pair(durationMs, sizeBytes). */
    @Synchronized
    fun stopRecording(): Pair<Long, Long> {
        val r = recorder ?: return 0L to 0L
        val sizeBytes = r.stop()
        val duration = if (recordingStartedAt > 0) System.currentTimeMillis() - recordingStartedAt else 0L
        recorder = null
        recordingStartedAt = 0L
        return duration to sizeBytes
    }

    fun isRecording(): Boolean = recorder != null
    fun recordingWidth() = captureWidth
    fun recordingHeight() = captureHeight

    /** Capture a single JPEG snapshot from the current video stream. */
    fun capturePhoto(timeoutMs: Long = 2000L): ByteArray? {
        if (videoTrack == null) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = arrayOfNulls<ByteArray>(1)
        pendingPhoto = { frame ->
            holder[0] = VideoFramePhoto.toJpeg(frame)
            latch.countDown()
        }
        return if (latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) holder[0] else null
    }

    fun release() {
        try { stopRecording() } catch (_: Throwable) {}
        if (frameSinkAttached) {
            try { videoTrack?.removeSink(frameSink) } catch (_: Throwable) {}
            frameSinkAttached = false
        }
        sessions.values.forEach { it.release() }; sessions.clear()
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        videoTrack = null
        videoSource?.dispose(); videoSource = null
        surfaceHelper?.dispose(); surfaceHelper = null
        adm?.release(); adm = null
        factory?.dispose(); factory = null
        eglBase?.release(); eglBase = null
    }

    private fun pickDevice(e: Camera2Enumerator, facing: String): String? {
        val names = e.deviceNames
        return names.firstOrNull { if (facing == "front") e.isFrontFacing(it) else e.isBackFacing(it) }
            ?: names.firstOrNull()
    }
}
