package com.ismartcoding.plain.services

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.ismartcoding.lib.channel.sendEvent
import com.ismartcoding.lib.logcat.LogCat
import com.ismartcoding.plain.Constants
import com.ismartcoding.plain.R
import com.ismartcoding.plain.events.EventType
import com.ismartcoding.plain.events.WebSocketEvent
import com.ismartcoding.plain.helpers.NotificationHelper
import com.ismartcoding.plain.services.webrtc.LiveMicWebRtcManager
import com.ismartcoding.plain.web.websocket.WebRtcSignalingMessage

class LiveMicService : LifecycleService() {

    private lateinit var manager: LiveMicWebRtcManager
    private var notificationId: Int = 0

    @Volatile
    private var running = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        manager = LiveMicWebRtcManager(this)
    }

    @SuppressLint("WrongConstant")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        LogCat.d("LiveMicService onStartCommand")

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
        sendEvent(WebSocketEvent(EventType.LIVE_MIC_STREAMING, ""))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        manager.release()
        instance = null
    }

    fun isRunning(): Boolean = running
    fun isMuted(): Boolean = manager.muted
    fun setMuted(m: Boolean) = manager.setMuted(m)
    fun handleWebRtcSignaling(clientId: String, message: WebRtcSignalingMessage) {
        manager.handleSignaling(clientId, message)
    }

    fun stop() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        @Volatile
        var instance: LiveMicService? = null
    }
}
