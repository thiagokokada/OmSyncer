package com.github.thiagokokada.omronsyncer.omron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class OmronDeviceRegistryTest {

    @Test
    fun supportedModels_matchExpectedNoPairingFe4aBatch() {
        assertEquals(
            listOf("hem_7380t1", "hem_7155t_v2", "hem_7155t_v3", "hem_7146t"),
            OmronDeviceRegistry.supportedModels.map { it.id },
        )
    }

    @Test
    fun parseMeasurement_parsesHem7380T1Record() {
        val measurement = OmronRecordParser.parseMeasurement(
            device = OmronDeviceRegistry.findById("hem_7380t1"),
            user = 1,
            recordBytes = byteArrayOf(
                0x5D,
                0x4D,
                0x3D,
                0x1A,
                0xEB.toByte(),
                0x4C,
                0x83.toByte(),
                0x0A,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
            ),
        )

        assertNotNull(measurement)
        assertEquals(
            LocalDateTime.of(2026, 3, 7, 11, 42, 3),
            measurement?.recordedAt,
        )
        assertEquals(118, measurement?.systolic)
        assertEquals(77, measurement?.diastolic)
        assertEquals(61, measurement?.pulse)
        assertEquals(true, measurement?.irregularHeartbeat)
        assertEquals(false, measurement?.movement)
    }

    @Test
    fun parseMeasurement_parsesHem7146TRecord() {
        val measurement = OmronRecordParser.parseMeasurement(
            device = OmronDeviceRegistry.findById("hem_7146t"),
            user = 1,
            recordBytes = byteArrayOf(
                0x63,
                0x51,
                0x42,
                0x1A,
                0xA6.toByte(),
                0x8C.toByte(),
                0xC0.toByte(),
                0x03,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
            ),
        )

        assertNotNull(measurement)
        assertEquals(
            LocalDateTime.of(2026, 3, 5, 6, 15, 0),
            measurement?.recordedAt,
        )
        assertEquals(124, measurement?.systolic)
        assertEquals(81, measurement?.diastolic)
        assertEquals(66, measurement?.pulse)
        assertEquals(false, measurement?.irregularHeartbeat)
        assertEquals(true, measurement?.movement)
    }

    @Test
    fun parseMeasurement_ignoresEmptyRecord() {
        val measurement = OmronRecordParser.parseMeasurement(
            device = OmronDeviceRegistry.findById("hem_7155t_v2"),
            user = 1,
            recordBytes = ByteArray(16) { 0xFF.toByte() },
        )

        assertNull(measurement)
    }

    @Test
    fun singleUserModel_reportsOneUser() {
        val model = OmronDeviceRegistry.findById("hem_7146t")

        assertEquals(1, model.userCount)
        assertFalse(model.userLayouts.size > 1)
    }
}
