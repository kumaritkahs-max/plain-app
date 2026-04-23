package com.ismartcoding.plain.web.schemas

import com.ismartcoding.lib.kgraphql.schema.dsl.SchemaBuilder
import com.ismartcoding.lib.channel.sendEvent
import com.ismartcoding.plain.MainApp
import com.ismartcoding.plain.events.StartLiveCameraEvent
import com.ismartcoding.plain.events.StartLiveMicEvent
import com.ismartcoding.plain.features.Permission
import com.ismartcoding.plain.services.LiveCameraService
import com.ismartcoding.plain.services.LiveMicService

private data class LiveCameraState(
    val running: Boolean,
    val facing: String,
    val hasPermission: Boolean,
)

private data class LiveMicState(
    val running: Boolean,
    val muted: Boolean,
    val hasPermission: Boolean,
)

fun SchemaBuilder.addLiveMonitorSchema() {
    query("liveCameraState") {
        resolver { ->
            val s = LiveCameraService.instance
            LiveCameraState(
                running = s?.isRunning() == true,
                facing = s?.facing() ?: "back",
                hasPermission = Permission.CAMERA.can(MainApp.instance),
            )
        }
    }
    query("liveMicState") {
        resolver { ->
            val s = LiveMicService.instance
            LiveMicState(
                running = s?.isRunning() == true,
                muted = s?.isMuted() == true,
                hasPermission = Permission.RECORD_AUDIO.can(MainApp.instance),
            )
        }
    }

    mutation("startLiveCamera") {
        resolver { facing: String ->
            sendEvent(StartLiveCameraEvent(if (facing == "front") "front" else "back"))
            true
        }
    }
    mutation("stopLiveCamera") {
        resolver { ->
            LiveCameraService.instance?.stop()
            LiveCameraService.instance = null
            true
        }
    }
    mutation("switchLiveCameraFacing") {
        resolver { ->
            LiveCameraService.instance?.switchFacing()
            true
        }
    }

    mutation("startLiveMic") {
        resolver { ->
            sendEvent(StartLiveMicEvent())
            true
        }
    }
    mutation("stopLiveMic") {
        resolver { ->
            LiveMicService.instance?.stop()
            LiveMicService.instance = null
            true
        }
    }
    mutation("setLiveMicMuted") {
        resolver { muted: Boolean ->
            LiveMicService.instance?.setMuted(muted)
            true
        }
    }
}
