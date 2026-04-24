package com.ismartcoding.plain.services

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.ismartcoding.lib.channel.sendEvent
import com.ismartcoding.lib.logcat.LogCat
import com.ismartcoding.plain.Constants
import com.ismartcoding.plain.R
import com.ismartcoding.plain.events.EventType
import com.ismartcoding.plain.events.WebSocketEvent
import com.ismartcoding.plain.data.RecordingsMetaDb
import com.ismartcoding.plain.data.RecordingsStore
import com.ismartcoding.plain.helpers.NotificationHelper
import com.ismartcoding.plain.services.recording.AudioRecorderHandle
import com.ismartcoding.plain.services.webrtc.LiveMicWebRtcManager
import com.ismartcoding.plain.web.websocket.WebRtcSignalingMessage

class LiveMicService : LifecycleService() {

    private lateinit var manager: LiveMicWebRtcManager
    private var notificationId: Int = 0
    private var silencedCallback: AudioManager.AudioRecordingCallback? = null

    @Volatile
    private var running = false

    @Volatile
    var silenced = false
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    @SuppressLint("WrongConstant")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // EXTRA_FOR_CALL=true means the service is being started to listen
        // to an active phone/VoIP call. We use AudioSource.MIC so that we
        // pick up the loudspeaker output (and the local user's voice) instead
        // of the VoIP-tuned VOICE_COMMUNICATION source which the OS often
        // silences while another app holds the call.
        val forCall = intent?.getBooleanExtra(EXTRA_FOR_CALL, false) == true
        val source = if (forCall) MediaRecorder.AudioSource.MIC
        else MediaRecorder.AudioSource.VOICE_COMMUNICATION
        LogCat.d("LiveMicService onStartCommand forCall=$forCall source=$source")
        if (!::manager.isInitialized) {
            manager = LiveMicWebRtcManager(this, source)
        }

        if (notificationId == 0) notificationId = NotificationHelper.generateId()
        val notification = NotificationHelper.createLiveServiceNotification(
            this,
            Constants.ACTION_STOP_LIVE_MIC,
            getString(R.string.app_name),
        )
        try {
            ServiceCompat.startForeground(this, notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            LogCat.e("live mic: startForeground failed: ${e.message}")
            stop(); return START_NOT_STICKY
        }

        if (!manager.start()) {
            LogCat.e("live mic: manager.start failed")
            stop(); return START_NOT_STICKY
        }
        running = true
        registerSilencedDetector()
        sendEvent(WebSocketEvent(EventType.LIVE_MIC_STREAMING, ""))
        return START_NOT_STICKY
    }

    /**
     * On Android 10+ the system can silently mute background recordings while
     * another app holds an active call. Detect that and surface it to the
     * tracker so the web client can display a warning.
     */
    private fun registerSilencedDetector() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (silencedCallback != null) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val s = configs.any { it.isClientSilenced }
                    if (s != silenced) {
                        silenced = s
                        LogCat.d("live mic: silenced=$silenced (configs=${configs.size})")
                        try { LiveCallTracker.onMicSilencedChanged(s) } catch (_: Throwable) {}
                    }
                }
            }
            silencedCallback = cb
            am.registerAudioRecordingCallback(cb, null)
        } catch (e: Throwable) {
            LogCat.e("live mic: registerSilencedDetector failed: ${e.message}")
        }
    }

    private fun unregisterSilencedDetector() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cb = silencedCallback ?: return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.unregisterAudioRecordingCallback(cb)
        } catch (_: Throwable) {}
        silencedCallback = null
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        try { audioRecorder?.stop() } catch (_: Throwable) {}
        audioRecorder = null
        unregisterSilencedDetector()
        if (::manager.isInitialized) manager.release()
        instance = null
    }

    fun isRunning(): Boolean = running
    fun isMuted(): Boolean = if (::manager.isInitialized) manager.muted else false
    fun setMuted(m: Boolean) {
        if (::manager.isInitialized) manager.setMuted(m)
    }
    fun handleWebRtcSignaling(clientId: String, message: WebRtcSignalingMessage) {
        if (::manager.isInitialized) manager.handleSignaling(clientId, message)
    }

    // ---- Local audio recording (independent of the WebRTC stream) ----
    @Volatile private var audioRecorder: AudioRecorderHandle? = null
    @Volatile private var audioRecordingStartedAt: Long = 0L
    @Volatile private var audioRecordingFile: java.io.File? = null

    fun isAudioRecording(): Boolean = audioRecorder != null
    fun audioRecordingStartedAt(): Long = audioRecordingStartedAt

    /** Begin AAC/.m4a recording into recordings/audio/. */
    @Synchronized
    fun startAudioRecording(): Boolean {
        if (audioRecorder != null) return false
        val out = RecordingsStore.allocateFile(RecordingsStore.TYPE_AUDIO)
        // Use plain MIC (not VOICE_COMMUNICATION) so playback sounds natural even if the WebRTC
        // mic stream is using a different source for the live preview.
        val rec = AudioRecorderHandle(out, audioSource = MediaRecorder.AudioSource.MIC)
        if (!rec.start()) return false
        audioRecorder = rec
        audioRecordingFile = out
        audioRecordingStartedAt = System.currentTimeMillis()
        return true
    }

    /** Stop the audio recording, persist metadata, return the row id (or null). */
    @Synchronized
    fun stopAudioRecording(name: String, note: String, tags: String): String? {
        val rec = audioRecorder ?: return null
        val file = audioRecordingFile ?: return null
        val durationMs = rec.stop()
        audioRecorder = null
        audioRecordingFile = null
        audioRecordingStartedAt = 0L
        val row = RecordingsMetaDb.insert(
            type = RecordingsStore.TYPE_AUDIO,
            filePath = file.absolutePath,
            name = name.ifBlank { file.nameWithoutExtension },
            note = note,
            tags = tags,
            durationMs = durationMs,
            sizeBytes = file.length(),
        )
        return row.id
    }

    fun stop() {
        running = false
        unregisterSilencedDetector()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val EXTRA_FOR_CALL = "for_call"

        @Volatile
        var instance: LiveMicService? = null
    }
}
