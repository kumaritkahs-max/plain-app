package com.ismartcoding.plain.ui.models

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.saveable
import com.ismartcoding.lib.channel.sendEvent
import com.ismartcoding.lib.helpers.CoroutinesHelper.withIO
import com.ismartcoding.plain.enums.HttpServerState
import com.ismartcoding.plain.events.StartHttpServerEvent
import com.ismartcoding.plain.preferences.WebPreference
import com.ismartcoding.plain.web.HttpServerManager
import kotlinx.coroutines.launch

// https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate#savedstate-compose-state
@OptIn(androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi::class)
class MainViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    var httpServerError by savedStateHandle.saveable { mutableStateOf("") }
    var httpServerState by savedStateHandle.saveable {
        mutableStateOf(HttpServerState.OFF)
    }
    var isVPNConnected by savedStateHandle.saveable { mutableStateOf(false) }
    var ip4s by savedStateHandle.saveable { mutableStateOf(emptyList<String>()) }
    var ip4 by savedStateHandle.saveable { mutableStateOf("") }
    var currentRootTab by savedStateHandle.saveable { mutableIntStateOf(0) }

    fun enableHttpServer(
        context: Context,
        enable: Boolean,
    ) {
        viewModelScope.launch {
            withIO { WebPreference.putAsync(context, enable) }
            if (enable) {
                httpServerError = ""
                if (!httpServerState.isProcessing() && httpServerState != HttpServerState.ON) {
                    httpServerState = HttpServerState.STARTING
                }
                // Notification permission is NOT required for the foreground
                // service to run. If the user has opted in to seeing the
                // notification (Web settings → Show service notification), the
                // permission was already requested at toggle time. Otherwise we
                // start silently — Android will simply hide the notification.
                sendEvent(StartHttpServerEvent())
            } else {
                withIO {
                    HttpServerManager.stopServiceAsync(context)
                }
            }
        }
    }

    fun syncHttpServerState(context: Context) {
        viewModelScope.launch {
            val webEnabled = withIO { WebPreference.getAsync(context) }
            if (!webEnabled) {
                if (!httpServerState.isProcessing()) {
                    httpServerState = HttpServerState.OFF
                }
                return@launch
            }

            if (httpServerState == HttpServerState.ERROR) {
                return@launch
            }

            if (!httpServerState.isProcessing() && httpServerState != HttpServerState.ON) {
                httpServerState = HttpServerState.STARTING
            }

            val check = withIO { HttpServerManager.checkServerAsync() }
            if (check.http && check.websocket) {
                httpServerError = ""
                httpServerState = HttpServerState.ON
            } else {
                enableHttpServer(context, true)
            }
        }
    }
}
