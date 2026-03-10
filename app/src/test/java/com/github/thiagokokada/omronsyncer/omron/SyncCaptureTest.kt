package com.github.thiagokokada.omronsyncer.omron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SyncCaptureTest {

    @Test
    fun parseFixtureText_replaysCapturedRecordsAgainstCurrentParser() {
        val capture = loadFixture("fixtures/hem_7380t1_capture.txt")
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
}
