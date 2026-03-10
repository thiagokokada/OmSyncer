package com.github.thiagokokada.omronsyncer.omron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SyncCaptureTest {

    @Test
    fun parseFixtureText_replaysSampleFixtureAgainstCurrentParser() {
        assertFixtureReplays("fixtures/hem_7380t1_capture.txt")
    }

    @Test
    fun parseFixtureText_replaysRealFixtureAgainstCurrentParser() {
        val capture = assertFixtureReplays("fixtures/hem_7380t1_real_capture_20260310.txt")

        assertEquals(200, capture.records.size)
        assertEquals(91, capture.records.count { it.measurement != null })
    }

    @Test
    fun fixture_roundTripsThroughTextCodec() {
        val capture = loadFixture("fixtures/hem_7380t1_capture.txt")

        val reparsed = SyncCapture.parseFixtureText(capture.asFixtureText())

        assertEquals(capture, reparsed)
        assertNotNull(reparsed.deviceAddress)
        assertEquals(2, reparsed.packets.size)
    }

    private fun loadFixture(path: String): SyncCapture {
        val text = checkNotNull(javaClass.classLoader?.getResource(path)) {
            "Missing test fixture: $path"
        }.readText()
        return SyncCapture.parseFixtureText(text)
    }

    private fun assertFixtureReplays(path: String): SyncCapture {
        val capture = loadFixture(path)
        val model = OmronDeviceRegistry.findById(capture.modelId)

        capture.records.forEach { record ->
            val replayed = OmronRecordParser.parseMeasurement(
                device = model,
                user = record.user,
                recordBytes = record.recordBytes(),
            )

            assertEquals(
                record.measurement?.toMeasurement(record.user),
                replayed,
            )
        }

        return capture
    }
}
