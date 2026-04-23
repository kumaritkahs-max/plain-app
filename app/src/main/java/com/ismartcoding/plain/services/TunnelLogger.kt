package com.ismartcoding.plain.services

import android.content.Context
import android.os.Build
import com.ismartcoding.lib.logcat.LogCat
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TunnelLogger {
    private const val MAX_BYTES = 512 * 1024
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        val dir = File(context.filesDir, "cloudflared").apply { mkdirs() }
        file = File(dir, "tunnel.log")
    }

    fun logFile(context: Context): File {
        init(context)
        return file!!
    }

    fun read(context: Context, maxLines: Int = 500): String {
        val f = logFile(context)
        if (!f.exists()) return ""
        val all = f.readLines()
        return if (all.size <= maxLines) all.joinToString("\n")
        else all.takeLast(maxLines).joinToString("\n")
    }

    fun readAll(context: Context): String {
        val f = logFile(context)
        if (!f.exists()) return ""
        return f.readText()
    }

    fun clear(context: Context) {
        try { logFile(context).writeText("") } catch (_: Throwable) {}
    }

    fun i(tag: String, msg: String) = write("INFO ", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("WARN ", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("ERROR", tag, msg, t)
    fun d(tag: String, msg: String) = write("DEBUG", tag, msg, null)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val line = "${ts.format(Date())} $level [$tag] $msg" +
            (t?.let { "\n${stack(it)}" } ?: "")
        when (level) {
            "ERROR" -> LogCat.e("[$tag] $msg ${t?.message ?: ""}")
            "WARN " -> LogCat.e("[$tag] $msg")
            else -> LogCat.d("[$tag] $msg")
        }
        val f = file ?: return
        try {
            synchronized(this) {
                f.appendText(line + "\n")
                if (f.length() > MAX_BYTES) {
                    val keep = f.readLines().takeLast(400)
                    f.writeText(keep.joinToString("\n") + "\n")
                }
            }
        } catch (_: Throwable) {}
    }

    private fun stack(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    fun deviceSnapshot(context: Context): String {
        val ai = context.applicationInfo
        val pi = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Throwable) { null }
        val sb = StringBuilder()
        sb.appendLine("=== device ===")
        sb.appendLine("manufacturer = ${Build.MANUFACTURER}")
        sb.appendLine("brand        = ${Build.BRAND}")
        sb.appendLine("model        = ${Build.MODEL}")
        sb.appendLine("device       = ${Build.DEVICE}")
        sb.appendLine("product      = ${Build.PRODUCT}")
        sb.appendLine("android sdk  = ${Build.VERSION.SDK_INT} (release ${Build.VERSION.RELEASE})")
        sb.appendLine("supported abis = ${Build.SUPPORTED_ABIS.joinToString(",")}")
        sb.appendLine("=== app ===")
        sb.appendLine("packageName  = ${context.packageName}")
        sb.appendLine("versionName  = ${pi?.versionName}")
        sb.appendLine("versionCode  = ${pi?.longVersionCode}")
        sb.appendLine("nativeLibDir = ${ai.nativeLibraryDir}")
        sb.appendLine("filesDir     = ${context.filesDir.absolutePath}")
        sb.appendLine("cacheDir     = ${context.cacheDir.absolutePath}")
        try {
            val nl = File(ai.nativeLibraryDir)
            sb.appendLine("nativeLibDir contents:")
            nl.listFiles()?.forEach {
                sb.appendLine("  ${it.name}  size=${it.length()}  exec=${it.canExecute()}  read=${it.canRead()}")
            } ?: sb.appendLine("  <null>")
        } catch (t: Throwable) {
            sb.appendLine("  <error listing: ${t.message}>")
        }
        return sb.toString()
    }
}
