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

    fun loadAll(): List<Measurement> {
        val db = helper.readableDatabase
        db.query(
            TABLE_MEASUREMENTS,
            PROJECTION,
            null,
            null,
            null,
            null,
            "$COLUMN_RECORDED_AT DESC",
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        Measurement(
                            user = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_USER)),
                            recordedAt = LocalDateTime.parse(
                                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_RECORDED_AT)),
                                STORED_TIME_FORMATTER,
                            ),
                            systolic = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SYSTOLIC)),
                            diastolic = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_DIASTOLIC)),
                            pulse = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_PULSE)),
                            irregularHeartbeat = cursor.getInt(
                                cursor.getColumnIndexOrThrow(COLUMN_IRREGULAR_HEARTBEAT),
                            ) == 1,
                            movement = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_MOVEMENT)) == 1,
                        ),
                    )
                }
            }
        }
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

    private fun Measurement.toContentValues(): ContentValues {
        return ContentValues().apply {
            put(COLUMN_USER, user)
            put(COLUMN_RECORDED_AT, STORED_TIME_FORMATTER.format(recordedAt))
            put(COLUMN_SYSTOLIC, systolic)
            put(COLUMN_DIASTOLIC, diastolic)
            put(COLUMN_PULSE, pulse)
            put(COLUMN_IRREGULAR_HEARTBEAT, if (irregularHeartbeat) 1 else 0)
            put(COLUMN_MOVEMENT, if (movement) 1 else 0)
        }
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
            db.execSQL("DROP TABLE IF EXISTS $TABLE_MEASUREMENTS")
            onCreate(db)
        }
    }

    private companion object {
        const val DATABASE_NAME = "measurements.db"
        const val DATABASE_VERSION = 1

        const val TABLE_MEASUREMENTS = "measurements"
        const val COLUMN_ID = "_id"
        const val COLUMN_USER = "user_id"
        const val COLUMN_RECORDED_AT = "recorded_at"
        const val COLUMN_SYSTOLIC = "systolic"
        const val COLUMN_DIASTOLIC = "diastolic"
        const val COLUMN_PULSE = "pulse"
        const val COLUMN_IRREGULAR_HEARTBEAT = "irregular_heartbeat"
        const val COLUMN_MOVEMENT = "movement"

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
