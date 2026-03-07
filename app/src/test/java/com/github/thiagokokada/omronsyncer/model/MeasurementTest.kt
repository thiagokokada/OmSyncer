package com.github.thiagokokada.omronsyncer.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class MeasurementTest {

    @Test
    fun flagsLabel_returnsDashWhenNoFlags() {
        val measurement = measurement(
            irregularHeartbeat = false,
            movement = false,
        )

        assertEquals("-", measurement.flagsLabel())
    }

    @Test
    fun flagsLabel_returnsAllActiveFlags() {
        val measurement = measurement(
            irregularHeartbeat = true,
            movement = true,
        )

        assertEquals("IHB/MOV", measurement.flagsLabel())
    }

    private fun measurement(
        irregularHeartbeat: Boolean,
        movement: Boolean,
    ) = Measurement(
        user = 1,
        recordedAt = LocalDateTime.of(2026, 3, 7, 9, 30),
        systolic = 120,
        diastolic = 80,
        pulse = 64,
        irregularHeartbeat = irregularHeartbeat,
        movement = movement,
    )
}
