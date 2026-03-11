package com.github.thiagokokada.omronsyncer.omron

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.time.LocalDateTime

class OmronOhqProtocolTest {

    @Test
    fun buildWriteCommand_wrapsAddressDataAndChecksum() {
        val command = OmronOhqProtocol.buildWriteCommand(
            address = 0x0054,
            data = byteArrayOf(0x00, 0x01, 0x02, 0x03),
        )

        assertArrayEquals(
            byteArrayOf(
                0x0C,
                0x01,
                0xC0.toByte(),
                0x00,
                0x54,
                0x04,
                0x00,
                0x01,
                0x02,
                0x03,
                0x00,
                0x9D.toByte(),
            ),
            command,
        )
    }

    @Test
    fun buildClockWriteData_preservesSeedAndWritesTimestampChecksum() {
        val clockWriteData = OmronOhqProtocol.buildClockWriteData(
            seedBlock = byteArrayOf(
                0x44,
                0xA4.toByte(),
                0x00,
                0x00,
            ),
            timestamp = LocalDateTime.of(2026, 3, 10, 21, 33, 48),
        )

        assertArrayEquals(
            byteArrayOf(
                0x44,
                0xA4.toByte(),
                0x00,
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x1A,
                0x03,
                0x0A,
                0x15,
                0x21,
                0x30,
                0x76,
                0x00,
            ),
            clockWriteData,
        )
    }
}
