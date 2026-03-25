package com.github.thiagokokada.omronsyncer

import com.github.thiagokokada.omronsyncer.model.Measurement
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class BloodPressureClassifierTest {

    @Test
    fun classify_usesJnc7Thresholds() {
        assertEquals(
            "Prehypertension",
            classify(128, 84, BloodPressureClassificationScheme.JNC7),
        )
        assertEquals(
            "Stage 2",
            classify(165, 92, BloodPressureClassificationScheme.JNC7),
        )
    }

    @Test
    fun classify_usesEscEsh2018Thresholds() {
        assertEquals(
            "Normal",
            classify(128, 84, BloodPressureClassificationScheme.ESC_ESH_2018),
        )
        assertEquals(
            "High normal",
            classify(135, 88, BloodPressureClassificationScheme.ESC_ESH_2018),
        )
        assertEquals(
            "Grade 3",
            classify(182, 96, BloodPressureClassificationScheme.ESC_ESH_2018),
        )
    }

    private fun classify(
        systolic: Int,
        diastolic: Int,
        scheme: BloodPressureClassificationScheme,
    ): String {
        return BloodPressureClassifier.classify(
            measurement = Measurement(
                user = 1,
                recordedAt = LocalDateTime.of(2026, 3, 25, 9, 0),
                systolic = systolic,
                diastolic = diastolic,
                pulse = 64,
                irregularHeartbeat = false,
                movement = false,
            ),
            scheme = scheme,
        ).category.shortLabel
    }
}
