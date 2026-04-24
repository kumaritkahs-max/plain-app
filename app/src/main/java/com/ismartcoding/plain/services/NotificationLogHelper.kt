package com.ismartcoding.plain.services

import com.ismartcoding.plain.data.DNotification

object NotificationLogHelper {
    private const val MAX = 1000
    private val list = ArrayDeque<DNotification>()
    private val lock = Any()

    fun record(n: DNotification) {
        synchronized(lock) {
            list.addLast(n)
            while (list.size > MAX) list.removeFirst()
        }
    }

    fun all(): List<DNotification> = synchronized(lock) { list.toList() }

    fun clear() = synchronized(lock) { list.clear() }
}
