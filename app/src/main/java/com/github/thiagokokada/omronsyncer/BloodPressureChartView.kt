package com.github.thiagokokada.omronsyncer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.util.AttributeSet
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.View
import androidx.core.graphics.withSave
import com.google.android.material.color.MaterialColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
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
    private val guideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, Color.GRAY)
        strokeWidth = resources.displayMetrics.density
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(6f * resources.displayMetrics.density, 4f * resources.displayMetrics.density),
            0f,
        )
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
    private var chartGuides: List<ChartGuide> = emptyList()
    private var selectedBucket: TrendBucket? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var scaleFactor = 1f
    private var horizontalOffset = 0f
    private var lastTouchX = 0f
    private var isDragging = false
    private var isScaling = false
    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val plotBounds = currentPlotBounds() ?: return false
                val previousScale = scaleFactor
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(MIN_SCALE_FACTOR, MAX_SCALE_FACTOR)
                if (previousScale == scaleFactor) {
                    return false
                }
                val focusFraction = ((detector.focusX - plotBounds.left) / plotBounds.width())
                    .coerceIn(0f, 1f)
                val previousContentWidth = plotBounds.width() * previousScale
                val contentWidth = plotBounds.width() * scaleFactor
                val anchoredContentX = horizontalOffset + focusFraction * previousContentWidth
                horizontalOffset = (anchoredContentX - focusFraction * contentWidth)
                    .coerceIn(0f, maxHorizontalOffset(plotBounds.width()))
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        },
    )
    var onSelectionChanged: ((TrendBucket?) -> Unit)? = null

    fun resetZoom() {
        if (scaleFactor != MIN_SCALE_FACTOR || horizontalOffset != 0f) {
            scaleFactor = MIN_SCALE_FACTOR
            horizontalOffset = 0f
            invalidate()
        }
    }

    fun setBuckets(
        buckets: List<TrendBucket>,
        selectedBucket: TrendBucket?,
        classificationScheme: BloodPressureClassificationScheme,
    ) {
        chartPoints = buckets.sortedBy { it.date }.map {
            ChartPoint(
                bucket = it,
                recordedAtMillis = it.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                systolic = it.meanSystolic,
                diastolic = it.meanDiastolic,
            )
        }
        chartGuides = if (chartPoints.isEmpty()) {
            emptyList()
        } else {
            BloodPressureClassifier.relevantChartGuides(
                scheme = classificationScheme,
                minSystolic = chartPoints.minOf { it.systolic },
                maxSystolic = chartPoints.maxOf { it.systolic },
                minDiastolic = chartPoints.minOf { it.diastolic },
                maxDiastolic = chartPoints.maxOf { it.diastolic },
            ).map { guide ->
                ChartGuide(
                    metricLabel = when (guide.metric) {
                        BloodPressureGuideMetric.SYSTOLIC -> context.getString(R.string.blood_pressure_guide_metric_systolic)
                        BloodPressureGuideMetric.DIASTOLIC -> context.getString(R.string.blood_pressure_guide_metric_diastolic)
                    },
                    value = guide.value,
                    label = BloodPressureClassifier.shortLabel(context, guide.category),
                    color = BloodPressureClassifier.chartGuideColor(context, guide.category),
                )
            }
        }
        this.selectedBucket = selectedBucket
        scaleFactor = scaleFactor.coerceIn(MIN_SCALE_FACTOR, MAX_SCALE_FACTOR)
        horizontalOffset = horizontalOffset.coerceIn(0f, maxHorizontalOffset(currentPlotWidth()))
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
        val plotBounds = RectF(leftPadding, topPadding, rightPadding, bottomPadding)

        val timeMin = chartPoints.first().recordedAtMillis
        val timeMax = chartPoints.last().recordedAtMillis
        val timeRange = max(1L, timeMax - timeMin)
        val contentWidth = plotWidth * scaleFactor

        systolicPath.reset()
        diastolicPath.reset()

        canvas.withSave {
            clipRect(plotBounds)
            drawGuideLines(
                canvas = this,
                left = leftPadding,
                right = rightPadding,
                top = topPadding,
                bottom = bottomPadding,
                paddedMin = paddedMin,
                pressureRange = pressureRange,
                plotHeight = plotHeight,
            )
            chartPoints.forEachIndexed { index, point ->
                val x = if (chartPoints.size == 1) {
                    leftPadding + plotWidth / 2f
                } else {
                    leftPadding + (((point.recordedAtMillis - timeMin).toFloat() / timeRange) * contentWidth) - horizontalOffset
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

                if (point.bucket == selectedBucket) {
                    drawLine(x, topPadding, x, bottomPadding, selectionPaint)
                }

                pointPaint.color = systolicPaint.color
                drawCircle(x, systolicY, 3f * resources.displayMetrics.density, pointPaint)
                pointPaint.color = diastolicPaint.color
                drawCircle(x, diastolicY, 3f * resources.displayMetrics.density, pointPaint)

                if (point.bucket == selectedBucket) {
                    val selectionRadius = 6f * resources.displayMetrics.density
                    drawCircle(x, systolicY, selectionRadius, selectedPointPaint)
                    drawCircle(x, diastolicY, selectionRadius, selectedPointPaint)
                    pointPaint.color = systolicPaint.color
                    drawCircle(x, systolicY, 4f * resources.displayMetrics.density, pointPaint)
                    pointPaint.color = diastolicPaint.color
                    drawCircle(x, diastolicY, 4f * resources.displayMetrics.density, pointPaint)
                }
            }

            drawPath(systolicPath, systolicPaint)
            drawPath(diastolicPath, diastolicPaint)
        }
        drawGuideLabels(
            canvas = canvas,
            left = leftPadding,
            right = rightPadding,
            top = topPadding,
            bottom = bottomPadding,
            paddedMin = paddedMin,
            pressureRange = pressureRange,
            plotHeight = plotHeight,
        )

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
                leftPadding + (((timestamp - timeMin).toFloat() / timeRange) * contentWidth) - horizontalOffset
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
        scaleGestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScaling) {
                    return true
                }
                if (scaleFactor <= 1f) {
                    return true
                }
                val deltaX = event.x - lastTouchX
                if (!isDragging && kotlin.math.abs(deltaX) > touchSlop) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (isDragging) {
                    horizontalOffset = (horizontalOffset - deltaX)
                        .coerceIn(0f, maxHorizontalOffset(currentPlotWidth()))
                    lastTouchX = event.x
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging && !isScaling) {
                    val nearestPoint = nearestPoint(event.x)
                    if (nearestPoint.bucket != selectedBucket) {
                        selectedBucket = nearestPoint.bucket
                        onSelectionChanged?.invoke(selectedBucket)
                        invalidate()
                    }
                    performClick()
                }
                isDragging = false
                isScaling = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isScaling = false
                return true
            }
        }
        return super.onTouchEvent(event)
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
        val contentWidth = plotWidth * scaleFactor
        val timeMin = chartPoints.first().recordedAtMillis
        val timeMax = chartPoints.last().recordedAtMillis
        val timeRange = max(1L, timeMax - timeMin)
        val clampedX = touchX.coerceIn(leftPadding, rightPadding)

        return chartPoints.minBy { point ->
            val pointX = leftPadding + (((point.recordedAtMillis - timeMin).toFloat() / timeRange) * contentWidth) - horizontalOffset
            (pointX - clampedX).pow(2)
        }
    }

    private data class ChartPoint(
        val bucket: TrendBucket,
        val recordedAtMillis: Long,
        val systolic: Int,
        val diastolic: Int,
    )

    private data class ChartGuide(
        val metricLabel: String,
        val value: Int,
        val label: String,
        val color: Int,
    )

    private fun drawGuideLines(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        paddedMin: Int,
        pressureRange: Int,
        plotHeight: Float,
    ) {
        chartGuides.sortedByDescending { it.value }.forEach { guide ->
            val y = bottom - (((guide.value - paddedMin).toFloat() / pressureRange) * plotHeight)
            if (y < top || y > bottom) {
                return@forEach
            }
            guideLinePaint.color = guide.color
            canvas.drawLine(left, y, right, y, guideLinePaint)
        }
    }

    private fun drawGuideLabels(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        paddedMin: Int,
        pressureRange: Int,
        plotHeight: Float,
    ) {
        var lastLabelY = Float.NEGATIVE_INFINITY
        chartGuides.sortedByDescending { it.value }.forEach { guide ->
            val y = bottom - (((guide.value - paddedMin).toFloat() / pressureRange) * plotHeight)
            if (y < top || y > bottom) {
                return@forEach
            }
            if (y - lastLabelY < axisTextPaint.textSize + 6f * resources.displayMetrics.density) {
                return@forEach
            }
            axisTextPaint.color = guide.color
            val leftText = "${guide.value}"
            val rightText = "${guide.metricLabel} ${guide.label}"
            canvas.drawText(
                leftText,
                (left - 28f * resources.displayMetrics.density).coerceAtLeast(paddingLeft.toFloat()),
                y - 4f,
                axisTextPaint,
            )
            val textWidth = axisTextPaint.measureText(rightText)
            canvas.drawText(
                rightText,
                (right - textWidth - 4f * resources.displayMetrics.density).coerceAtLeast(left),
                y - 4f,
                axisTextPaint,
            )
            lastLabelY = y
        }
        axisTextPaint.color = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.DKGRAY,
        )
    }

    private fun currentPlotBounds(): android.graphics.RectF? {
        if (width == 0 || height == 0) {
            return null
        }
        val leftPadding = paddingLeft + 56f * resources.displayMetrics.density
        val rightPadding = width - paddingRight - 16f * resources.displayMetrics.density
        val topPadding = paddingTop + 16f * resources.displayMetrics.density
        val bottomPadding = height - paddingBottom - 28f * resources.displayMetrics.density
        if (rightPadding <= leftPadding || bottomPadding <= topPadding) {
            return null
        }
        return android.graphics.RectF(leftPadding, topPadding, rightPadding, bottomPadding)
    }

    private fun currentPlotWidth(): Float {
        return currentPlotBounds()?.width() ?: 0f
    }

    private fun maxHorizontalOffset(plotWidth: Float): Float {
        return max(0f, (plotWidth * scaleFactor) - plotWidth)
    }

    private companion object {
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
        const val MIN_SCALE_FACTOR = 1f
        const val MAX_SCALE_FACTOR = 4f
    }
}
