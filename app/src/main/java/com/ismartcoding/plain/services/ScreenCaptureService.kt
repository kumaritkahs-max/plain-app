package com.ismartcoding.plain.services

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.ismartcoding.lib.channel.sendEvent
import com.ismartcoding.lib.extensions.parcelable
import com.ismartcoding.lib.logcat.LogCat
import com.ismartcoding.plain.Constants
import com.ismartcoding.plain.MainApp
import com.ismartcoding.plain.R
import com.ismartcoding.plain.data.RecordingsMetaDb
import com.ismartcoding.plain.data.RecordingsStore
import com.ismartcoding.plain.events.EventType
import com.ismartcoding.plain.events.WebSocketEvent
import com.ismartcoding.plain.helpers.NotificationHelper
import com.ismartcoding.plain.mediaProjectionManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns a MediaProjection grant for hidden screen
 * recording (MP4) and one-shot screenshots (PNG). Separate from
 * [ScreenMirrorService] so live mirroring and local capture do not have to
 * fight over the same MediaProjection token.
 */
class ScreenCaptureService : LifecycleService() {

    private var notificationId: Int = 0
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = DisplayMetrics.DENSITY_DEFAULT
    private val recordingActive = AtomicBoolean(false)
    private var recordingFile: File? = null
    private var recordingStartedAt: Long = 0L

    @Volatile private var running = false

    @SuppressLint("WrongConstant")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val resultCode = intent?.getIntExtra("code", -1) ?: -1
        val resultData: Intent? = intent?.parcelable("data")

        if (notificationId == 0) notificationId = NotificationHelper.generateId()
        val notification = NotificationHelper.createServiceNotification(
            this,
            Constants.ACTION_STOP_SCREEN_CAPTURE,
            getString(R.string.app_name),
        )
        // Match ScreenMirrorService: handle OEMs where startForeground fails until
        // getMediaProjection() has set the project_media AppOp.
        try {
            ServiceCompat.startForeground(this, notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (se: SecurityException) {
            LogCat.e("screen capture: startForeground failed (OEM AppOp fix): ${se.message}")
            if (resultCode != -1 && resultData != null) {
                projection = runCatching { mediaProjectionManager.getMediaProjection(resultCode, resultData) }.getOrNull()
            }
            if (projection == null) { stop(); return START_NOT_STICKY }
            try {
                ServiceCompat.startForeground(this, notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } catch (se2: SecurityException) {
                LogCat.e("screen capture: startForeground still failed: ${se2.message}")
                stop(); return START_NOT_STICKY
            }
        } catch (e: Throwable) {
            LogCat.e("screen capture: startForeground failed: ${e.message}")
            stop(); return START_NOT_STICKY
        }

        if (projection == null && resultCode != -1 && resultData != null) {
            projection = runCatching { mediaProjectionManager.getMediaProjection(resultCode, resultData) }.getOrNull()
        }
        if (projection == null) {
            LogCat.e("screen capture: MediaProjection is null")
            stop(); return START_NOT_STICKY
        }
        running = true
        readScreenMetrics()
        instance = this
        sendEvent(WebSocketEvent(EventType.SCREEN_MIRRORING, ""))
        return START_NOT_STICKY
    }

    private fun readScreenMetrics() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        // MediaCodec rejects odd dims.
        screenWidth = (metrics.widthPixels / 2) * 2
        screenHeight = (metrics.heightPixels / 2) * 2
        screenDensity = metrics.densityDpi.coerceAtLeast(160)
    }

    fun isRunning(): Boolean = running
    fun isRecording(): Boolean = recordingActive.get()
    fun recordingStartedAt(): Long = recordingStartedAt

    /** Begin MP4 recording of the entire screen. */
    @Synchronized
    fun startRecording(): Boolean {
        if (recordingActive.get()) return false
        val proj = projection ?: return false
        val out = RecordingsStore.allocateFile(RecordingsStore.TYPE_SCREEN)
        try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            rec.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(8_000_000)
                setVideoFrameRate(30)
                setVideoSize(screenWidth, screenHeight)
                setOutputFile(out.absolutePath)
                prepare()
            }
            mediaRecorder = rec
            virtualDisplay = proj.createVirtualDisplay(
                "PlainScreenRec",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null,
            ) ?: throw IllegalStateException("virtualDisplay null")
            rec.start()
            recordingFile = out
            recordingStartedAt = System.currentTimeMillis()
            recordingActive.set(true)
            return true
        } catch (e: Throwable) {
            LogCat.e("screen capture: startRecording failed: ${e.message}")
            cleanupRecording(); RecordingsStore.safeDelete(out.absolutePath)
            return false
        }
    }

    /** Stop the active screen recording, persist metadata, return its id. */
    @Synchronized
    fun stopRecording(name: String, note: String, tags: String): String? {
        if (!recordingActive.get()) return null
        val file = recordingFile ?: run { cleanupRecording(); return null }
        val durationMs = if (recordingStartedAt > 0) System.currentTimeMillis() - recordingStartedAt else 0L
        try { mediaRecorder?.stop() } catch (_: Throwable) {}
        cleanupRecording()
        val row = RecordingsMetaDb.insert(
            type = RecordingsStore.TYPE_SCREEN,
            filePath = file.absolutePath,
            name = name.ifBlank { file.nameWithoutExtension },
            note = note,
            tags = tags,
            durationMs = durationMs,
            sizeBytes = file.length(),
            width = screenWidth,
            height = screenHeight,
        )
        return row.id
    }

    /**
     * One-shot screenshot via a single-frame ImageReader VirtualDisplay.
     * Saves PNG into recordings/screenshot/. Returns the recording id.
     */
    fun takeScreenshot(name: String, note: String, tags: String, timeoutMs: Long = 1500L): String? {
        val proj = projection ?: return null
        val w = screenWidth; val h = screenHeight
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val latch = java.util.concurrent.CountDownLatch(1)
        val pngHolder = arrayOfNulls<ByteArray>(1)
        val handlerThread = android.os.HandlerThread("ScreenshotReader").apply { start() }
        val handler = android.os.Handler(handlerThread.looper)
        var localDisplay: VirtualDisplay? = null
        try {
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val rowPad = rowStride - pixelStride * w
                    val bmp = Bitmap.createBitmap(w + rowPad / pixelStride, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                    bmp.recycle()
                    val out = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                    cropped.recycle()
                    pngHolder[0] = out.toByteArray()
                } catch (e: Throwable) {
                    LogCat.e("screen capture: screenshot decode failed: ${e.message}")
                } finally {
                    image.close()
                    latch.countDown()
                }
            }, handler)
            localDisplay = proj.createVirtualDisplay(
                "PlainScreenshot", w, h, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler,
            )
            val ok = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            val png = if (ok) pngHolder[0] else null ?: return null
            val out = RecordingsStore.allocateFile(RecordingsStore.TYPE_SCREENSHOT)
            out.outputStream().use { it.write(png) }
            val row = RecordingsMetaDb.insert(
                type = RecordingsStore.TYPE_SCREENSHOT,
                filePath = out.absolutePath,
                name = name.ifBlank { out.nameWithoutExtension },
                note = note,
                tags = tags,
                sizeBytes = out.length(),
                width = w,
                height = h,
            )
            return row.id
        } catch (e: Throwable) {
            LogCat.e("screen capture: takeScreenshot failed: ${e.message}")
            return null
        } finally {
            try { localDisplay?.release() } catch (_: Throwable) {}
            try { reader.close() } catch (_: Throwable) {}
            try { handlerThread.quitSafely() } catch (_: Throwable) {}
        }
    }

    private fun cleanupRecording() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { mediaRecorder?.reset() } catch (_: Throwable) {}
        try { mediaRecorder?.release() } catch (_: Throwable) {}
        mediaRecorder = null
        recordingActive.set(false)
        recordingFile = null
        recordingStartedAt = 0L
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        cleanupRecording()
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        if (instance == this) instance = null
    }

    fun stop() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        @Volatile var instance: ScreenCaptureService? = null
    }
}
