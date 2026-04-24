package com.ismartcoding.plain.services

import com.ismartcoding.lib.channel.sendEvent
import com.ismartcoding.lib.helpers.JsonHelper
import com.ismartcoding.plain.events.EventType
import com.ismartcoding.plain.events.WebSocketEvent
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class TimelineEntryData(
    val id: Long,
    val type: String,
    val title: String,
    val subtitle: String,
    val appId: String,
    val appName: String,
    val time: Long,
)

object TimelineHelper {
    private const val MAX = 1000
    val serverStartedAt: Long = System.currentTimeMillis()
    private val list = ArrayDeque<TimelineEntryData>()
    private val lock = Any()
    private val seq = AtomicLong(0)

    fun add(type: String, title: String, subtitle: String = "", appId: String = "", appName: String = "", time: Long = System.currentTimeMillis()) {
        val e = TimelineEntryData(seq.incrementAndGet(), type, title, subtitle, appId, appName, time)
        synchronized(lock) {
            list.addLast(e)
            while (list.size > MAX) list.removeFirst()
        }
        try {
            sendEvent(WebSocketEvent(EventType.TIMELINE_EVENT, JsonHelper.jsonEncode(e)))
        } catch (_: Throwable) {}
    }

    fun all(limit: Int = 500): List<TimelineEntryData> = synchronized(lock) {
        if (list.size <= limit) list.toList() else list.drop(list.size - limit)
    }

    fun clear() = synchronized(lock) { list.clear() }
}
