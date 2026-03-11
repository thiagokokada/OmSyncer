package com.github.thiagokokada.omronsyncer.omron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.UUID

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

    @Test
    fun findById_unknownModelFallsBackToDefault() {
        val model = OmronDeviceRegistry.findById("does_not_exist")

        assertSame(OmronDeviceRegistry.defaultModel(), model)
    }

    @Test
    fun supportedModels_shareExpectedFe4aService() {
        val expectedService = UUID.fromString("0000fe4a-0000-1000-8000-00805f9b34fb")

        assertEquals(
            listOf(expectedService, expectedService, expectedService, expectedService),
            OmronDeviceRegistry.supportedModels.map { it.serviceUuid },
        )
    }

    @Test
    fun supportedModels_shareExpectedFe4aContinuationAndCacheSettings() {
        val expectedContinuation = UUID.fromString("4d0bf320-aee8-11e1-a0d9-0002a5d5c51b")

        assertEquals(
            listOf(
                expectedContinuation,
                expectedContinuation,
                expectedContinuation,
                expectedContinuation,
            ),
            OmronDeviceRegistry.supportedModels.map { it.rxContinuationUuid },
        )
        assertEquals(
            listOf(true, true, true, true),
            OmronDeviceRegistry.supportedModels.map { it.clearGattCacheOnDisconnect },
        )
    }

    @Test
    fun hem7380T1_exposesExpectedAppPairingMetadata() {
        val model = OmronDeviceRegistry.findById("hem_7380t1")

        assertEquals(
            UUID.fromString("b305b680-aee7-11e1-a730-0002a5d5c51b"),
            model.pairingBootstrapUuid,
        )
        assertEquals(OmronPairingWorkflow.OHQ_SESSION_FINALIZATION, model.pairingWorkflow)
        assertEquals(
            "0080008000800080710000800800000080808080808080800001010001000000030000",
            model.pairingSetupWriteHex,
        )
        assertEquals(true, model.syncSessionHandshakeEnabled)
        assertTrue(model.supportsAppPairingStep)
    }

    @Test
    fun experimentalFe4aModels_doNotExposeAppPairingStep() {
        val models = listOf(
            OmronDeviceRegistry.findById("hem_7155t_v2"),
            OmronDeviceRegistry.findById("hem_7155t_v3"),
            OmronDeviceRegistry.findById("hem_7146t"),
        )

        assertTrue(models.all { !it.supportsAppPairingStep })
    }

    @Test
    fun parseMeasurement_rejectsInvalidTimestamp() {
        val measurement = OmronRecordParser.parseMeasurement(
            device = OmronDeviceRegistry.findById("hem_7380t1"),
            user = 1,
            recordBytes = byteArrayOf(
                0x5D,
                0x4D,
                0x3D,
                0x1A,
                0x3F,
                0x4D,
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

        assertNull(measurement)
    }

    @Test
    fun parseMeasurement_rejectsOutOfRangeRawSystolic() {
        val measurement = OmronRecordParser.parseMeasurement(
            device = OmronDeviceRegistry.findById("hem_7380t1"),
            user = 1,
            recordBytes = byteArrayOf(
                0xF0.toByte(),
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

        assertNull(measurement)
    }
}
