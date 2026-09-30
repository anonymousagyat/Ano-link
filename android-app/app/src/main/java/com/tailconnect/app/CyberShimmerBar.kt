package com.tailconnect.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * High-performance capsule shimmer loading bar.
 * Matches the CSS sweepShimmer keyframes 1:1 with hardware-accelerated LinearGradient.
 */
class CyberShimmerBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0D1A")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#459333EA")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cornerPath = Path()
    private val boundsRect = RectF()

    private var shimmerProgress = 0f
    private var animator: ValueAnimator? = null

    private val shimmerColors = intArrayOf(
        Color.TRANSPARENT,
        Color.parseColor("#7E22CE"),
        Color.parseColor("#9333EA"),
        Color.parseColor("#D8B4FE"),
        Color.parseColor("#FFFFFF"),
        Color.parseColor("#D8B4FE"),
        Color.parseColor("#9333EA"),
        Color.TRANSPARENT
    )
    private val shimmerPositions = floatArrayOf(0f, 0.15f, 0.35f, 0.65f, 0.80f, 0.88f, 0.95f, 1f)

    init {
        startAnimation()
    }

    fun startAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(-0.6f, 1.3f).apply {
            duration = 1350L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                shimmerProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        boundsRect.set(1.5f, 1.5f, w - 1.5f, h - 1.5f)
        val radius = h / 2f
        cornerPath.reset()
        cornerPath.addRoundRect(boundsRect, radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val radius = h / 2f

        // Draw capsule background track
        canvas.drawRoundRect(boundsRect, radius, radius, trackPaint)

        // Clip to capsule and draw smooth continuous shimmer wave
        canvas.save()
        canvas.clipPath(cornerPath)

        val shimmerWidth = w * 0.65f
        val startX = shimmerProgress * w
        val endX = startX + shimmerWidth

        shimmerPaint.shader = LinearGradient(
            startX, 0f, endX, 0f,
            shimmerColors,
            shimmerPositions,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(startX, 0f, endX, h, shimmerPaint)
        canvas.restore()

        // Draw capsule border with ambient glow
        canvas.drawRoundRect(boundsRect, radius, radius, borderPaint)
    }
}
