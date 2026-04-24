package com.ismartcoding.plain.services.recording

import android.media.MediaRecorder
import android.os.Build
import com.ismartcoding.lib.logcat.LogCat
import com.ismartcoding.plain.MainApp
import java.io.File

/**
 * Wraps an Android MediaRecorder configured for AAC-in-MP4 (.m4a) audio
 * recording. Independent of WebRTC — audio capture works whether or not
 * the live mic stream is also active.
 */
class AudioRecorderHandle(
    private val outputFile: File,
    private val sampleRate: Int = 44_100,
    private val bitRate: Int = 128_000,
    audioSource: Int = MediaRecorder.AudioSource.MIC,
) {
    private var recorder: MediaRecorder? = null
    private var startedAt: Long = 0L
    private val source = audioSource

    fun start(): Boolean {
        try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(MainApp.instance)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            rec.apply {
                setAudioSource(source)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(sampleRate)
                setAudioEncodingBitRate(bitRate)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            startedAt = System.currentTimeMillis()
            return true
        } catch (e: Throwable) {
            LogCat.e("AudioRecorderHandle.start failed: ${e.message}")
            cleanup()
            return false
        }
    }

    /** Returns the actual elapsed duration in ms. */
    fun stop(): Long {
        val elapsed = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
        try { recorder?.stop() } catch (_: Throwable) {}
        cleanup()
        return elapsed
    }

    private fun cleanup() {
        try { recorder?.reset() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
    }
}
