package com.github.thiagokokada.omronsyncer

import com.github.thiagokokada.omronsyncer.model.Measurement
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class BloodPressureClassifierTest {

    @Test
    fun classify_usesJnc7Thresholds() {
        assertEquals(
            "jnc7_prehypertension",
            classify(128, 84, BloodPressureClassificationScheme.JNC7),
        )
        assertEquals(
            "jnc7_stage_two",
            classify(165, 92, BloodPressureClassificationScheme.JNC7),
        )
    }

    @Test
    fun classify_usesEscEsh2018Thresholds() {
        assertEquals(
            "esc_esh_normal",
            classify(128, 84, BloodPressureClassificationScheme.ESC_ESH_2018),
        )
        assertEquals(
            "esc_esh_high_normal",
            classify(135, 88, BloodPressureClassificationScheme.ESC_ESH_2018),
        )
        assertEquals(
            "esc_esh_grade_three",
            classify(182, 96, BloodPressureClassificationScheme.ESC_ESH_2018),
        )
    }

    @Test
    fun relevantChartGuides_omitsFarAwayThresholds() {
        val guides = BloodPressureClassifier.relevantChartGuides(
            scheme = BloodPressureClassificationScheme.ESC_ESH_2018,
            minSystolic = 122,
            maxSystolic = 146,
            minDiastolic = 82,
            maxDiastolic = 88,
        )

        assertEquals(
            listOf(
                "esc_esh_normal:SYSTOLIC",
                "esc_esh_high_normal:SYSTOLIC",
                "esc_esh_grade_one:SYSTOLIC",
                "esc_esh_normal:DIASTOLIC",
                "esc_esh_high_normal:DIASTOLIC",
                "esc_esh_grade_one:DIASTOLIC",
            ),
            guides.map { "${it.category.key}:${it.metric.name}" },
        )
    }

    @Test
    fun relevantChartGuides_keepsLowestGuideWhenSeriesIsBelowThresholds() {
        val guides = BloodPressureClassifier.relevantChartGuides(
            scheme = BloodPressureClassificationScheme.ESC_ESH_2018,
            minSystolic = 102,
            maxSystolic = 111,
            minDiastolic = 61,
            maxDiastolic = 74,
        )

        assertEquals(
            listOf(
                "esc_esh_normal:SYSTOLIC",
                "esc_esh_normal:DIASTOLIC",
            ),
            guides.map { "${it.category.key}:${it.metric.name}" },
        )
    }

    @Test
    fun relevantChartGuides_keepsHighestGuideWhenSeriesIsAboveThresholds() {
        val guides = BloodPressureClassifier.relevantChartGuides(
            scheme = BloodPressureClassificationScheme.ESC_ESH_2018,
            minSystolic = 192,
            maxSystolic = 205,
            minDiastolic = 114,
            maxDiastolic = 126,
        )

        assertEquals(
            listOf(
                "esc_esh_grade_three:SYSTOLIC",
                "esc_esh_grade_three:DIASTOLIC",
            ),
            guides.map { "${it.category.key}:${it.metric.name}" },
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
        ).category.key
    }
}
