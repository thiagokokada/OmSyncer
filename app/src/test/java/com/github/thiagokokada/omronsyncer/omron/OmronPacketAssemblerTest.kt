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
    fun appendFragment_dropsTrailingChannelPaddingFromLegacyResponse() {
        val assembler = OmronPacketAssembler()
        // Legacy monitor: a 24-byte response delivered as two 16-byte channel
        // notifications, the second carrying 8 real bytes plus 8 bytes of padding.
        val primary = hexToBytes("188000000010000000940144ffffffff")
        val continuation = hexToBytes("ffffffffffff00590000000000000000")
        val expectedPacket = hexToBytes("188000000010000000940144ffffffffffffffffffff0059")

        val firstResult = assembler.appendFragment(fragment = primary, startsPacket = true)
        val secondResult = assembler.appendFragment(fragment = continuation, startsPacket = false)

        assertEquals(0, firstResult.size)
        assertEquals(1, secondResult.size)
        assertArrayEquals(expectedPacket, secondResult.single())
    }

    @Test
    fun appendFragment_reassemblesConsecutivePaddedResponses() {
        val assembler = OmronPacketAssembler()
        val primary = hexToBytes("188000000010000000940144ffffffff")
        val continuation = hexToBytes("ffffffffffff00590000000000000000")
        val expectedPacket = hexToBytes("188000000010000000940144ffffffffffffffffffff0059")

        assembler.appendFragment(fragment = primary, startsPacket = true)
        assembler.appendFragment(fragment = continuation, startsPacket = false)
        // A second response must reassemble cleanly after the first one's padding.
        assembler.appendFragment(fragment = primary, startsPacket = true)
        val secondResponse = assembler.appendFragment(fragment = continuation, startsPacket = false)

        assertEquals(1, secondResponse.size)
        assertArrayEquals(expectedPacket, secondResponse.single())
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
