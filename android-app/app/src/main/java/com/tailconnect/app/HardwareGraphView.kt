package com.tailconnect.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * High-performance 120Hz/60Hz real-time performance line graph.
 * Modeled after the authentic Windows 11 Task Manager Performance grid
 * with glowing yellow-golden stroke and area gradient fill.
 */
class HardwareGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val HISTORY_POINTS = 60 // 60 seconds of real-time telemetry
    }

    private val dataPoints = FloatArray(HISTORY_POINTS) { 0f }
    private var maxVal = 100f

    private var accentColor = Color.parseColor("#F59E0B")
    private var gridColor = Color.parseColor("#182640")
    private var bgColor = Color.parseColor("#080D18")

    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = bgColor
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = gridColor
        strokeWidth = 1f * resources.displayMetrics.density
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentColor
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentColor
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val linePath = Path()
    private val fillPath = Path()
    private val clipPath = Path()
    private val cornerRadius = 12f * resources.displayMetrics.density

    init {
        // Pre-fill with reasonable baseline values
        for (i in dataPoints.indices) {
            dataPoints[i] = 10f + (i % 5) * 2f
        }
    }

    fun setAccentColor(colorHex: String) {
        accentColor = Color.parseColor(colorHex)
        linePaint.color = accentColor
        dotBorderPaint.color = accentColor
        updateShader(width, height)
        postInvalidateOnAnimation()
    }

    fun addPoint(value: Float) {
        // Shift left
        System.arraycopy(dataPoints, 1, dataPoints, 0, HISTORY_POINTS - 1)
        dataPoints[HISTORY_POINTS - 1] = value.coerceIn(0f, maxVal)
        postInvalidateOnAnimation()
    }

    fun setHistory(values: List<Float>) {
        val start = (HISTORY_POINTS - values.size).coerceAtLeast(0)
        for (i in 0 until start) {
            dataPoints[i] = 0f
        }
        for (i in values.indices) {
            val idx = start + i
            if (idx < HISTORY_POINTS) {
                dataPoints[idx] = values[i].coerceIn(0f, maxVal)
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader(w, h)
        clipPath.reset()
        clipPath.addRoundRect(
            0f, 0f, w.toFloat(), h.toFloat(),
            cornerRadius, cornerRadius,
            Path.Direction.CW
        )
    }

    private fun updateShader(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val topAlpha = (accentColor and 0x00FFFFFF) or 0x44000000
        val bottomAlpha = (accentColor and 0x00FFFFFF) or 0x00000000
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            topAlpha, bottomAlpha,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        canvas.save()
        canvas.clipPath(clipPath)

        // Draw solid background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Draw horizontal grid lines (0%, 25%, 50%, 75%, 100%)
        val hSteps = 4
        for (i in 0..hSteps) {
            val y = (h * i) / hSteps
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // Draw vertical grid lines (every 10 seconds -> 6 columns)
        val vSteps = 6
        for (i in 1 until vSteps) {
            val x = (w * i) / vSteps
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }

        // Build line & fill paths
        linePath.reset()
        fillPath.reset()

        val stepX = w / (HISTORY_POINTS - 1)
        var lastX = 0f
        var lastY = h

        for (i in 0 until HISTORY_POINTS) {
            val x = i * stepX
            val ratio = (dataPoints[i] / maxVal).coerceIn(0f, 1f)
            // Leave a small 4dp padding from top/bottom
            val pad = 4f * resources.displayMetrics.density
            val y = (h - pad) - ratio * (h - pad * 2f)

            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }

            if (i == HISTORY_POINTS - 1) {
                lastX = x
                lastY = y
            }
        }

        fillPath.lineTo(w, h)
        fillPath.close()

        // 1. Draw glowing area gradient
        canvas.drawPath(fillPath, fillPaint)

        // 2. Draw sharp performance line
        canvas.drawPath(linePath, linePaint)

        // 3. Draw live pulse dot on current (latest) point
        val dotRadius = 3.5f * resources.displayMetrics.density
        canvas.drawCircle(lastX - 2f, lastY, dotRadius, dotPaint)
        canvas.drawCircle(lastX - 2f, lastY, dotRadius, dotBorderPaint)

        canvas.restore()
    }
}
