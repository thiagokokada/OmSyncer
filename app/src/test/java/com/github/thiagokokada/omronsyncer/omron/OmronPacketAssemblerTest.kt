package com.github.thiagokokada.omronsyncer.omron

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OmronPacketAssemblerTest {

    @Test
    fun appendFragment_returnsSinglePacketWhenNotificationIsComplete() {
        val assembler = OmronPacketAssembler()
        val packet = hexToBytes("0880000000100098")

        val packets = assembler.appendFragment(
            fragment = packet,
            startsPacket = true,
        )

        assertEquals(1, packets.size)
        assertArrayEquals(packet, packets.single())
    }

    @Test
    fun appendFragment_reassemblesPacketAcrossPrimaryAndContinuationNotifications() {
        val assembler = OmronPacketAssembler()
        val packet = hexToBytes("18810001c410654b661a310cb5560000130000018c00005e")

        val firstFragment = packet.copyOfRange(0, 20)
        val secondFragment = packet.copyOfRange(20, packet.size)

        val firstResult = assembler.appendFragment(
            fragment = firstFragment,
            startsPacket = true,
        )
        val secondResult = assembler.appendFragment(
            fragment = secondFragment,
            startsPacket = false,
        )

        assertEquals(0, firstResult.size)
        assertEquals(1, secondResult.size)
        assertArrayEquals(packet, secondResult.single())
    }

    @Test
    fun appendFragment_ignoresOrphanContinuationNotification() {
        val assembler = OmronPacketAssembler()

        val packets = assembler.appendFragment(
            fragment = byteArrayOf(0x01, 0x02, 0x03),
            startsPacket = false,
        )

        assertEquals(0, packets.size)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) {
            "Hex string must have an even length."
        }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
