package com.ismartcoding.plain.services

import android.content.Context
import android.content.SharedPreferences
import com.ismartcoding.plain.MainApp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Persistent registry of blocked apps + parental control rules.
 *
 * Stores everything in a single SharedPreferences file as JSON to keep schema flexible.
 *
 * - blocked: set of package names that are always-blocked.
 * - timeLimits: package -> daily allowed milliseconds. When usage exceeds, app is killed on launch.
 * - usageToday: package -> milliseconds used today (resets at midnight).
 * - bedtime: { enabled, startMinutes, endMinutes, packages: [pkg, …] }
 *      startMinutes/endMinutes are minutes from midnight in the device's local time.
 *      If start > end, the window wraps past midnight.
 * - launchHistory: ring buffer of {package, ts}, max 500 entries.
 */
object AppBlockHelper {
    private const val PREFS = "plain_app_block"
    private const val K_BLOCKED = "blocked"
    private const val K_TIME_LIMITS = "time_limits"
    private const val K_USAGE = "usage_today"
    private const val K_USAGE_DAY = "usage_today_day"
    private const val K_BEDTIME = "bedtime"
    private const val K_HISTORY = "launch_history"
    private const val MAX_HISTORY = 500

    private fun prefs(ctx: Context = MainApp.instance): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Blocked apps ----

    fun isBlocked(pkg: String, ctx: Context = MainApp.instance): Boolean =
        getBlockedSet(ctx).contains(pkg)

    fun getBlockedSet(ctx: Context = MainApp.instance): Set<String> =
        prefs(ctx).getStringSet(K_BLOCKED, emptySet()) ?: emptySet()

    fun setBlocked(pkg: String, blocked: Boolean, ctx: Context = MainApp.instance) {
        val set = getBlockedSet(ctx).toMutableSet()
        if (blocked) set.add(pkg) else set.remove(pkg)
        prefs(ctx).edit().putStringSet(K_BLOCKED, set).apply()
    }

    // ---- Time limits ----

    data class TimeLimit(val pkg: String, val dailyMs: Long, val usedMs: Long)

    fun getTimeLimits(ctx: Context = MainApp.instance): Map<String, Long> {
        val raw = prefs(ctx).getString(K_TIME_LIMITS, "{}") ?: "{}"
        val obj = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        val map = mutableMapOf<String, Long>()
        obj.keys().forEach { k -> map[k] = obj.optLong(k, 0L) }
        return map
    }

    fun setTimeLimit(pkg: String, dailyMs: Long, ctx: Context = MainApp.instance) {
        val limits = getTimeLimits(ctx).toMutableMap()
        if (dailyMs <= 0) limits.remove(pkg) else limits[pkg] = dailyMs
        val obj = JSONObject()
        limits.forEach { (k, v) -> obj.put(k, v) }
        prefs(ctx).edit().putString(K_TIME_LIMITS, obj.toString()).apply()
    }

    fun getUsageToday(ctx: Context = MainApp.instance): Map<String, Long> {
        rotateUsageIfNeeded(ctx)
        val raw = prefs(ctx).getString(K_USAGE, "{}") ?: "{}"
        val obj = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        val map = mutableMapOf<String, Long>()
        obj.keys().forEach { k -> map[k] = obj.optLong(k, 0L) }
        return map
    }

    fun addUsage(pkg: String, deltaMs: Long, ctx: Context = MainApp.instance) {
        if (deltaMs <= 0) return
        rotateUsageIfNeeded(ctx)
        val map = getUsageToday(ctx).toMutableMap()
        map[pkg] = (map[pkg] ?: 0L) + deltaMs
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs(ctx).edit().putString(K_USAGE, obj.toString()).apply()
    }

    fun isOverLimit(pkg: String, ctx: Context = MainApp.instance): Boolean {
        val limit = getTimeLimits(ctx)[pkg] ?: return false
        if (limit <= 0) return false
        val used = getUsageToday(ctx)[pkg] ?: 0L
        return used >= limit
    }

    /** Usage map including the in-progress slice of the currently foregrounded app. */
    fun getUsageTodayWithLive(currentForegroundPkg: String?, lastEnteredAt: Long, ctx: Context = MainApp.instance): Map<String, Long> {
        val map = getUsageToday(ctx).toMutableMap()
        if (!currentForegroundPkg.isNullOrEmpty() && lastEnteredAt > 0) {
            val delta = (System.currentTimeMillis() - lastEnteredAt).coerceAtLeast(0L)
            if (delta in 100..6 * 60 * 60 * 1000L) {
                map[currentForegroundPkg] = (map[currentForegroundPkg] ?: 0L) + delta
            }
        }
        return map
    }

    fun removeTimeLimit(pkg: String, ctx: Context = MainApp.instance) {
        setTimeLimit(pkg, 0L, ctx)
    }

    private fun rotateUsageIfNeeded(ctx: Context) {
        val today = todayKey()
        val stored = prefs(ctx).getString(K_USAGE_DAY, null)
        if (stored != today) {
            prefs(ctx).edit()
                .putString(K_USAGE, "{}")
                .putString(K_USAGE_DAY, today)
                .apply()
        }
    }

    private fun todayKey(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
    }

    // ---- Bedtime ----

    data class Bedtime(
        val enabled: Boolean,
        val startMinutes: Int,
        val endMinutes: Int,
        val packages: List<String>,
    )

    fun getBedtime(ctx: Context = MainApp.instance): Bedtime {
        val raw = prefs(ctx).getString(K_BEDTIME, null) ?: return Bedtime(false, 22 * 60, 7 * 60, emptyList())
        return try {
            val o = JSONObject(raw)
            val pkgs = mutableListOf<String>()
            val arr = o.optJSONArray("packages") ?: JSONArray()
            for (i in 0 until arr.length()) pkgs.add(arr.getString(i))
            Bedtime(
                enabled = o.optBoolean("enabled", false),
                startMinutes = o.optInt("startMinutes", 22 * 60),
                endMinutes = o.optInt("endMinutes", 7 * 60),
                packages = pkgs,
            )
        } catch (_: Exception) {
            Bedtime(false, 22 * 60, 7 * 60, emptyList())
        }
    }

    fun setBedtime(bedtime: Bedtime, ctx: Context = MainApp.instance) {
        val o = JSONObject()
        o.put("enabled", bedtime.enabled)
        o.put("startMinutes", bedtime.startMinutes)
        o.put("endMinutes", bedtime.endMinutes)
        val arr = JSONArray()
        bedtime.packages.forEach { arr.put(it) }
        o.put("packages", arr)
        prefs(ctx).edit().putString(K_BEDTIME, o.toString()).apply()
    }

    fun isInBedtime(pkg: String, ctx: Context = MainApp.instance): Boolean {
        val b = getBedtime(ctx)
        if (!b.enabled || pkg !in b.packages) return false
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (b.startMinutes <= b.endMinutes) {
            now in b.startMinutes until b.endMinutes
        } else {
            now >= b.startMinutes || now < b.endMinutes
        }
    }

    // ---- Launch history ----

    data class LaunchEntry(val pkg: String, val ts: Long)

    fun getHistory(ctx: Context = MainApp.instance): List<LaunchEntry> {
        val raw = prefs(ctx).getString(K_HISTORY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = mutableListOf<LaunchEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(LaunchEntry(o.optString("pkg"), o.optLong("ts")))
        }
        return out
    }

    fun recordLaunch(pkg: String, ctx: Context = MainApp.instance) {
        val list = getHistory(ctx).toMutableList()
        list.add(LaunchEntry(pkg, System.currentTimeMillis()))
        while (list.size > MAX_HISTORY) list.removeAt(0)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("pkg", it.pkg).put("ts", it.ts)) }
        prefs(ctx).edit().putString(K_HISTORY, arr.toString()).apply()
    }

    fun clearHistory(ctx: Context = MainApp.instance) {
        prefs(ctx).edit().remove(K_HISTORY).apply()
    }

    /**
     * Decide whether [pkg] should be blocked right now (for any reason).
     * Returns the human-readable reason or null if allowed.
     */
    fun blockReason(pkg: String, ctx: Context = MainApp.instance): String? {
        if (isBlocked(pkg, ctx)) return "blocked"
        if (isOverLimit(pkg, ctx)) return "time_limit"
        if (isInBedtime(pkg, ctx)) return "bedtime"
        return null
    }
}
