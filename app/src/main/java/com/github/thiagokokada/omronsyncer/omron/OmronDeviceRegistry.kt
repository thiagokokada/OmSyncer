package com.github.thiagokokada.omronsyncer.omron

import com.github.thiagokokada.omronsyncer.model.Measurement
import java.time.DateTimeException
import java.time.LocalDateTime
import java.util.UUID

data class OmronDeviceDefinition(
    val id: String,
    val modelCode: String,
    val marketedName: String,
    val verificationLevel: VerificationLevel,
    val serviceUuid: UUID,
    val txUuid: UUID,
    val rxUuid: UUID,
    val userLayouts: List<OmronUserLayout>,
    val recordSizeBytes: Int,
    val recordParser: OmronRecordParserDefinition,
) {
    val userCount: Int get() = userLayouts.size
}

data class OmronUserLayout(
    val user: Int,
    val startAddress: Int,
    val recordCount: Int,
)

data class OmronRecordParserDefinition(
    val endianness: RecordEndianness,
    val systolic: BitField,
    val diastolic: BitField,
    val pulse: BitField,
    val year: BitField,
    val month: BitField,
    val day: BitField,
    val hour: BitField,
    val minute: BitField,
    val second: BitField,
    val irregularHeartbeat: BitField,
    val movement: BitField,
    val systolicOffset: Int = 25,
)

data class BitField(
    val byte: Int,
    val bit: Int,
    val length: Int,
)

enum class VerificationLevel {
    VERIFIED,
    EXPERIMENTAL,
}

enum class RecordEndianness {
    LITTLE,
    WORD_SWAPPED,
}

object OmronDeviceRegistry {
    private val FE4A_SERVICE_UUID: UUID =
        UUID.fromString("0000fe4a-0000-1000-8000-00805f9b34fb")
    private val FE4A_TX_UUID: UUID =
        UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b")
    private val FE4A_RX_UUID: UUID =
        UUID.fromString("49123040-aee8-11e1-a74d-0002a5d5c51b")

    private val FE4A_LITTLE_ENDIAN_PARSER = OmronRecordParserDefinition(
        endianness = RecordEndianness.LITTLE,
        systolic = BitField(byte = 0, bit = 0, length = 8),
        diastolic = BitField(byte = 1, bit = 0, length = 8),
        pulse = BitField(byte = 2, bit = 0, length = 8),
        year = BitField(byte = 3, bit = 0, length = 6),
        month = BitField(byte = 4, bit = 10, length = 4),
        day = BitField(byte = 4, bit = 5, length = 5),
        hour = BitField(byte = 4, bit = 0, length = 5),
        minute = BitField(byte = 6, bit = 6, length = 6),
        second = BitField(byte = 6, bit = 0, length = 6),
        irregularHeartbeat = BitField(byte = 4, bit = 14, length = 1),
        movement = BitField(byte = 4, bit = 15, length = 1),
    )

    val supportedModels: List<OmronDeviceDefinition> = listOf(
        hem7380T1(),
        hem7155TV2(),
        hem7155TV3(),
        hem7146T(),
    )

    fun defaultModel(): OmronDeviceDefinition = supportedModels.first()

    fun findById(id: String?): OmronDeviceDefinition {
        return supportedModels.firstOrNull { it.id == id } ?: defaultModel()
    }

    private fun hem7380T1() = OmronDeviceDefinition(
        id = "hem_7380t1",
        modelCode = "HEM-7380T1",
        marketedName = "X7 Smart AFib / M7 Intelli IT AFib",
        verificationLevel = VerificationLevel.VERIFIED,
        serviceUuid = FE4A_SERVICE_UUID,
        txUuid = FE4A_TX_UUID,
        rxUuid = FE4A_RX_UUID,
        userLayouts = listOf(
            OmronUserLayout(user = 1, startAddress = 0x01C4, recordCount = 100),
            OmronUserLayout(user = 2, startAddress = 0x0804, recordCount = 100),
        ),
        recordSizeBytes = 0x10,
        recordParser = FE4A_LITTLE_ENDIAN_PARSER,
    )

    private fun hem7155TV2() = OmronDeviceDefinition(
        id = "hem_7155t_v2",
        modelCode = "HEM-7155T (V2)",
        marketedName = "M4/M400 Intelli IT, X4 Smart",
        verificationLevel = VerificationLevel.EXPERIMENTAL,
        serviceUuid = FE4A_SERVICE_UUID,
        txUuid = FE4A_TX_UUID,
        rxUuid = FE4A_RX_UUID,
        userLayouts = listOf(
            OmronUserLayout(user = 1, startAddress = 0x0098, recordCount = 60),
            OmronUserLayout(user = 2, startAddress = 0x0458, recordCount = 60),
        ),
        recordSizeBytes = 0x10,
        recordParser = FE4A_LITTLE_ENDIAN_PARSER,
    )

    private fun hem7155TV3() = OmronDeviceDefinition(
        id = "hem_7155t_v3",
        modelCode = "HEM-7155T (V3)",
        marketedName = "M4/M400 Intelli IT, X4 Smart",
        verificationLevel = VerificationLevel.EXPERIMENTAL,
        serviceUuid = FE4A_SERVICE_UUID,
        txUuid = FE4A_TX_UUID,
        rxUuid = FE4A_RX_UUID,
        userLayouts = listOf(
            OmronUserLayout(user = 1, startAddress = 0x02E8, recordCount = 60),
            OmronUserLayout(user = 2, startAddress = 0x06A8, recordCount = 60),
        ),
        recordSizeBytes = 0x10,
        recordParser = FE4A_LITTLE_ENDIAN_PARSER,
    )

    private fun hem7146T() = OmronDeviceDefinition(
        id = "hem_7146t",
        modelCode = "HEM-7146T",
        marketedName = "M2 Intelli IT+, X2 Smart+",
        verificationLevel = VerificationLevel.EXPERIMENTAL,
        serviceUuid = FE4A_SERVICE_UUID,
        txUuid = FE4A_TX_UUID,
        rxUuid = FE4A_RX_UUID,
        userLayouts = listOf(
            OmronUserLayout(user = 1, startAddress = 0x02E8, recordCount = 30),
        ),
        recordSizeBytes = 0x0E,
        recordParser = FE4A_LITTLE_ENDIAN_PARSER,
    )

}

object OmronRecordParser {
    fun parseMeasurement(
        device: OmronDeviceDefinition,
        user: Int,
        recordBytes: ByteArray,
    ): Measurement? {
        if (recordBytes.size != device.recordSizeBytes || recordBytes.all { it == EMPTY_BYTE }) {
            return null
        }

        val parser = device.recordParser
        val normalizedBytes = normalize(recordBytes, parser.endianness)
        val rawSystolic = extractField(normalizedBytes, parser.systolic)
        if (rawSystolic > MAX_VALID_RAW_SYSTOLIC) {
            return null
        }

        val year = 2000 + extractField(normalizedBytes, parser.year)
        val month = extractField(normalizedBytes, parser.month)
        val day = extractField(normalizedBytes, parser.day)
        val hour = extractField(normalizedBytes, parser.hour)
        val minute = extractField(normalizedBytes, parser.minute)
        val second = minOf(extractField(normalizedBytes, parser.second), 59)

        val recordedAt = try {
            LocalDateTime.of(year, month, day, hour, minute, second)
        } catch (_: DateTimeException) {
            return null
        }

        return Measurement(
            user = user,
            recordedAt = recordedAt,
            systolic = rawSystolic + parser.systolicOffset,
            diastolic = extractField(normalizedBytes, parser.diastolic),
            pulse = extractField(normalizedBytes, parser.pulse),
            irregularHeartbeat = extractField(normalizedBytes, parser.irregularHeartbeat) == 1,
            movement = extractField(normalizedBytes, parser.movement) == 1,
        )
    }

    private fun normalize(
        sourceBytes: ByteArray,
        endianness: RecordEndianness,
    ): ByteArray {
        if (endianness == RecordEndianness.LITTLE) {
            return sourceBytes
        }

        val normalized = sourceBytes.copyOf()
        var index = 0
        while (index + 1 < normalized.size) {
            val first = normalized[index]
            normalized[index] = normalized[index + 1]
            normalized[index + 1] = first
            index += 2
        }
        return normalized
    }

    private fun extractField(sourceBytes: ByteArray, bitField: BitField): Int {
        val startBit = (bitField.byte * 8) + bitField.bit
        var value = 0
        repeat(bitField.length) { offset ->
            val absoluteBit = startBit + offset
            val byteValue = sourceBytes[absoluteBit / 8].toInt() and 0xFF
            val bitValue = (byteValue shr (absoluteBit % 8)) and 0x01
            value = value or (bitValue shl offset)
        }
        return value
    }

    private const val MAX_VALID_RAW_SYSTOLIC = 0xE1
    private const val EMPTY_BYTE: Byte = 0xFF.toByte()
}
