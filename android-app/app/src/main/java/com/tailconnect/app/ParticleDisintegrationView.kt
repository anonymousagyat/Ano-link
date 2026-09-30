package com.tailconnect.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min
import kotlin.random.Random

/**
 * High-performance GPU Canvas overlay for Telegram-style micro-particle "Blip" disintegration.
 * Faithfully mirrors D:\Documents\PROJECTS\ano-link-webrtc\ui_latest.html:
 * - Rounded-corner boundary culling (16dp radius)
 * - Synchronized left-to-right clip-path wipe (420ms)
 * - Dense monochromatic slate dust particle palette (SLATE_PALETTE)
 * - ~5,000 microscopic sand specks with laminar atmospheric vector
 * - Touch-transparent overlay (passes all touch events through to views underneath)
 * - Smooth layout height collapse of the underlying view without jitter
 */
class ParticleDisintegrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val clipRect = RectF()

    // Pure Slate & Crystal dust palette matching ui_latest.html SLATE_PALETTE
    private val slatePalette = intArrayOf(
        Color.parseColor("#94A3B8"), // Slate 400
        Color.parseColor("#CBD5E1"), // Slate 300
        Color.parseColor("#64748B"), // Slate 500
        Color.parseColor("#E2E8F0"), // Slate 200
        Color.parseColor("#475569")  // Slate 600
    )

    init {
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    // Never intercept touch events — let all touches pass directly through to search & list below
    override fun onTouchEvent(event: MotionEvent): Boolean = false
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    private class MicroDustParticle(
        var x: Float,
        var y: Float,
        val color: Int,
        val size: Float,
        var vx: Float,
        var vy: Float,
        val decayRate: Float
    ) {
        var life: Float = 1.0f
        var active: Boolean = false

        fun update(): Boolean {
            if (!active) return true
            x += vx
            y += vy
            vx *= 0.995f
            vy *= 0.995f
            life -= decayRate
            return life > 0f
        }
    }

    private class DissolveJob(
        val bitmap: Bitmap,
        val originX: Float,
        val originY: Float,
        val width: Float,
        val height: Float,
        val cornerRadius: Float,
        val columns: List<List<MicroDustParticle>>,
        val totalCols: Int,
        val targetView: View,
        val onComplete: () -> Unit
    ) {
        val startTime: Long = System.currentTimeMillis()
        val wipeDurationMs: Long = 420L
        var lastActivatedCol: Int = -1
        var isWipeFinished: Boolean = false
        var isCollapsingStarted: Boolean = false
        var isDone: Boolean = false
    }

    private val activeJobs = mutableListOf<DissolveJob>()
    private val allParticles = mutableListOf<MicroDustParticle>()
    private var isAnimating = false

    /**
     * Launch the synchronized dissolution effect on a target view (e.g. Process Card).
     */
    fun startDissolve(view: View, onComplete: () -> Unit) {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            onComplete()
            return
        }

        // Show overlay view during animation
        visibility = VISIBLE

        // 1. Capture snapshot bitmap of the card
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)

        // 2. Compute coordinates relative to this overlay view
        val viewLoc = IntArray(2)
        view.getLocationOnScreen(viewLoc)
        val overlayLoc = IntArray(2)
        this.getLocationOnScreen(overlayLoc)
        val originX = (viewLoc[0] - overlayLoc[0]).toFloat()
        val originY = (viewLoc[1] - overlayLoc[1]).toFloat()

        // 3. Hide the original view immediately
        view.alpha = 0f

        // 4. Build column-indexed particle grid matching 16dp rounded corners
        // step = 1.6f * density gives ~5,000 dense, glittering particles per card exactly like HTML!
        val cornerRadius = 16f * density
        val step = (1.6f * density).coerceAtLeast(3.0f)
        val columns = mutableListOf<List<MicroDustParticle>>()

        var x = 0f
        while (x < w) {
            val col = mutableListOf<MicroDustParticle>()
            var y = 0f
            while (y < h) {
                // Round corner culling to match the card's exact 16dp radius
                if (isInsideRoundedRect(x, y, w.toFloat(), h.toFloat(), cornerRadius)) {
                    val jx = (Random.nextFloat() * 0.8f - 0.4f) * density
                    val jy = (Random.nextFloat() * 0.8f - 0.4f) * density

                    // Authentic luminous slate palette from HTML (never murky dark)
                    val color = slatePalette[Random.nextInt(slatePalette.size)]

                    // Structured laminar vector: slow right-up diagonal drift (~45-55 deg)
                    val vx = (0.50f + Random.nextFloat() * 0.28f) * density
                    val vy = -(0.70f + Random.nextFloat() * 0.35f) * density
                    val size = (0.75f + Random.nextFloat() * 0.50f) * density
                    val decayRate = 0.0052f // ~2.2s cinematic flight

                    val p = MicroDustParticle(
                        x = originX + x + jx,
                        y = originY + y + jy,
                        color = color,
                        size = size,
                        vx = vx,
                        vy = vy,
                        decayRate = decayRate
                    )
                    col.add(p)
                    allParticles.add(p)
                }
                y += step
            }
            columns.add(col)
            x += step
        }

        val job = DissolveJob(
            bitmap = bitmap,
            originX = originX,
            originY = originY,
            width = w.toFloat(),
            height = h.toFloat(),
            cornerRadius = cornerRadius,
            columns = columns,
            totalCols = columns.size,
            targetView = view,
            onComplete = onComplete
        )

        activeJobs.add(job)

        if (!isAnimating) {
            isAnimating = true
            postInvalidateOnAnimation()
        }
    }

    private fun isInsideRoundedRect(x: Float, y: Float, w: Float, h: Float, r: Float): Boolean {
        if (x < r && y < r) {
            val dx = x - r
            val dy = y - r
            return (dx * dx + dy * dy) <= (r * r)
        }
        if (x > w - r && y < r) {
            val dx = x - (w - r)
            val dy = y - r
            return (dx * dx + dy * dy) <= (r * r)
        }
        if (x < r && y > h - r) {
            val dx = x - r
            val dy = y - (h - r)
            return (dx * dx + dy * dy) <= (r * r)
        }
        if (x > w - r && y > h - r) {
            val dx = x - (w - r)
            val dy = y - (h - r)
            return (dx * dx + dy * dy) <= (r * r)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()

        // 1. Tick and draw active dissolve jobs (wipe effect)
        val jobIter = activeJobs.iterator()
        while (jobIter.hasNext()) {
            val job = jobIter.next()
            val elapsed = now - job.startTime
            val progress = (elapsed.toFloat() / job.wipeDurationMs).coerceIn(0f, 1f)

            // Draw remaining portion of the card being wiped from left to right
            if (progress < 1.0f) {
                canvas.save()
                val wipeX = job.originX + (job.width * progress)

                // Clip to rounded rect of the card
                clipPath.reset()
                clipRect.set(job.originX, job.originY, job.originX + job.width, job.originY + job.height)
                clipPath.addRoundRect(clipRect, job.cornerRadius, job.cornerRadius, Path.Direction.CW)
                canvas.clipPath(clipPath)

                // Clip away the dissolved left part
                canvas.clipRect(wipeX, job.originY, job.originX + job.width, job.originY + job.height)
                canvas.drawBitmap(job.bitmap, job.originX, job.originY, null)
                canvas.restore()

                // Activate particles that the wipe line has passed
                val targetCol = (progress * job.totalCols).toInt().coerceAtMost(job.totalCols - 1)
                if (targetCol > job.lastActivatedCol) {
                    for (c in (job.lastActivatedCol + 1)..targetCol) {
                        for (p in job.columns[c]) {
                            p.active = true
                        }
                    }
                    job.lastActivatedCol = targetCol
                }
            } else if (!job.isWipeFinished) {
                job.isWipeFinished = true
                // Activate all remaining particles
                for (col in job.columns) {
                    for (p in col) p.active = true
                }

                // Smooth layout collapse of the empty slot (no jump, silky smooth transition)
                if (!job.isCollapsingStarted) {
                    job.isCollapsingStarted = true
                    collapseViewSmoothly(job.targetView) {
                        job.isDone = true
                        job.onComplete()
                    }
                }
            }

            if (job.isDone && allParticles.none { it.active && it.life > 0f }) {
                try { job.bitmap.recycle() } catch (_: Exception) {}
                jobIter.remove()
            }
        }

        // 2. Update and draw all micro dust particles
        var hasActiveParticles = false
        val pIter = allParticles.iterator()
        while (pIter.hasNext()) {
            val p = pIter.next()
            if (!p.active) {
                hasActiveParticles = true
                continue
            }
            if (p.update()) {
                hasActiveParticles = true
                val alpha = (p.life.coerceIn(0f, 1f) * 0.85f * 255).toInt()
                paint.color = p.color
                paint.alpha = alpha
                val s = (p.size * min(1.0f, p.life * 1.15f)).coerceAtLeast(0.5f)
                canvas.drawRect(p.x, p.y, p.x + s, p.y + s, paint)
            } else {
                pIter.remove()
            }
        }

        if (activeJobs.isNotEmpty() || hasActiveParticles) {
            postInvalidateOnAnimation()
        } else {
            isAnimating = false
            // Hide overlay when idle so it consumes zero system resources
            visibility = GONE
        }
    }

    private fun collapseViewSmoothly(view: View, onFinish: () -> Unit) {
        val initialHeight = view.height
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
        val initialMarginBottom = params?.bottomMargin ?: 0

        val animator = ValueAnimator.ofFloat(1.0f, 0.0f)
        animator.duration = 380L
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.addUpdateListener { va ->
            val factor = va.animatedValue as Float
            params?.height = (initialHeight * factor).toInt()
            params?.bottomMargin = (initialMarginBottom * factor).toInt()
            view.layoutParams = params
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // Reset params for view recycling
                params?.height = ViewGroup.LayoutParams.WRAP_CONTENT
                params?.bottomMargin = initialMarginBottom
                view.layoutParams = params
                view.alpha = 1f
                onFinish()
            }
        })
        animator.start()
    }
}
