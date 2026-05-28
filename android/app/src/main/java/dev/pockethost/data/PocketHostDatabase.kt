package dev.pockethost.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.pockethost.model.LogLine

class PocketHostDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "pockethost.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_epoch_ms INTEGER NOT NULL,
                service_id TEXT NOT NULL,
                level TEXT NOT NULL,
                message TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_logs_time ON logs(timestamp_epoch_ms DESC)")
        db.execSQL(
            """
            CREATE TABLE service_settings (
                service_id TEXT PRIMARY KEY,
                enabled INTEGER NOT NULL DEFAULT 0,
                autostart INTEGER NOT NULL DEFAULT 0,
                port INTEGER,
                args TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 only.
    }

    fun insertLog(line: LogLine) {
        val values = ContentValues().apply {
            put("timestamp_epoch_ms", line.timestampEpochMs)
            put("service_id", line.serviceId)
            put("level", line.level)
            put("message", line.message)
        }
        writableDatabase.insert("logs", null, values)
        pruneLogs(5_000)
    }

    fun pruneLogs(maxRows: Int) {
        if (maxRows <= 0) return
        writableDatabase.execSQL(
            """
            DELETE FROM logs
            WHERE id NOT IN (
                SELECT id FROM logs ORDER BY timestamp_epoch_ms DESC, id DESC LIMIT ?
            )
            """.trimIndent(),
            arrayOf<Any>(maxRows)
        )
    }

    fun recentLogs(limit: Int = 300): List<LogLine> {
        val out = mutableListOf<LogLine>()
        readableDatabase.rawQuery(
            "SELECT timestamp_epoch_ms, service_id, level, message FROM logs ORDER BY timestamp_epoch_ms DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += LogLine(
                    timestampEpochMs = cursor.getLong(0),
                    serviceId = cursor.getString(1),
                    level = cursor.getString(2),
                    message = cursor.getString(3)
                )
            }
        }
        return out
    }

    companion object {
        @Volatile private var instance: PocketHostDatabase? = null

        fun get(context: Context): PocketHostDatabase =
            instance ?: synchronized(this) {
                instance ?: PocketHostDatabase(context).also { instance = it }
            }
    }
}
