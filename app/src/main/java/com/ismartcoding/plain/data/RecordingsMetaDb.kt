package com.ismartcoding.plain.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ismartcoding.plain.MainApp
import java.util.UUID

data class DRecording(
    val id: String,
    val type: String,
    val name: String,
    val note: String,
    val tags: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val createdAt: Long,
    val filePath: String,
)

data class DRecordingsStats(
    val total: Int,
    val totalBytes: Long,
    val byType: Map<String, Int>,
)

/**
 * Stand-alone SQLite store for hidden recordings metadata. Kept separate
 * from the main Room database so the schema can evolve independently and
 * ship without migrations on existing installs.
 */
object RecordingsMetaDb {
    private const val DB_NAME = "recordings_meta.db"
    private const val DB_VERSION = 1
    private const val TABLE = "recordings"

    private class Helper : SQLiteOpenHelper(MainApp.instance, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    id TEXT PRIMARY KEY,
                    type TEXT NOT NULL,
                    name TEXT NOT NULL DEFAULT '',
                    note TEXT NOT NULL DEFAULT '',
                    tags TEXT NOT NULL DEFAULT '',
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    size_bytes INTEGER NOT NULL DEFAULT 0,
                    width INTEGER NOT NULL DEFAULT 0,
                    height INTEGER NOT NULL DEFAULT 0,
                    mime_type TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    file_path TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_recordings_type ON $TABLE(type)")
            db.execSQL("CREATE INDEX idx_recordings_created ON $TABLE(created_at DESC)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // No upgrades yet.
        }
    }

    @Volatile private var helper: Helper? = null
    private fun db(): SQLiteDatabase {
        val h = helper ?: synchronized(this) {
            helper ?: Helper().also { helper = it }
        }
        return h.writableDatabase
    }

    private fun cursorToRow(c: android.database.Cursor): DRecording {
        return DRecording(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            type = c.getString(c.getColumnIndexOrThrow("type")),
            name = c.getString(c.getColumnIndexOrThrow("name")) ?: "",
            note = c.getString(c.getColumnIndexOrThrow("note")) ?: "",
            tags = c.getString(c.getColumnIndexOrThrow("tags")) ?: "",
            durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms")),
            sizeBytes = c.getLong(c.getColumnIndexOrThrow("size_bytes")),
            width = c.getInt(c.getColumnIndexOrThrow("width")),
            height = c.getInt(c.getColumnIndexOrThrow("height")),
            mimeType = c.getString(c.getColumnIndexOrThrow("mime_type")) ?: "",
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            filePath = c.getString(c.getColumnIndexOrThrow("file_path")) ?: "",
        )
    }

    fun insert(
        type: String,
        filePath: String,
        name: String = "",
        note: String = "",
        tags: String = "",
        durationMs: Long = 0,
        sizeBytes: Long = 0,
        width: Int = 0,
        height: Int = 0,
        mimeType: String = RecordingsStore.mimeTypeFor(type),
        createdAt: Long = System.currentTimeMillis(),
    ): DRecording {
        val id = UUID.randomUUID().toString()
        val cv = ContentValues().apply {
            put("id", id); put("type", type)
            put("name", name); put("note", note); put("tags", tags)
            put("duration_ms", durationMs); put("size_bytes", sizeBytes)
            put("width", width); put("height", height); put("mime_type", mimeType)
            put("created_at", createdAt); put("file_path", filePath)
        }
        db().insert(TABLE, null, cv)
        return DRecording(id, type, name, note, tags, durationMs, sizeBytes, width, height, mimeType, createdAt, filePath)
    }

    fun get(id: String): DRecording? {
        if (id.isBlank()) return null
        db().query(TABLE, null, "id = ?", arrayOf(id), null, null, null).use { c ->
            return if (c.moveToFirst()) cursorToRow(c) else null
        }
    }

    fun list(type: String? = null, offset: Int = 0, limit: Int = 200): List<DRecording> {
        val sel = if (type.isNullOrBlank()) null else "type = ?"
        val args = if (type.isNullOrBlank()) null else arrayOf(type)
        val out = mutableListOf<DRecording>()
        db().query(TABLE, null, sel, args, null, null, "created_at DESC", "$offset,$limit").use { c ->
            while (c.moveToNext()) out.add(cursorToRow(c))
        }
        return out
    }

    fun count(type: String? = null): Int {
        val sel = if (type.isNullOrBlank()) "" else " WHERE type = ?"
        val args = if (type.isNullOrBlank()) null else arrayOf(type)
        db().rawQuery("SELECT COUNT(*) FROM $TABLE$sel", args).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun stats(): DRecordingsStats {
        val byType = mutableMapOf<String, Int>()
        var total = 0
        var bytes = 0L
        db().rawQuery("SELECT type, COUNT(*), COALESCE(SUM(size_bytes),0) FROM $TABLE GROUP BY type", null).use { c ->
            while (c.moveToNext()) {
                val t = c.getString(0); val n = c.getInt(1); val b = c.getLong(2)
                byType[t] = n; total += n; bytes += b
            }
        }
        return DRecordingsStats(total, bytes, byType)
    }

    fun update(id: String, name: String? = null, note: String? = null, tags: String? = null): DRecording? {
        val current = get(id) ?: return null
        val cv = ContentValues()
        if (name != null) cv.put("name", name)
        if (note != null) cv.put("note", note)
        if (tags != null) cv.put("tags", tags)
        if (cv.size() > 0) db().update(TABLE, cv, "id = ?", arrayOf(id))
        return get(id) ?: current
    }

    fun updateMeta(id: String, durationMs: Long? = null, sizeBytes: Long? = null, width: Int? = null, height: Int? = null) {
        val cv = ContentValues()
        if (durationMs != null) cv.put("duration_ms", durationMs)
        if (sizeBytes != null) cv.put("size_bytes", sizeBytes)
        if (width != null) cv.put("width", width)
        if (height != null) cv.put("height", height)
        if (cv.size() > 0) db().update(TABLE, cv, "id = ?", arrayOf(id))
    }

    fun delete(id: String): Boolean {
        val row = get(id) ?: return false
        RecordingsStore.safeDelete(row.filePath)
        return db().delete(TABLE, "id = ?", arrayOf(id)) > 0
    }

    fun deleteMany(ids: Collection<String>): Int {
        var n = 0
        for (id in ids) if (delete(id)) n++
        return n
    }
}
