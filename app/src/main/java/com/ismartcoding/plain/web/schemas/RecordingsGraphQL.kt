package com.ismartcoding.plain.web.schemas

import com.ismartcoding.lib.channel.sendEvent
import com.ismartcoding.lib.kgraphql.schema.dsl.SchemaBuilder
import com.ismartcoding.plain.data.DRecording
import com.ismartcoding.plain.data.RecordingsMetaDb
import com.ismartcoding.plain.data.RecordingsStore
import com.ismartcoding.plain.events.StartScreenCaptureEvent
import com.ismartcoding.plain.services.LiveCameraService
import com.ismartcoding.plain.services.LiveMicService
import com.ismartcoding.plain.services.ScreenCaptureService

private data class RecordingItem(
    val id: String,
    val type: String,
    val name: String,
    val note: String,
    val tags: String,
    val durationMs: Int,
    val sizeBytes: Int,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val createdAt: String,
)

private data class RecordingsStats(
    val total: Int,
    val totalBytes: Int,
    val videoCount: Int,
    val photoCount: Int,
    val audioCount: Int,
    val screenCount: Int,
    val screenshotCount: Int,
)

private data class CameraRecState(
    val recording: Boolean,
    val startedAt: String,
)

private data class MicRecState(
    val recording: Boolean,
    val startedAt: String,
)

private data class ScreenCaptureState(
    val running: Boolean,
    val recording: Boolean,
    val startedAt: String,
)

private fun DRecording.toItem(): RecordingItem = RecordingItem(
    id = id,
    type = type,
    name = name,
    note = note,
    tags = tags,
    durationMs = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    sizeBytes = sizeBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    width = width,
    height = height,
    mimeType = mimeType.ifBlank { RecordingsStore.mimeTypeFor(type) },
    createdAt = createdAt.toString(),
)

fun SchemaBuilder.addRecordingsSchema() {
    // ---- Queries ----
    query("recordings") {
        resolver { type: String, offset: Int, limit: Int ->
            val typeOrNull = type.ifBlank { null }
            RecordingsMetaDb.list(typeOrNull, offset, limit.coerceIn(1, 1000)).map { it.toItem() }
        }
    }
    query("recording") {
        resolver { id: String -> RecordingsMetaDb.get(id)?.toItem() }
    }
    query("recordingsStats") {
        resolver { ->
            val s = RecordingsMetaDb.stats()
            RecordingsStats(
                total = s.total,
                totalBytes = s.totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                videoCount = s.byType[RecordingsStore.TYPE_VIDEO] ?: 0,
                photoCount = s.byType[RecordingsStore.TYPE_PHOTO] ?: 0,
                audioCount = s.byType[RecordingsStore.TYPE_AUDIO] ?: 0,
                screenCount = s.byType[RecordingsStore.TYPE_SCREEN] ?: 0,
                screenshotCount = s.byType[RecordingsStore.TYPE_SCREENSHOT] ?: 0,
            )
        }
    }

    query("cameraRecordingState") {
        resolver { ->
            val s = LiveCameraService.instance
            CameraRecState(
                recording = s?.isRecording() == true,
                startedAt = (s?.recordingStartedAt() ?: 0L).toString(),
            )
        }
    }
    query("micRecordingState") {
        resolver { ->
            val s = LiveMicService.instance
            MicRecState(
                recording = s?.isAudioRecording() == true,
                startedAt = (s?.audioRecordingStartedAt() ?: 0L).toString(),
            )
        }
    }
    query("screenCaptureState") {
        resolver { ->
            val s = ScreenCaptureService.instance
            ScreenCaptureState(
                running = s?.isRunning() == true,
                recording = s?.isRecording() == true,
                startedAt = (s?.recordingStartedAt() ?: 0L).toString(),
            )
        }
    }

    // ---- Mutations: metadata ----
    mutation("updateRecordingMeta") {
        resolver { id: String, name: String, note: String, tags: String ->
            RecordingsMetaDb.update(id, name = name, note = note, tags = tags)?.toItem()
        }
    }
    mutation("renameRecording") {
        resolver { id: String, name: String ->
            RecordingsMetaDb.update(id, name = name)?.toItem()
        }
    }
    mutation("deleteRecording") {
        resolver { id: String -> RecordingsMetaDb.delete(id) }
    }
    mutation("deleteRecordings") {
        resolver { ids: List<String> -> RecordingsMetaDb.deleteMany(ids) }
    }

    // ---- Mutations: camera video + photo ----
    mutation("startCameraVideoRecording") {
        resolver { ->
            LiveCameraService.instance?.startVideoRecording() ?: false
        }
    }
    mutation("stopCameraVideoRecording") {
        resolver { name: String, note: String, tags: String ->
            LiveCameraService.instance?.stopVideoRecording(name, note, tags)?.let {
                RecordingsMetaDb.get(it)?.toItem()
            }
        }
    }
    mutation("captureCameraPhoto") {
        resolver { name: String, note: String, tags: String ->
            LiveCameraService.instance?.capturePhoto(name, note, tags)?.let {
                RecordingsMetaDb.get(it)?.toItem()
            }
        }
    }

    // ---- Mutations: microphone audio ----
    mutation("startMicAudioRecording") {
        resolver { ->
            LiveMicService.instance?.startAudioRecording() ?: false
        }
    }
    mutation("stopMicAudioRecording") {
        resolver { name: String, note: String, tags: String ->
            LiveMicService.instance?.stopAudioRecording(name, note, tags)?.let {
                RecordingsMetaDb.get(it)?.toItem()
            }
        }
    }

    // ---- Mutations: screen capture service ----
    mutation("startScreenCaptureService") {
        resolver { ->
            sendEvent(StartScreenCaptureEvent())
            true
        }
    }
    mutation("stopScreenCaptureService") {
        resolver { ->
            ScreenCaptureService.instance?.stop()
            ScreenCaptureService.instance = null
            true
        }
    }
    mutation("startScreenRecording") {
        resolver { ->
            ScreenCaptureService.instance?.startRecording() ?: false
        }
    }
    mutation("stopScreenRecording") {
        resolver { name: String, note: String, tags: String ->
            ScreenCaptureService.instance?.stopRecording(name, note, tags)?.let {
                RecordingsMetaDb.get(it)?.toItem()
            }
        }
    }
    mutation("takeScreenshot") {
        resolver { name: String, note: String, tags: String ->
            ScreenCaptureService.instance?.takeScreenshot(name, note, tags)?.let {
                RecordingsMetaDb.get(it)?.toItem()
            }
        }
    }
}
