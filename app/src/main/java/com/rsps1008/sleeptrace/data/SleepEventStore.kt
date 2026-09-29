package com.rsps1008.sleeptrace.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepSegment

/** Indexed, transactional retention for high-churn Sleep API events. */
class SleepEventStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "sleep_events.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE segments (start INTEGER NOT NULL, end INTEGER NOT NULL, confidence INTEGER NOT NULL, source TEXT NOT NULL, PRIMARY KEY(start, end, source))")
        db.execSQL("CREATE TABLE samples (time INTEGER PRIMARY KEY, confidence INTEGER NOT NULL, motion INTEGER NOT NULL, light INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun import(segments: List<SleepSegment>, samples: List<ClassificationSample>) { append(segments, samples) }
    fun append(segments: List<SleepSegment> = emptyList(), samples: List<ClassificationSample> = emptyList(), now: Long = System.currentTimeMillis()) {
        writableDatabase.transaction {
            segments.forEach { item ->
                insertWithOnConflict("segments", null, ContentValues().apply { put("start", item.startMillis); put("end", item.endMillis); put("confidence", item.confidence); put("source", item.source) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            samples.forEach { item ->
                insertWithOnConflict("samples", null, ContentValues().apply { put("time", item.timeMillis); put("confidence", item.confidence); put("motion", item.motion); put("light", item.light) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            delete("segments", "end < ?", arrayOf((now - RETENTION).toString()))
            delete("samples", "time < ?", arrayOf((now - RETENTION).toString()))
        }
    }
    fun segments(): List<SleepSegment> = readableDatabase.query("segments", null, null, null, null, null, "start ASC").use { c -> buildList { while (c.moveToNext()) add(SleepSegment(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3))) } }
    fun samples(): List<ClassificationSample> = readableDatabase.query("samples", null, null, null, null, null, "time ASC").use { c -> buildList { while (c.moveToNext()) add(ClassificationSample(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3))) } }
    companion object { private const val RETENTION = 14L * 24 * 60 * 60 * 1000 }
}
