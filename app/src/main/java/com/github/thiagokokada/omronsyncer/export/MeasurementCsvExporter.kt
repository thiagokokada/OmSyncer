package com.github.thiagokokada.omronsyncer.export

import com.github.thiagokokada.omronsyncer.model.Measurement
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MeasurementCsvExporter {

    fun export(outputStream: OutputStream, measurements: List<Measurement>) {
        outputStream.bufferedWriter().use { writer ->
            writer.appendLine("recorded_at,user,systolic,diastolic,pulse,irregular_heartbeat,movement,tru_read_merged")
            measurements.forEach { measurement ->
                writer.appendLine(
                    listOf(
                        CSV_TIME_FORMATTER.format(measurement.recordedAt),
                        measurement.user.toString(),
                        measurement.systolic.toString(),
                        measurement.diastolic.toString(),
                        measurement.pulse.toString(),
                        measurement.irregularHeartbeat.toString(),
                        measurement.movement.toString(),
                        measurement.isTruReadMerged.toString(),
                    ).joinToString(","),
                )
            }
        }
    }

    fun suggestedFileName(
        now: LocalDateTime = LocalDateTime.now(),
        prefix: String = "omsyncer-measurements",
        extension: String = "csv",
    ): String {
        return "$prefix-${FILE_NAME_FORMATTER.format(now)}.$extension"
    }

    private companion object {
        val CSV_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val FILE_NAME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
