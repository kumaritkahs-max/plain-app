package com.ismartcoding.plain.services.webrtc

import com.ismartcoding.lib.helpers.CoroutinesHelper.coIO
import com.ismartcoding.lib.helpers.JsonHelper
import com.ismartcoding.lib.logcat.LogCat
import com.ismartcoding.plain.web.websocket.WebRtcSignalingMessage
import com.ismartcoding.plain.web.websocket.WebSocketHelper
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Generic WebRTC peer session for live camera/mic streaming.
 * Mirrors the screen-mirror peer session but is stream-agnostic and tags
 * every signaling message with the [streamId] so the web side can route.
 */
class LivePeerSession(
    val clientId: String,
    private val streamId: String, // "camera" or "mic"
    private val factory: PeerConnectionFactory,
    private val videoTrack: VideoTrack?,
    private val audioTrack: AudioTrack?,
) {
    private var pc: PeerConnection? = null
    private val remoteSet = AtomicBoolean(false)
    private val pending = mutableListOf<IceCandidate>()

    fun createPeerConnectionAndOffer() {
        release()
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        pc = factory.createPeerConnection(cfg, observer())
        if (pc == null) {
            LogCat.e("live[$streamId][$clientId] createPeerConnection returned null")
            return
        }
        videoTrack?.let { pc?.addTrack(it, listOf("live_$streamId")) }
        audioTrack?.let { pc?.addTrack(it, listOf("live_$streamId")) }
        LogCat.d("live[$streamId][$clientId] creating offer (video=${videoTrack != null} audio=${audioTrack != null})")
        pc?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(d: SessionDescription) {
                pc?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        LogCat.d("live[$streamId][$clientId] offer ready, sending to web (${d.description.length} bytes)")
                        send(WebRtcSignalingMessage(type = "offer", sdp = d.description, stream = streamId))
                    }
                    override fun onSetFailure(p0: String?) { LogCat.e("live[$streamId][$clientId] setLocalDescription failed: $p0") }
                }, d)
            }
            override fun onCreateFailure(p0: String?) { LogCat.e("live[$streamId][$clientId] createOffer failed: $p0") }
        }, MediaConstraints())
    }

    fun handleAnswer(sdp: String) {
        val pc = pc ?: return
        if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
        remoteSet.set(false); pending.clear()
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteSet.set(true)
                pending.forEach { pc.addIceCandidate(it) }; pending.clear()
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun handleIceCandidate(message: WebRtcSignalingMessage) {
        val pc = pc ?: return
        val candidate = IceCandidate(message.sdpMid, message.sdpMLineIndex ?: 0, message.candidate)
        if (remoteSet.get()) pc.addIceCandidate(candidate) else pending.add(candidate)
    }

    fun release() {
        remoteSet.set(false); pending.clear()
        pc?.close(); pc = null
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            send(
                WebRtcSignalingMessage(
                    type = "ice_candidate",
                    candidate = c.sdp,
                    sdpMid = c.sdpMid,
                    sdpMLineIndex = c.sdpMLineIndex,
                    stream = streamId,
                ),
            )
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            LogCat.d("live[$streamId][$clientId] connection state: $s")
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(r: Boolean) = Unit
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(c: Array<IceCandidate>) = Unit
        override fun onAddStream(s: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(s: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(dc: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onTrack(t: org.webrtc.RtpTransceiver) = Unit
    }

    private fun send(message: WebRtcSignalingMessage) {
        val json = JsonHelper.jsonEncode(message)
        coIO {
            try { WebSocketHelper.sendSignalingToClientAsync(clientId, json) }
            catch (ex: Exception) { LogCat.e("live[$streamId] send failed: ${ex.message}") }
        }
    }
}
