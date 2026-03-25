package com.github.thiagokokada.omronsyncer

import android.content.Context
import android.graphics.Color
import androidx.annotation.StringRes
import com.github.thiagokokada.omronsyncer.model.Measurement

enum class BloodPressureClassificationScheme {
    DISABLED,
    JNC7,
    ESC_ESH_2018,
}

data class BloodPressureCategoryDefinition(
    val key: String,
    @param:StringRes val shortLabelResId: Int,
    val severity: Int,
)

data class BloodPressureClassification(
    val scheme: BloodPressureClassificationScheme,
    val category: BloodPressureCategoryDefinition,
)

data class BloodPressureCategoryCount(
    val category: BloodPressureCategoryDefinition,
    val count: Int,
)

data class BloodPressureCategoryGuide(
    val metric: BloodPressureGuideMetric,
    val value: Int,
    val category: BloodPressureCategoryDefinition,
)

enum class BloodPressureGuideMetric {
    SYSTOLIC,
    DIASTOLIC,
}

data class BloodPressureCategoryStyle(
    val backgroundColor: Int,
    val textColor: Int,
)

object BloodPressureClassifier {

    fun classify(
        measurement: Measurement,
        scheme: BloodPressureClassificationScheme,
    ): BloodPressureClassification {
        val category = when (scheme) {
            BloodPressureClassificationScheme.DISABLED -> error("Blood pressure classification is disabled")
            BloodPressureClassificationScheme.JNC7 -> classifyJnc7(measurement)
            BloodPressureClassificationScheme.ESC_ESH_2018 -> classifyEscEsh2018(measurement)
        }
        return BloodPressureClassification(scheme = scheme, category = category)
    }

    fun categoryCounts(
        measurements: List<Measurement>,
        scheme: BloodPressureClassificationScheme,
    ): List<BloodPressureCategoryCount> {
        val counts = measurements
            .map { classify(it, scheme).category.key }
            .groupingBy { it }
            .eachCount()
        return categories(scheme).map { category ->
            BloodPressureCategoryCount(
                category = category,
                count = counts[category.key] ?: 0,
            )
        }
    }

    fun categories(scheme: BloodPressureClassificationScheme): List<BloodPressureCategoryDefinition> {
        return when (scheme) {
            BloodPressureClassificationScheme.DISABLED -> emptyList()
            BloodPressureClassificationScheme.JNC7 -> listOf(
                JNC7_NORMAL,
                JNC7_PREHYPERTENSION,
                JNC7_STAGE_ONE,
                JNC7_STAGE_TWO,
            )
            BloodPressureClassificationScheme.ESC_ESH_2018 -> listOf(
                ESC_ESH_OPTIMAL,
                ESC_ESH_NORMAL,
                ESC_ESH_HIGH_NORMAL,
                ESC_ESH_GRADE_ONE,
                ESC_ESH_GRADE_TWO,
                ESC_ESH_GRADE_THREE,
            )
        }
    }

    fun relevantChartGuides(
        scheme: BloodPressureClassificationScheme,
        minSystolic: Int,
        maxSystolic: Int,
        minDiastolic: Int,
        maxDiastolic: Int,
        buffer: Int = 8,
    ): List<BloodPressureCategoryGuide> {
        if (scheme == BloodPressureClassificationScheme.DISABLED) {
            return emptyList()
        }
        val systolicLowerBound = minSystolic - buffer
        val systolicUpperBound = maxSystolic + buffer
        val diastolicLowerBound = minDiastolic - buffer
        val diastolicUpperBound = maxDiastolic + buffer
        return chartGuides(scheme).filter { guide ->
            when (guide.metric) {
                BloodPressureGuideMetric.SYSTOLIC ->
                    guide.value in systolicLowerBound..systolicUpperBound
                BloodPressureGuideMetric.DIASTOLIC ->
                    guide.value in diastolicLowerBound..diastolicUpperBound
            }
        }
    }

    fun style(category: BloodPressureCategoryDefinition): BloodPressureCategoryStyle {
        return when (category.severity) {
            0 -> BloodPressureCategoryStyle(
                backgroundColor = Color.rgb(220, 241, 228),
                textColor = Color.rgb(32, 99, 64),
            )
            1 -> BloodPressureCategoryStyle(
                backgroundColor = Color.rgb(227, 238, 247),
                textColor = Color.rgb(42, 78, 130),
            )
            2 -> BloodPressureCategoryStyle(
                backgroundColor = Color.rgb(246, 232, 214),
                textColor = Color.rgb(143, 83, 27),
            )
            3 -> BloodPressureCategoryStyle(
                backgroundColor = Color.rgb(247, 223, 223),
                textColor = Color.rgb(137, 38, 42),
            )
            else -> BloodPressureCategoryStyle(
                backgroundColor = Color.rgb(235, 226, 239),
                textColor = Color.rgb(86, 44, 92),
            )
        }
    }

    fun shortLabel(
        context: Context,
        category: BloodPressureCategoryDefinition,
    ): String {
        return context.getString(category.shortLabelResId)
    }

    private fun classifyJnc7(measurement: Measurement): BloodPressureCategoryDefinition {
        return when {
            measurement.systolic >= 160 || measurement.diastolic >= 100 -> JNC7_STAGE_TWO
            measurement.systolic >= 140 || measurement.diastolic >= 90 -> JNC7_STAGE_ONE
            measurement.systolic >= 120 || measurement.diastolic >= 80 -> JNC7_PREHYPERTENSION
            else -> JNC7_NORMAL
        }
    }

    private fun classifyEscEsh2018(measurement: Measurement): BloodPressureCategoryDefinition {
        return when {
            measurement.systolic >= 180 || measurement.diastolic >= 110 -> ESC_ESH_GRADE_THREE
            measurement.systolic >= 160 || measurement.diastolic >= 100 -> ESC_ESH_GRADE_TWO
            measurement.systolic >= 140 || measurement.diastolic >= 90 -> ESC_ESH_GRADE_ONE
            measurement.systolic >= 130 || measurement.diastolic >= 85 -> ESC_ESH_HIGH_NORMAL
            measurement.systolic >= 120 || measurement.diastolic >= 80 -> ESC_ESH_NORMAL
            else -> ESC_ESH_OPTIMAL
        }
    }

    private fun chartGuides(scheme: BloodPressureClassificationScheme): List<BloodPressureCategoryGuide> {
        return when (scheme) {
            BloodPressureClassificationScheme.DISABLED -> emptyList()
            BloodPressureClassificationScheme.JNC7 -> listOf(
                BloodPressureCategoryGuide(BloodPressureGuideMetric.SYSTOLIC, 120, JNC7_PREHYPERTENSION),
                BloodPressureCategoryGuide(BloodPressureGuideMetric.SYSTOLIC, 140, JNC7_STAGE_ONE),
                BloodPressureCategoryGuide(BloodPressureGuideMetric.SYSTOLIC, 160, JNC7_STAGE_TWO),
                BloodPressureCategoryGuide(BloodPressureGuideMetric.DIASTOLIC, 80, JNC7_PREHYPERTENSION),
                BloodPressureCategoryGuide(BloodPressureGuideMetric.DIASTOLIC, 90, JNC7_STAGE_ONE),
                BloodPressureCategoryGuide(BloodPressureGuideMetric.DIASTOLIC, 100, JNC7_STAGE_TWO),
            )
            BloodPressureClassificationScheme.ESC_ESH_2018 -> listOf(
                BloodPressureCategoryGuide(BloodPressureGuideMetric.SYSTOLIC, 120, ESC_ESH_NORMAL),
                BloodPressureCategoryGuide(BloodPressureGuideMetric.SYSTOLIC, 130, ESC_ESH_HIGH_NORMAL),
                BloodPressureCategoryGuide(BloodPressureGuideMetric.SYSTOLIC, 140, ESC_ESH_GRADE_ONE),
                BloodPressureCategoryGuide(BloodPressureGuideMetric.SYSTOLIC, 160, ESC_ESH_GRADE_TWO),
                BloodPressureCategoryGuide(BloodPressureGuideMetric.SYSTOLIC, 180, ESC_ESH_GRADE_THREE),
                BloodPressureCategoryGuide(BloodPressureGuideMetric.DIASTOLIC, 80, ESC_ESH_NORMAL),
                BloodPressureCategoryGuide(BloodPressureGuideMetric.DIASTOLIC, 85, ESC_ESH_HIGH_NORMAL),
                BloodPressureCategoryGuide(BloodPressureGuideMetric.DIASTOLIC, 90, ESC_ESH_GRADE_ONE),
                BloodPressureCategoryGuide(BloodPressureGuideMetric.DIASTOLIC, 100, ESC_ESH_GRADE_TWO),
                BloodPressureCategoryGuide(BloodPressureGuideMetric.DIASTOLIC, 110, ESC_ESH_GRADE_THREE),
            )
        }
    }

    private val JNC7_NORMAL = BloodPressureCategoryDefinition(
        key = "jnc7_normal",
        shortLabelResId = R.string.blood_pressure_category_normal,
        severity = 0,
    )
    private val JNC7_PREHYPERTENSION = BloodPressureCategoryDefinition(
        key = "jnc7_prehypertension",
        shortLabelResId = R.string.blood_pressure_category_prehypertension,
        severity = 1,
    )
    private val JNC7_STAGE_ONE = BloodPressureCategoryDefinition(
        key = "jnc7_stage_one",
        shortLabelResId = R.string.blood_pressure_category_stage_1,
        severity = 2,
    )
    private val JNC7_STAGE_TWO = BloodPressureCategoryDefinition(
        key = "jnc7_stage_two",
        shortLabelResId = R.string.blood_pressure_category_stage_2,
        severity = 3,
    )
    private val ESC_ESH_OPTIMAL = BloodPressureCategoryDefinition(
        key = "esc_esh_optimal",
        shortLabelResId = R.string.blood_pressure_category_optimal,
        severity = 0,
    )
    private val ESC_ESH_NORMAL = BloodPressureCategoryDefinition(
        key = "esc_esh_normal",
        shortLabelResId = R.string.blood_pressure_category_normal,
        severity = 1,
    )
    private val ESC_ESH_HIGH_NORMAL = BloodPressureCategoryDefinition(
        key = "esc_esh_high_normal",
        shortLabelResId = R.string.blood_pressure_category_high_normal,
        severity = 2,
    )
    private val ESC_ESH_GRADE_ONE = BloodPressureCategoryDefinition(
        key = "esc_esh_grade_one",
        shortLabelResId = R.string.blood_pressure_category_grade_1,
        severity = 3,
    )
    private val ESC_ESH_GRADE_TWO = BloodPressureCategoryDefinition(
        key = "esc_esh_grade_two",
        shortLabelResId = R.string.blood_pressure_category_grade_2,
        severity = 4,
    )
    private val ESC_ESH_GRADE_THREE = BloodPressureCategoryDefinition(
        key = "esc_esh_grade_three",
        shortLabelResId = R.string.blood_pressure_category_grade_3,
        severity = 5,
    )
}
