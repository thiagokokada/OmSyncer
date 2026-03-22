package com.github.thiagokokada.omronsyncer.export

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
            listOf(
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
        )

        assertEquals(
            """
            recorded_at,user,systolic,diastolic,pulse,irregular_heartbeat,movement,tru_read_merged
            2026-03-07 11:42:03,1,118,77,61,true,false,false
            2026-03-06 22:15:00,2,124,81,66,false,true,true
            
            """.trimIndent(),
            output.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun export_writesHeaderForEmptyMeasurementList() {
        val output = ByteArrayOutputStream()

        exporter.export(output, emptyList())

        assertEquals(
            "recorded_at,user,systolic,diastolic,pulse,irregular_heartbeat,movement,tru_read_merged\n",
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
