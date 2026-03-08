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
            val measurements = mutableListOf<Measurement>()
            while (cursor.moveToNext()) {
                measurements += Measurement(
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
                )
            }
            return measurements
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
        var inserted = 0
        val insertedMeasurements = mutableListOf<Measurement>()
        db.transaction {
            try {
                measurements.forEach { measurement ->
                    val values = ContentValues().apply {
                        put(COLUMN_USER, measurement.user)
                        put(
                            COLUMN_RECORDED_AT,
                            STORED_TIME_FORMATTER.format(measurement.recordedAt)
                        )
                        put(COLUMN_SYSTOLIC, measurement.systolic)
                        put(COLUMN_DIASTOLIC, measurement.diastolic)
                        put(COLUMN_PULSE, measurement.pulse)
                        put(
                            COLUMN_IRREGULAR_HEARTBEAT,
                            if (measurement.irregularHeartbeat) 1 else 0
                        )
                        put(COLUMN_MOVEMENT, if (measurement.movement) 1 else 0)
                    }

                    val rowId = insertWithOnConflict(
                        TABLE_MEASUREMENTS,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                    if (rowId != -1L) {
                        inserted += 1
                        insertedMeasurements += measurement
                    }
                }
            } finally {
            }
        }

        return SaveSummary(
            imported = measurements.size,
            inserted = inserted,
            duplicates = measurements.size - inserted,
            insertedMeasurements = insertedMeasurements,
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
