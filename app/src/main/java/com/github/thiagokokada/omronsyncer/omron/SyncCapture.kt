package com.github.thiagokokada.omronsyncer.omron

import com.github.thiagokokada.omronsyncer.model.Measurement
import java.time.LocalDateTime

data class SyncCapture(
    val modelId: String,
    val modelCode: String,
    val deviceName: String?,
    val deviceAddress: String?,
    val packets: List<SyncPacketCapture>,
    val records: List<SyncRecordCapture>,
) {
    fun asFixtureText(): String {
        return buildString {
            appendLine(FORMAT_HEADER)
            appendLine("model_id=$modelId")
            appendLine("model_code=$modelCode")
            appendLine("device_name=${deviceName.orEmpty()}")
            appendLine("device_address=${deviceAddress.orEmpty()}")
            packets.forEach { packet ->
                appendLine("packet|${packet.direction.wireValue}|${packet.hex}")
            }
            records.forEach { record ->
                append("record|${record.user}|0x${record.address.toString(16)}|${record.hex}")
                val measurement = record.measurement
                if (measurement != null) {
                    append(
                        "|" +
                            "${measurement.recordedAt}|" +
                            "${measurement.systolic}|" +
                            "${measurement.diastolic}|" +
                            "${measurement.pulse}|" +
                            "${if (measurement.irregularHeartbeat) 1 else 0}|" +
                            "${if (measurement.movement) 1 else 0}",
                    )
                }
                appendLine()
            }
        }
    }

    companion object {
        const val FORMAT_HEADER = "OMSYNCER_SYNC_CAPTURE_V1"

        fun parseFixtureText(text: String): SyncCapture {
            val lines = text.lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()
            require(lines.firstOrNull() == FORMAT_HEADER) {
                "Unsupported sync capture format."
            }

            var modelId = ""
            var modelCode = ""
            var deviceName: String? = null
            var deviceAddress: String? = null
            val packets = mutableListOf<SyncPacketCapture>()
            val records = mutableListOf<SyncRecordCapture>()

            lines.drop(1).forEach { line ->
                when {
                    line.startsWith("model_id=") -> modelId = line.substringAfter('=')
                    line.startsWith("model_code=") -> modelCode = line.substringAfter('=')
                    line.startsWith("device_name=") ->
                        deviceName = line.substringAfter('=').ifBlank { null }

                    line.startsWith("device_address=") ->
                        deviceAddress = line.substringAfter('=').ifBlank { null }

                    line.startsWith("packet|") -> {
                        val parts = line.split('|')
                        require(parts.size == 3) { "Invalid packet line: $line" }
                        packets += SyncPacketCapture(
                            direction = SyncPacketDirection.fromWireValue(parts[1]),
                            hex = parts[2],
                        )
                    }

                    line.startsWith("record|") -> {
                        val parts = line.split('|')
                        require(parts.size == 4 || parts.size == 10) { "Invalid record line: $line" }
                        records += SyncRecordCapture(
                            user = parts[1].toInt(),
                            address = parts[2].removePrefix("0x").toInt(16),
                            hex = parts[3],
                            measurement = if (parts.size == 10) {
                                CapturedMeasurement(
                                    recordedAt = LocalDateTime.parse(parts[4]),
                                    systolic = parts[5].toInt(),
                                    diastolic = parts[6].toInt(),
                                    pulse = parts[7].toInt(),
                                    irregularHeartbeat = parts[8] == "1",
                                    movement = parts[9] == "1",
                                )
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            require(modelId.isNotBlank()) { "Sync capture is missing model_id." }
            require(modelCode.isNotBlank()) { "Sync capture is missing model_code." }
            return SyncCapture(
                modelId = modelId,
                modelCode = modelCode,
                deviceName = deviceName,
                deviceAddress = deviceAddress,
                packets = packets,
                records = records,
            )
        }
    }
}

data class SyncPacketCapture(
    val direction: SyncPacketDirection,
    val hex: String,
)

enum class SyncPacketDirection(val wireValue: String) {
    TX("tx"),
    RX("rx");

    companion object {
        fun fromWireValue(value: String): SyncPacketDirection {
            return entries.firstOrNull { it.wireValue == value }
                ?: error("Unknown packet direction: $value")
        }
    }
}

data class SyncRecordCapture(
    val user: Int,
    val address: Int,
    val hex: String,
    val measurement: CapturedMeasurement?,
) {
    fun recordBytes(): ByteArray {
        require(hex.length % 2 == 0) {
            "Record hex must contain an even number of characters."
        }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}

data class CapturedMeasurement(
    val recordedAt: LocalDateTime,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    val irregularHeartbeat: Boolean,
    val movement: Boolean,
) {
    fun toMeasurement(user: Int): Measurement {
        return Measurement(
            user = user,
            recordedAt = recordedAt,
            systolic = systolic,
            diastolic = diastolic,
            pulse = pulse,
            irregularHeartbeat = irregularHeartbeat,
            movement = movement,
        )
    }
}
