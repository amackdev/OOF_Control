package com.oof.control.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.oof.control.R

class OpenArcProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 28f
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 28f
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 40f
        maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
    }

    private val arcRect = RectF()
    private var max = 100
    private var progress = 0
    private var animatedProgress = 0f

    private val startAngle = 135f
    private val sweepAngle = 270f


    init {
        // BlurMaskFilter needs software rendering
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        trackPaint.color = ContextCompat.getColor(context, R.color.arc_track)
    }

    fun setProgress(newProgress: Int, animate: Boolean = true) {
        val target = newProgress.coerceIn(0, max)

        if (animate) {
            ValueAnimator.ofFloat(animatedProgress, target.toFloat()).apply {
                duration = 800
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    animatedProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animatedProgress = target.toFloat()
            invalidate()
        }
        progress = target
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = glowPaint.strokeWidth / 2f + 16f
        arcRect.set(inset, inset, w - inset, h - inset)
        rebuildGradient()
    }

    private fun rebuildGradient() {
        val cx = arcRect.centerX()
        val cy = arcRect.centerY()

        // SweepGradient goes 0→360° clockwise from 3-o'clock.
        // We rotate it by startAngle (135°) so position 0.0 = arc start.
        // Our arc sweeps 270°, which is 270/360 = 0.75 of the circle.
        // So gradient positions 0.0–0.75 map to the visible arc.

        val arcColors = intArrayOf(
            Color.parseColor("#1A4285F4"),  // 0.00 — arc start
            Color.parseColor("#334285F4"),  // 0.10
            Color.parseColor("#4285F4"),    // 0.25
            Color.parseColor("#4285F4"),    // 0.45
            Color.parseColor("#8AB4F8"),    // 0.60
            Color.parseColor("#DDEEFF"),    // 0.70
            Color.parseColor("#FFFFFF"),    // 0.75 — arc end
            Color.parseColor("#00FFFFFF"),  // 0.76 — transparent gap
            Color.parseColor("#00FFFFFF"),  // 0.97 — transparent gap
            Color.parseColor("#1A4285F4"),  // 0.98 — start cap backwards extension
            Color.parseColor("#1A4285F4"),  // 1.00 — start cap backwards extension
        )

        val arcPositions = floatArrayOf(
            0.00f,
            0.10f,
            0.25f,
            0.45f,
            0.60f,
            0.70f,
            0.75f,
            0.76f,
            0.97f,
            0.98f,
            1.00f,
        )

        val sweepGradient = SweepGradient(cx, cy, arcColors, arcPositions)

        val gradientMatrix = Matrix()
        gradientMatrix.setRotate(startAngle, cx, cy)
        sweepGradient.setLocalMatrix(gradientMatrix)

        progressPaint.shader = sweepGradient

        // Glow uses the same shape but lower opacity
        val glowColors = intArrayOf(
            Color.parseColor("#334285F4"),  // 0.00
            Color.parseColor("#334285F4"),  // 0.10
            Color.parseColor("#404285F4"),  // 0.25
            Color.parseColor("#664285F4"),  // 0.45
            Color.parseColor("#668AB4F8"),  // 0.60
            Color.parseColor("#66DDEEFF"),  // 0.70
            Color.parseColor("#66FFFFFF"),  // 0.75
            Color.parseColor("#00FFFFFF"),  // 0.76
            Color.parseColor("#00FFFFFF"),  // 0.97
            Color.parseColor("#334285F4"),  // 0.98
            Color.parseColor("#334285F4"),  // 1.00
        )

        val glowGradient = SweepGradient(cx, cy, glowColors, arcPositions)
        val glowMatrix = Matrix()
        glowMatrix.setRotate(startAngle, cx, cy)
        glowGradient.setLocalMatrix(glowMatrix)
        glowPaint.shader = glowGradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentSweep = (animatedProgress / max) * sweepAngle

        // 1. Glow — blurred halo behind the progress
        if (currentSweep > 0) {
            canvas.drawArc(arcRect, startAngle, currentSweep, false, glowPaint)
        }

        // 2. Track — subtle full ring
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, trackPaint)

        // 3. Progress — crisp gradient arc
        if (currentSweep > 0) {
            canvas.drawArc(arcRect, startAngle, currentSweep, false, progressPaint)
        }
    }
}
