package com.ismartcoding.plain.services.recording

import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ismartcoding.lib.logcat.LogCat
import org.webrtc.VideoFrame
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encodes WebRTC [VideoFrame]s into a hidden MP4 file using MediaCodec
 * (COLOR_FormatYUV420Flexible -> H.264) and muxes them with MediaMuxer.
 *
 * Designed to be used as a side VideoSink on the existing camera VideoTrack
 * so the WebRTC live stream and the local recording can run from the same
 * capturer without opening Camera2 a second time.
 */
class VideoFrameRecorder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int = 30,
    private val bitRate: Int = 4_000_000,
) {
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var ptsBaseNs: Long = 0L
    private var lastPtsUs: Long = -1
    private val started = AtomicBoolean(false)
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile var firstFrameAt: Long = 0L; private set
    @Volatile var lastFrameAt: Long = 0L; private set

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        try {
            // Round to even — H.264 encoders reject odd dimensions.
            val w = (width / 2) * 2
            val h = (height / 2) * 2
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            encoder = enc
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            return true
        } catch (e: Throwable) {
            LogCat.e("VideoFrameRecorder.start failed: ${e.message}")
            cleanup()
            return false
        }
    }

    /** Feed a single VideoFrame; safe to call from the WebRTC capture thread. */
    @Synchronized
    fun onFrame(frame: VideoFrame) {
        val enc = encoder ?: return
        if (firstFrameAt == 0L) firstFrameAt = System.currentTimeMillis()
        lastFrameAt = System.currentTimeMillis()

        val i420 = frame.buffer.toI420() ?: return
        try {
            val inputIdx = enc.dequeueInputBuffer(10_000)
            if (inputIdx >= 0) {
                if (ptsBaseNs == 0L) ptsBaseNs = frame.timestampNs
                var ptsUs = (frame.timestampNs - ptsBaseNs) / 1000
                if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 1
                lastPtsUs = ptsUs

                val image = enc.getInputImage(inputIdx)
                if (image != null) {
                    val planes = image.planes
                    copyPlane(i420.dataY, i420.strideY, planes[0].buffer, planes[0].rowStride, planes[0].pixelStride, width, height)
                    copyPlane(i420.dataU, i420.strideU, planes[1].buffer, planes[1].rowStride, planes[1].pixelStride, width / 2, height / 2)
                    copyPlane(i420.dataV, i420.strideV, planes[2].buffer, planes[2].rowStride, planes[2].pixelStride, width / 2, height / 2)
                    val totalSize = width * height * 3 / 2
                    enc.queueInputBuffer(inputIdx, 0, totalSize, ptsUs, 0)
                } else {
                    enc.queueInputBuffer(inputIdx, 0, 0, ptsUs, 0)
                }
            }
            drainEncoder(false)
        } catch (e: Throwable) {
            LogCat.e("VideoFrameRecorder.onFrame failed: ${e.message}")
        } finally {
            i420.release()
        }
    }

    /**
     * Stops encoding, flushes pending output, finalizes the MP4 container.
     * Returns the actual recorded file size in bytes.
     */
    @Synchronized
    fun stop(): Long {
        if (!started.get()) return 0L
        try {
            // Send EOS so the encoder flushes its buffered frames.
            try {
                val enc = encoder
                if (enc != null) {
                    val inputIdx = enc.dequeueInputBuffer(10_000)
                    if (inputIdx >= 0) enc.queueInputBuffer(inputIdx, 0, 0, lastPtsUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            } catch (_: Throwable) {}
            drainEncoder(true)
        } finally {
            cleanup()
        }
        return runCatching { outputFile.length() }.getOrDefault(0L)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val enc = encoder ?: return
        val mux = muxer ?: return
        loop@ while (true) {
            val outIdx = enc.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return else continue@loop
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw IllegalStateException("format changed twice")
                    val newFormat = enc.outputFormat
                    trackIndex = mux.addTrack(newFormat)
                    mux.start()
                    muxerStarted = true
                }
                outIdx >= 0 -> {
                    val buf: ByteBuffer = enc.getOutputBuffer(outIdx) ?: continue@loop
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        mux.writeSampleData(trackIndex, buf, bufferInfo)
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun cleanup() {
        try { encoder?.stop() } catch (_: Throwable) {}
        try { encoder?.release() } catch (_: Throwable) {}
        encoder = null
        if (muxerStarted) { try { muxer?.stop() } catch (_: Throwable) {} }
        try { muxer?.release() } catch (_: Throwable) {}
        muxer = null
        muxerStarted = false
    }

    companion object {
        /**
         * Copy a YUV plane from a packed source ByteBuffer (stride only) to a
         * destination ByteBuffer that may have non-1 pixelStride (NV12-style
         * layout for U/V planes on some encoders).
         */
        private fun copyPlane(
            src: ByteBuffer,
            srcStride: Int,
            dst: ByteBuffer,
            dstRowStride: Int,
            dstPixelStride: Int,
            widthPx: Int,
            heightPx: Int,
        ) {
            val srcSlice = src.duplicate()
            if (dstPixelStride == 1 && dstRowStride == srcStride && widthPx == srcStride) {
                // Fast path — straight copy.
                srcSlice.position(0)
                srcSlice.limit(srcStride * heightPx)
                dst.position(0)
                dst.put(srcSlice)
                return
            }
            // Per-row copy honoring possible padding and per-pixel stride.
            val rowBuf = ByteArray(srcStride)
            val pixelStride = dstPixelStride
            for (row in 0 until heightPx) {
                srcSlice.position(row * srcStride)
                srcSlice.get(rowBuf, 0, minOf(srcStride, rowBuf.size))
                if (pixelStride == 1) {
                    dst.position(row * dstRowStride)
                    dst.put(rowBuf, 0, widthPx)
                } else {
                    var o = row * dstRowStride
                    for (x in 0 until widthPx) {
                        dst.position(o); dst.put(rowBuf[x]); o += pixelStride
                    }
                }
            }
        }
    }
}

/**
 * Encode a single [VideoFrame] to a JPEG byte array via NV21 + YuvImage.
 * Used for the "Take photo" action on the live camera page.
 */
object VideoFramePhoto {
    fun toJpeg(frame: VideoFrame, quality: Int = 90): ByteArray? {
        val i420 = frame.buffer.toI420() ?: return null
        try {
            val w = frame.buffer.width
            val h = frame.buffer.height
            val nv21 = ByteArray(w * h * 3 / 2)
            // Y plane
            val yBuf = i420.dataY
            for (row in 0 until h) {
                yBuf.position(row * i420.strideY)
                yBuf.get(nv21, row * w, w)
            }
            // VU interleaved (NV21 = Y + V + U interleaved by 2)
            val uBuf = i420.dataU; val vBuf = i420.dataV
            val cw = w / 2; val ch = h / 2
            val uvOffset = w * h
            val uRow = ByteArray(i420.strideU)
            val vRow = ByteArray(i420.strideV)
            for (row in 0 until ch) {
                uBuf.position(row * i420.strideU); uBuf.get(uRow, 0, minOf(i420.strideU, uRow.size))
                vBuf.position(row * i420.strideV); vBuf.get(vRow, 0, minOf(i420.strideV, vRow.size))
                var o = uvOffset + row * w
                for (col in 0 until cw) {
                    nv21[o++] = vRow[col]
                    nv21[o++] = uRow[col]
                }
            }
            val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(android.graphics.Rect(0, 0, w, h), quality, out)
            return out.toByteArray()
        } catch (e: Throwable) {
            LogCat.e("VideoFramePhoto.toJpeg failed: ${e.message}")
            return null
        } finally {
            i420.release()
        }
    }
}
