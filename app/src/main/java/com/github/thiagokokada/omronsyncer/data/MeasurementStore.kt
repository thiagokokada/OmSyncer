package com.github.thiagokokada.omronsyncer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.github.thiagokokada.omronsyncer.model.Measurement
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MeasurementStore(context: Context) {

    private val helper = MeasurementDatabaseHelper(context)

    fun loadAll(user: Int? = null): List<Measurement> {
        return loadByDeletedState(user = user, deleted = false)
    }

    fun loadDeleted(user: Int? = null): List<Measurement> {
        return loadByDeletedState(user = user, deleted = true)
    }

    fun saveAll(measurements: List<Measurement>): SaveSummary {
        if (measurements.isEmpty()) {
            return SaveSummary(
                imported = 0,
                inserted = 0,
                duplicates = 0,
                insertedMeasurements = emptyList(),
            )
        }

        val db = helper.writableDatabase
        val insertedMeasurements = db.transaction {
            measurements.mapNotNull { measurement ->
                val rowId = insertWithOnConflict(
                    TABLE_MEASUREMENTS,
                    null,
                    measurement.toContentValues(),
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                measurement.takeIf { rowId != -1L }
            }
        }
        val insertedCount = insertedMeasurements.size

        return SaveSummary(
            imported = measurements.size,
            inserted = insertedCount,
            duplicates = measurements.size - insertedCount,
            insertedMeasurements = insertedMeasurements,
        )
    }

    fun softDelete(measurement: Measurement) {
        updateDeletedState(listOf(measurement), deleted = true)
    }

    fun undelete(measurement: Measurement) {
        updateDeletedState(listOf(measurement), deleted = false)
    }

    private fun Measurement.toContentValues(): ContentValues {
        return ContentValues().apply {
            put(COLUMN_USER, user)
            put(COLUMN_RECORDED_AT, STORED_TIME_FORMATTER.format(recordedAt))
            put(COLUMN_SYSTOLIC, systolic)
            put(COLUMN_DIASTOLIC, diastolic)
            put(COLUMN_PULSE, pulse)
            put(COLUMN_IRREGULAR_HEARTBEAT, if (irregularHeartbeat) 1 else 0)
            put(COLUMN_MOVEMENT, if (movement) 1 else 0)
            put(COLUMN_DELETED, 0)
            putNull(COLUMN_DELETED_AT)
        }
    }

    private fun loadByDeletedState(user: Int?, deleted: Boolean): List<Measurement> {
        val db = helper.readableDatabase
        val selectionParts = mutableListOf("$COLUMN_DELETED = ?")
        val selectionArgs = mutableListOf(if (deleted) "1" else "0")
        if (user != null) {
            selectionParts += "$COLUMN_USER = ?"
            selectionArgs += user.toString()
        }
        db.query(
            TABLE_MEASUREMENTS,
            PROJECTION,
            selectionParts.joinToString(" AND "),
            selectionArgs.toTypedArray(),
            null,
            null,
            "$COLUMN_RECORDED_AT DESC",
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toMeasurement())
                }
            }
        }
    }

    private fun updateDeletedState(measurements: List<Measurement>, deleted: Boolean) {
        if (measurements.isEmpty()) {
            return
        }

        val db = helper.writableDatabase
        val deletedAt = if (deleted) STORED_TIME_FORMATTER.format(LocalDateTime.now()) else null
        db.transaction {
            measurements.forEach { measurement ->
                update(
                    TABLE_MEASUREMENTS,
                    ContentValues().apply {
                        put(COLUMN_DELETED, if (deleted) 1 else 0)
                        if (deletedAt == null) {
                            putNull(COLUMN_DELETED_AT)
                        } else {
                            put(COLUMN_DELETED_AT, deletedAt)
                        }
                    },
                    """
                    $COLUMN_USER = ? AND
                    $COLUMN_RECORDED_AT = ? AND
                    $COLUMN_SYSTOLIC = ? AND
                    $COLUMN_DIASTOLIC = ? AND
                    $COLUMN_PULSE = ? AND
                    $COLUMN_IRREGULAR_HEARTBEAT = ? AND
                    $COLUMN_MOVEMENT = ?
                    """.trimIndent().replace("\n", " "),
                    arrayOf(
                        measurement.user.toString(),
                        STORED_TIME_FORMATTER.format(measurement.recordedAt),
                        measurement.systolic.toString(),
                        measurement.diastolic.toString(),
                        measurement.pulse.toString(),
                        if (measurement.irregularHeartbeat) "1" else "0",
                        if (measurement.movement) "1" else "0",
                    ),
                )
            }
        }
    }

    private fun android.database.Cursor.toMeasurement(): Measurement {
        return Measurement(
            user = getInt(getColumnIndexOrThrow(COLUMN_USER)),
            recordedAt = LocalDateTime.parse(
                getString(getColumnIndexOrThrow(COLUMN_RECORDED_AT)),
                STORED_TIME_FORMATTER,
            ),
            systolic = getInt(getColumnIndexOrThrow(COLUMN_SYSTOLIC)),
            diastolic = getInt(getColumnIndexOrThrow(COLUMN_DIASTOLIC)),
            pulse = getInt(getColumnIndexOrThrow(COLUMN_PULSE)),
            irregularHeartbeat = getInt(getColumnIndexOrThrow(COLUMN_IRREGULAR_HEARTBEAT)) == 1,
            movement = getInt(getColumnIndexOrThrow(COLUMN_MOVEMENT)) == 1,
        )
    }

    data class SaveSummary(
        val imported: Int,
        val inserted: Int,
        val duplicates: Int,
        val insertedMeasurements: List<Measurement>,
    )

    private class MeasurementDatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_MEASUREMENTS (
                    $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COLUMN_USER INTEGER NOT NULL,
                    $COLUMN_RECORDED_AT TEXT NOT NULL,
                    $COLUMN_SYSTOLIC INTEGER NOT NULL,
                    $COLUMN_DIASTOLIC INTEGER NOT NULL,
                    $COLUMN_PULSE INTEGER NOT NULL,
                    $COLUMN_IRREGULAR_HEARTBEAT INTEGER NOT NULL,
                    $COLUMN_MOVEMENT INTEGER NOT NULL,
                    $COLUMN_DELETED INTEGER NOT NULL DEFAULT 0,
                    $COLUMN_DELETED_AT TEXT,
                    UNIQUE (
                        $COLUMN_USER,
                        $COLUMN_RECORDED_AT,
                        $COLUMN_SYSTOLIC,
                        $COLUMN_DIASTOLIC,
                        $COLUMN_PULSE,
                        $COLUMN_IRREGULAR_HEARTBEAT,
                        $COLUMN_MOVEMENT
                    ) ON CONFLICT IGNORE
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL(
                    "ALTER TABLE $TABLE_MEASUREMENTS ADD COLUMN $COLUMN_DELETED INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE $TABLE_MEASUREMENTS ADD COLUMN $COLUMN_DELETED_AT TEXT",
                )
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "measurements.db"
        const val DATABASE_VERSION = 2

        const val TABLE_MEASUREMENTS = "measurements"
        const val COLUMN_ID = "_id"
        const val COLUMN_USER = "user_id"
        const val COLUMN_RECORDED_AT = "recorded_at"
        const val COLUMN_SYSTOLIC = "systolic"
        const val COLUMN_DIASTOLIC = "diastolic"
        const val COLUMN_PULSE = "pulse"
        const val COLUMN_IRREGULAR_HEARTBEAT = "irregular_heartbeat"
        const val COLUMN_MOVEMENT = "movement"
        const val COLUMN_DELETED = "deleted"
        const val COLUMN_DELETED_AT = "deleted_at"

        val PROJECTION = arrayOf(
            COLUMN_USER,
            COLUMN_RECORDED_AT,
            COLUMN_SYSTOLIC,
            COLUMN_DIASTOLIC,
            COLUMN_PULSE,
            COLUMN_IRREGULAR_HEARTBEAT,
            COLUMN_MOVEMENT,
        )

        val STORED_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
