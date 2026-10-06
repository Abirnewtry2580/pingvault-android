package com.pingvault.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

data class ArchivedNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    val postedAt: Long,
    val mediaPath: String?,
    val mediaMime: String?,
    val saved: Boolean
)

class NotificationStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "pingvault.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE notifications (
                notification_key TEXT PRIMARY KEY,
                package_name TEXT NOT NULL,
                app_name TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                posted_at INTEGER NOT NULL,
                media_path TEXT,
                media_mime TEXT,
                saved INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_notifications_date ON notifications(posted_at DESC)")
        db.execSQL("CREATE INDEX idx_notifications_package ON notifications(package_name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun put(item: ArchivedNotification) {
        val previouslySaved = readableDatabase.rawQuery(
            "SELECT saved FROM notifications WHERE notification_key = ?",
            arrayOf(item.key)
        ).use { it.moveToFirst() && it.getInt(0) != 0 }
        val values = ContentValues().apply {
            put("notification_key", item.key)
            put("package_name", item.packageName)
            put("app_name", item.appName)
            put("title", item.title)
            put("body", item.body)
            put("posted_at", item.postedAt)
            put("media_path", item.mediaPath)
            put("media_mime", item.mediaMime)
            put("saved", if (item.saved || previouslySaved) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("notifications", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun list(search: String = "", savedOnly: Boolean = false, packageFilter: String? = null): List<ArchivedNotification> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (savedOnly) where += "saved = 1"
        if (!packageFilter.isNullOrBlank()) {
            where += "package_name = ?"
            args += packageFilter
        }
        if (search.isNotBlank()) {
            where += "(title LIKE ? OR body LIKE ? OR app_name LIKE ?)"
            val q = "%$search%"
            args += listOf(q, q, q)
        }
        val sql = "SELECT * FROM notifications" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY posted_at DESC LIMIT 1000"
        val rows = mutableListOf<ArchivedNotification>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                rows += ArchivedNotification(
                    key = c.getString(0), packageName = c.getString(1), appName = c.getString(2),
                    title = c.getString(3), body = c.getString(4), postedAt = c.getLong(5),
                    mediaPath = c.getString(6), mediaMime = c.getString(7), saved = c.getInt(8) != 0
                )
            }
        }
        return rows
    }

    fun appFilters(): List<Pair<String, String>> {
        val apps = mutableListOf<Pair<String, String>>()
        readableDatabase.rawQuery(
            "SELECT package_name, app_name FROM notifications GROUP BY package_name, app_name ORDER BY app_name COLLATE NOCASE",
            null
        ).use { c ->
            while (c.moveToNext()) apps += c.getString(1) to c.getString(0)
        }
        return apps
    }

    @Synchronized
    fun clearAll() {
        val paths = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT media_path FROM notifications WHERE media_path IS NOT NULL", null).use { c ->
            while (c.moveToNext()) paths += c.getString(0)
        }
        writableDatabase.delete("notifications", null, null)
        paths.distinct().forEach { runCatching { File(it).delete() } }
    }

    @Synchronized
    fun toggleSaved(key: String) {
        writableDatabase.execSQL(
            "UPDATE notifications SET saved = CASE saved WHEN 1 THEN 0 ELSE 1 END WHERE notification_key = ?",
            arrayOf(key)
        )
    }

    @Synchronized
    fun delete(key: String) {
        val path = readableDatabase.rawQuery(
            "SELECT media_path FROM notifications WHERE notification_key = ?", arrayOf(key)
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        writableDatabase.delete("notifications", "notification_key = ?", arrayOf(key))
        path?.let { runCatching { File(it).delete() } }
    }

    companion object {
        @Volatile private var instance: NotificationStore? = null
        fun get(context: Context): NotificationStore =
            instance ?: synchronized(this) {
                instance ?: NotificationStore(context).also { instance = it }
            }
    }
}
