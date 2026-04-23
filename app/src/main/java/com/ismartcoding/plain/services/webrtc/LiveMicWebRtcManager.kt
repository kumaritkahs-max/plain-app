package com.ismartcoding.plain.services.webrtc

import android.content.Context
import com.ismartcoding.lib.logcat.LogCat
import com.ismartcoding.plain.web.websocket.WebRtcSignalingMessage
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

class LiveMicWebRtcManager(private val context: Context) {
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var eglBase: EglBase? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private val sessions = mutableMapOf<String, LivePeerSession>()

    @Volatile var muted = false
        private set

    fun start(): Boolean {
        try {
            LogCat.d("live mic: starting")
            eglBase = EglBase.create()
            val (f, a) = createSimpleWebRtcFactory(context, eglBase!!)
            factory = f; adm = a
            audioSource = factory!!.createAudioSource(MediaConstraints())
            audioTrack = factory!!.createAudioTrack("live_mic_audio", audioSource)
            audioTrack?.setEnabled(!muted)
            LogCat.d("live mic: audio track ready id=${audioTrack?.id()} muted=$muted")
            return true
        } catch (e: Throwable) {
            LogCat.e("live mic: start failed: ${e.javaClass.simpleName}: ${e.message}")
            e.stackTrace.take(8).forEach { LogCat.e("    at $it") }
            return false
        }
    }

    fun setMuted(m: Boolean) {
        muted = m
        audioTrack?.setEnabled(!m)
    }

    fun handleSignaling(clientId: String, message: WebRtcSignalingMessage) {
        LogCat.d("live mic: signaling type=${message.type} from client=$clientId")
        when (message.type) {
            "ready" -> {
                val factory = factory ?: run { LogCat.e("live mic: ignoring 'ready' — factory is null"); return }
                val track = audioTrack ?: run { LogCat.e("live mic: ignoring 'ready' — audioTrack is null"); return }
                sessions.remove(clientId)?.release()
                val s = LivePeerSession(clientId, "mic", factory, null, track)
                sessions[clientId] = s
                s.createPeerConnectionAndOffer()
            }
            "answer" -> if (!message.sdp.isNullOrBlank()) sessions[clientId]?.handleAnswer(message.sdp)
                else LogCat.e("live mic: 'answer' missing sdp from $clientId")
            "ice_candidate" -> if (!message.candidate.isNullOrBlank()) sessions[clientId]?.handleIceCandidate(message)
            else -> LogCat.d("live mic: ignoring unknown signaling type=${message.type}")
        }
    }

    fun release() {
        sessions.values.forEach { it.release() }; sessions.clear()
        audioTrack = null
        audioSource?.dispose(); audioSource = null
        adm?.release(); adm = null
        factory?.dispose(); factory = null
        eglBase?.release(); eglBase = null
    }
}
