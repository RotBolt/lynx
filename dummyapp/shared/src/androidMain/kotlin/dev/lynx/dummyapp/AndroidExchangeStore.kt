package dev.lynx.dummyapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class AndroidExchangeStore(context: Context) : ExchangeStore {
    private val helper = Helper(context.applicationContext)
    private val events = MutableSharedFlow<ExchangeResult>(extraBufferCapacity = 64)

    override suspend fun append(result: ExchangeResult) {
        helper.writableDatabase.insertOrThrow("network_events", null, ContentValues().apply {
            put("transport", result.transport.name)
            put("method", result.method)
            put("url", result.url)
            result.status?.let { put("status", it) }
            put("request_body", result.requestBody)
            put("response_body", result.responseBody)
            put("error", result.error)
            put("started_at", result.startedAtEpochMillis)
            put("completed_at", result.completedAtEpochMillis)
        })
        events.tryEmit(result)
    }

    override fun observe(): Flow<ExchangeResult> = events

    fun close() = helper.close()

    private class Helper(context: Context) : SQLiteOpenHelper(context, "dummyapp.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE network_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    transport TEXT NOT NULL,
                    method TEXT NOT NULL,
                    url TEXT NOT NULL,
                    status INTEGER,
                    request_body TEXT,
                    response_body TEXT,
                    error TEXT,
                    started_at INTEGER NOT NULL,
                    completed_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
