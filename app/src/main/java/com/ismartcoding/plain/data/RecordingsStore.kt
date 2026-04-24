package com.ismartcoding.plain.data

import com.ismartcoding.plain.MainApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Hidden, app-private storage for recordings. Files live under
 * filesDir/recordings/{video,photo,audio,screen,screenshot}/ and are NOT
 * exposed to MediaStore, gallery, MTP or file managers.
 *
 * Type strings (stable, also used as the GraphQL enum-string and folder name):
 *   - "video"     : camera video recording (.mp4)
 *   - "photo"     : camera still photo    (.jpg)
 *   - "audio"     : microphone recording  (.m4a)
 *   - "screen"    : screen recording      (.mp4)
 *   - "screenshot": screen still capture  (.png)
 */
object RecordingsStore {
    const val TYPE_VIDEO = "video"
    const val TYPE_PHOTO = "photo"
    const val TYPE_AUDIO = "audio"
    const val TYPE_SCREEN = "screen"
    const val TYPE_SCREENSHOT = "screenshot"

    val ALL_TYPES = listOf(TYPE_VIDEO, TYPE_PHOTO, TYPE_AUDIO, TYPE_SCREEN, TYPE_SCREENSHOT)

    private fun rootDir(): File {
        val d = File(MainApp.instance.filesDir, "recordings")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun typeDir(type: String): File {
        require(type in ALL_TYPES) { "unknown recording type: $type" }
        val d = File(rootDir(), type)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun extensionFor(type: String): String = when (type) {
        TYPE_VIDEO, TYPE_SCREEN -> "mp4"
        TYPE_PHOTO -> "jpg"
        TYPE_AUDIO -> "m4a"
        TYPE_SCREENSHOT -> "png"
        else -> "bin"
    }

    fun mimeTypeFor(type: String): String = when (type) {
        TYPE_VIDEO, TYPE_SCREEN -> "video/mp4"
        TYPE_PHOTO -> "image/jpeg"
        TYPE_AUDIO -> "audio/mp4"
        TYPE_SCREENSHOT -> "image/png"
        else -> "application/octet-stream"
    }

    private val FILENAME_SAFE = Regex("[^A-Za-z0-9_.-]")
    private val DATE_FMT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Allocate a unique file inside the appropriate type directory.
     * The provided desired name is sanitized; if blank, a timestamp+random
     * stem is generated so files never collide.
     */
    fun allocateFile(type: String, desiredName: String = ""): File {
        val ext = extensionFor(type)
        val rawStem = if (desiredName.isNotBlank()) {
            // Strip trailing extension if user typed one matching ours.
            desiredName.substringBeforeLast('.', desiredName)
        } else {
            "${type}_${DATE_FMT.format(Date())}_${UUID.randomUUID().toString().take(6)}"
        }
        val safe = FILENAME_SAFE.replace(rawStem, "_").trim('_').ifBlank { UUID.randomUUID().toString() }
        val dir = typeDir(type)
        var candidate = File(dir, "$safe.$ext")
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "${safe}_$i.$ext")
            i++
        }
        return candidate
    }

    /** Total disk usage across every recording type. */
    fun totalBytes(): Long {
        var total = 0L
        for (t in ALL_TYPES) {
            typeDir(t).listFiles()?.forEach { total += it.length() }
        }
        return total
    }

    /** Best-effort delete of a file under the recordings root. */
    fun safeDelete(path: String) {
        if (path.isBlank()) return
        val f = File(path)
        val rootPath = rootDir().absolutePath
        if (!f.absolutePath.startsWith(rootPath)) return
        try { f.delete() } catch (_: Throwable) { /* ignore */ }
    }
}
