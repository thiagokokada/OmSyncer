package com.github.thiagokokada.omronsyncer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.MotionEvent
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withSave
import com.github.thiagokokada.omronsyncer.model.Measurement
import com.google.android.material.color.MaterialColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class BloodPressureChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)
        strokeWidth = resources.displayMetrics.density
    }
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            resources.displayMetrics,
        )
    }
    private val systolicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.RED)
        strokeWidth = 3f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val diastolicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiary, Color.BLUE)
        strokeWidth = 3f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val systolicPath = Path()
    private val diastolicPath = Path()
    private val selectedPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        style = Paint.Style.FILL
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, Color.GRAY)
        strokeWidth = resources.displayMetrics.density
    }

    private var chartPoints: List<ChartPoint> = emptyList()
    private var selectedMeasurement: Measurement? = null
    var onSelectionChanged: ((Measurement?) -> Unit)? = null

    fun setMeasurements(measurements: List<Measurement>, selectedMeasurement: Measurement?) {
        chartPoints = measurements.sortedBy { it.recordedAt }.map {
            ChartPoint(
                measurement = it,
                recordedAtMillis = it.recordedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                systolic = it.systolic,
                diastolic = it.diastolic,
            )
        }
        this.selectedMeasurement = selectedMeasurement
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (chartPoints.isEmpty()) {
            return
        }

        val leftPadding = paddingLeft + 56f * resources.displayMetrics.density
        val rightPadding = width - paddingRight - 16f * resources.displayMetrics.density
        val topPadding = paddingTop + 16f * resources.displayMetrics.density
        val bottomPadding = height - paddingBottom - 28f * resources.displayMetrics.density
        val plotWidth = rightPadding - leftPadding
        val plotHeight = bottomPadding - topPadding

        if (plotWidth <= 0f || plotHeight <= 0f) {
            return
        }

        val minPressure = chartPoints.minOf { minOf(it.systolic, it.diastolic) }
        val maxPressure = chartPoints.maxOf { maxOf(it.systolic, it.diastolic) }
        val paddedMin = max(40, (minPressure - 10) / 10 * 10)
        val paddedMax = ((maxPressure + 19) / 10) * 10
        val pressureRange = max(10, paddedMax - paddedMin)

        repeat(5) { index ->
            val ratio = index / 4f
            val y = bottomPadding - (plotHeight * ratio)
            canvas.drawLine(leftPadding, y, rightPadding, y, gridPaint)
            val value = paddedMin + (pressureRange * ratio).roundToInt()
            canvas.drawText(value.toString(), paddingLeft.toFloat(), y + axisTextPaint.textSize / 3f, axisTextPaint)
        }

        val timeMin = chartPoints.first().recordedAtMillis
        val timeMax = chartPoints.last().recordedAtMillis
        val timeRange = max(1L, timeMax - timeMin)

        systolicPath.reset()
        diastolicPath.reset()

        chartPoints.forEachIndexed { index, point ->
            val x = if (chartPoints.size == 1) {
                leftPadding + plotWidth / 2f
            } else {
                leftPadding + (((point.recordedAtMillis - timeMin).toFloat() / timeRange) * plotWidth)
            }
            val systolicY = bottomPadding - (((point.systolic - paddedMin).toFloat() / pressureRange) * plotHeight)
            val diastolicY = bottomPadding - (((point.diastolic - paddedMin).toFloat() / pressureRange) * plotHeight)

            if (index == 0) {
                systolicPath.moveTo(x, systolicY)
                diastolicPath.moveTo(x, diastolicY)
            } else {
                systolicPath.lineTo(x, systolicY)
                diastolicPath.lineTo(x, diastolicY)
            }

            if (point.measurement == selectedMeasurement) {
                canvas.drawLine(x, topPadding, x, bottomPadding, selectionPaint)
            }

            pointPaint.color = systolicPaint.color
            canvas.drawCircle(x, systolicY, 3f * resources.displayMetrics.density, pointPaint)
            pointPaint.color = diastolicPaint.color
            canvas.drawCircle(x, diastolicY, 3f * resources.displayMetrics.density, pointPaint)

            if (point.measurement == selectedMeasurement) {
                val selectionRadius = 6f * resources.displayMetrics.density
                canvas.drawCircle(x, systolicY, selectionRadius, selectedPointPaint)
                canvas.drawCircle(x, diastolicY, selectionRadius, selectedPointPaint)
                pointPaint.color = systolicPaint.color
                canvas.drawCircle(x, systolicY, 4f * resources.displayMetrics.density, pointPaint)
                pointPaint.color = diastolicPaint.color
                canvas.drawCircle(x, diastolicY, 4f * resources.displayMetrics.density, pointPaint)
            }
        }

        canvas.drawPath(systolicPath, systolicPaint)
        canvas.drawPath(diastolicPath, diastolicPaint)

        val labels = buildList {
            add(chartPoints.first().recordedAtMillis)
            if (chartPoints.size > 2) {
                add(chartPoints[chartPoints.lastIndex / 2].recordedAtMillis)
            }
            if (chartPoints.size > 1) {
                add(chartPoints.last().recordedAtMillis)
            }
        }.distinct()

        labels.forEach { timestamp ->
            val x = if (chartPoints.size == 1) {
                leftPadding + plotWidth / 2f
            } else {
                leftPadding + (((timestamp - timeMin).toFloat() / timeRange) * plotWidth)
            }
            val label = DATE_FORMATTER.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
            val textWidth = axisTextPaint.measureText(label)
            canvas.withSave {
                drawText(
                    label,
                    (x - textWidth / 2f).coerceIn(leftPadding, rightPadding - textWidth),
                    height - paddingBottom.toFloat(),
                    axisTextPaint,
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (chartPoints.isEmpty()) {
            return super.onTouchEvent(event)
        }
        if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_UP) {
            return super.onTouchEvent(event)
        }

        val nearestPoint = nearestPoint(event.x)
        if (nearestPoint.measurement != selectedMeasurement) {
            selectedMeasurement = nearestPoint.measurement
            onSelectionChanged?.invoke(selectedMeasurement)
            invalidate()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun nearestPoint(touchX: Float): ChartPoint {
        if (chartPoints.size == 1) {
            return chartPoints.first()
        }

        val leftPadding = paddingLeft + 56f * resources.displayMetrics.density
        val rightPadding = width - paddingRight - 16f * resources.displayMetrics.density
        val plotWidth = rightPadding - leftPadding
        val timeMin = chartPoints.first().recordedAtMillis
        val timeMax = chartPoints.last().recordedAtMillis
        val timeRange = max(1L, timeMax - timeMin)
        val clampedX = touchX.coerceIn(leftPadding, rightPadding)

        return chartPoints.minBy { point ->
            val pointX = leftPadding + (((point.recordedAtMillis - timeMin).toFloat() / timeRange) * plotWidth)
            (pointX - clampedX).pow(2)
        }
    }

    private data class ChartPoint(
        val measurement: Measurement,
        val recordedAtMillis: Long,
        val systolic: Int,
        val diastolic: Int,
    )

    private companion object {
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
    }
}
