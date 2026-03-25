package com.github.thiagokokada.omronsyncer.export

import com.github.thiagokokada.omronsyncer.BloodPressureClassificationScheme
import com.github.thiagokokada.omronsyncer.model.Measurement
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime

class MeasurementCsvExporterTest {

    private val exporter = MeasurementCsvExporter()

    @Test
    fun export_writesExpectedCsvRows() {
        val output = ByteArrayOutputStream()

        exporter.export(
            output,
            measurements = listOf(
                Measurement(
                    user = 1,
                    recordedAt = LocalDateTime.of(2026, 3, 7, 11, 42, 3),
                    systolic = 118,
                    diastolic = 77,
                    pulse = 61,
                    irregularHeartbeat = true,
                    movement = false,
                ),
                Measurement(
                    user = 2,
                    recordedAt = LocalDateTime.of(2026, 3, 6, 22, 15, 0),
                    systolic = 124,
                    diastolic = 81,
                    pulse = 66,
                    irregularHeartbeat = false,
                    movement = true,
                    isTruReadMerged = true,
                ),
            ),
            classificationScheme = BloodPressureClassificationScheme.JNC7,
        )

        assertEquals(
            """
            recorded_at,user,systolic,diastolic,pulse,irregular_heartbeat,movement,tru_read_merged,blood_pressure_category
            2026-03-07 11:42:03,1,118,77,61,true,false,false,jnc7_normal
            2026-03-06 22:15:00,2,124,81,66,false,true,true,jnc7_prehypertension
            
            """.trimIndent(),
            output.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun export_writesHeaderForEmptyMeasurementList() {
        val output = ByteArrayOutputStream()

        exporter.export(
            outputStream = output,
            measurements = emptyList(),
            classificationScheme = BloodPressureClassificationScheme.DISABLED,
        )

        assertEquals(
            "recorded_at,user,systolic,diastolic,pulse,irregular_heartbeat,movement,tru_read_merged,blood_pressure_category\n",
            output.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun export_leavesCategoryEmptyWhenClassificationDisabled() {
        val output = ByteArrayOutputStream()

        exporter.export(
            outputStream = output,
            measurements = listOf(
                Measurement(
                    user = 1,
                    recordedAt = LocalDateTime.of(2026, 3, 7, 11, 42, 3),
                    systolic = 124,
                    diastolic = 81,
                    pulse = 61,
                    irregularHeartbeat = false,
                    movement = false,
                ),
            ),
            classificationScheme = BloodPressureClassificationScheme.DISABLED,
        )

        assertEquals(
            """
            recorded_at,user,systolic,diastolic,pulse,irregular_heartbeat,movement,tru_read_merged,blood_pressure_category
            2026-03-07 11:42:03,1,124,81,61,false,false,false,
            
            """.trimIndent(),
            output.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun suggestedFileName_usesProvidedPrefixAndExtension() {
        val fileName = exporter.suggestedFileName(
            now = LocalDateTime.of(2026, 3, 7, 16, 10, 5),
            prefix = "health-connect-export",
            extension = "txt",
        )

        assertEquals("health-connect-export-20260307-161005.txt", fileName)
    }
}
