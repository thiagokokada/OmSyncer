package com.github.thiagokokada.omronsyncer.omron

import java.time.LocalDateTime

internal object OmronOhqProtocol {
    const val PAIRING_SETTINGS_READ_ADDRESS = 0x0010
    const val PAIRING_SETTINGS_READ_SIZE = 0x2C
    const val PAIRING_CLOCK_SEED_READ_ADDRESS = 0x003C
    const val PAIRING_CLOCK_SEED_READ_SIZE = 0x18
    const val PAIRING_SETTINGS_WRITE_ADDRESS = 0x0054
    const val PAIRING_CLOCK_WRITE_ADDRESS = 0x0080

    fun buildWriteCommand(address: Int, data: ByteArray): ByteArray {
        require(data.size <= 0xFF) {
            "Write payload is too large: ${data.size} bytes."
        }

        val payload = ByteArray(data.size + 8)
        payload[0] = payload.size.toByte()
        payload[1] = 0x01
        payload[2] = 0xC0.toByte()
        payload[3] = ((address shr 8) and 0xFF).toByte()
        payload[4] = (address and 0xFF).toByte()
        payload[5] = data.size.toByte()
        data.copyInto(payload, destinationOffset = 6)

        var checksum = 0
        for (index in 0 until payload.lastIndex) {
            checksum = checksum xor payload[index].toInt().and(0xFF)
        }
        payload[payload.lastIndex] = checksum.toByte()
        return payload
    }

    fun buildClockWriteData(seedBlock: ByteArray, timestamp: LocalDateTime): ByteArray {
        require(seedBlock.size >= 4) {
            "Clock seed block was too short: ${seedBlock.size} bytes."
        }

        val year = timestamp.year - 2000
        require(year in 0..0xFF) {
            "Clock year is out of range for OHQ pairing: ${timestamp.year}"
        }

        val data = byteArrayOf(
            seedBlock[0],
            seedBlock[1],
            seedBlock[2],
            seedBlock[3],
            0x01,
            0x00,
            0x00,
            0x00,
            year.toByte(),
            timestamp.monthValue.toByte(),
            timestamp.dayOfMonth.toByte(),
            timestamp.hour.toByte(),
            timestamp.minute.toByte(),
            timestamp.second.toByte(),
            0x00,
            0x00,
        )
        val checksum = data
            .take(data.lastIndex - 1)
            .fold(0) { acc, byte -> (acc + byte.toInt().and(0xFF)) and 0xFF }
        data[data.lastIndex - 1] = checksum.toByte()
        return data
    }
}
