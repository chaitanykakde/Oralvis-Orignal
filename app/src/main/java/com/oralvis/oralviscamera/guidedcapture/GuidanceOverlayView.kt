package com.oralvis.oralviscamera.guidedcapture

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import com.oralvis.oralviscamera.R

/**
 * Lightweight overlay view that mimics the Windows app AutoCapture UI:
 *  - Center target box (AssistiveMarker) with dynamic colors, border styles, and glow animation
 *  - Top instruction text
 *  - Left-bottom panel with arch icon, progress text, main & recapture buttons
 *
 * Updated to match Windows app: feedback moved from bottom bar to central marker.
 * This view is purely presentational; click handling is forwarded via listeners.
 */
class GuidanceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onMainButtonClicked()
        fun onRecaptureButtonClicked()
    }

    var listener: Listener? = null

    // State from GuidedSessionController / AutoCaptureController
    var scanningState: ScanningState = ScanningState.READY_TO_SCAN_LOWER
        set(value) {
            field = value
            invalidate()
        }

    var mainText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var buttonText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var progressText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var guidanceResult: GuidanceResult? = null
        set(value) {
            field = value
            updateGlowAnimation(value)
            invalidate()
        }

    var showFlash: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Arch icons are optional; callers can provide bitmaps loaded from resources.
    var lowerArchIcon: Bitmap? = null
    var upperArchIcon: Bitmap? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 30, 30, 35)
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 80, 80)
    }
    private val recapturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 50, 50, 50)
        style = Paint.Style.FILL
    }
    private val recaptureStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 150)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // Cached button hit-areas
    private val mainButtonRect = Rect()
    private val recaptureButtonRect = Rect()
    
    // Glow animation for "arming" state
    private var glowAnimator: ValueAnimator? = null
    private var glowAlpha: Float = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height

        drawTopInstruction(canvas, w)
        drawControlPanel(canvas, w, h)
        drawTargetBox(canvas, w, h)
        if (showFlash) {
            drawFlashOverlay(canvas, w, h)
        }
    }

    /**
     * Update glow animation based on guidance result.
     * Glow pulses when prompt contains "Hold steady" (arming state).
     */
    private fun updateGlowAnimation(result: GuidanceResult?) {
        val shouldGlow = result?.prompt?.contains("Hold steady", ignoreCase = true) == true
        
        if (shouldGlow && glowAnimator == null) {
            // Start pulsing glow animation
            glowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000L // 1 second cycle
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { animator ->
                    glowAlpha = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!shouldGlow && glowAnimator != null) {
            // Stop animation
            glowAnimator?.cancel()
            glowAnimator = null
            glowAlpha = 0f
        }
    }

    private fun drawTopInstruction(canvas: Canvas, w: Int) {
        if (mainText.isEmpty()) return
        textPaint.textSize = 42f
        val textWidth = textPaint.measureText(mainText)
        val x = (w - textWidth) / 2f
        val y = 80f
        // Simple shadow effect for readability
        textPaint.color = Color.BLACK
        canvas.drawText(mainText, x + 2, y + 2, textPaint)
        textPaint.color = Color.WHITE
        canvas.drawText(mainText, x, y, textPaint)
    }

    private fun drawControlPanel(canvas: Canvas, w: Int, h: Int) {
        val panelWidth = (w * 0.22f).toInt()
        val panelHeight = (h * 0.28f).toInt()
        val paddingX = (w * 0.05f).toInt()
        val paddingY = (h * 0.12f).toInt()

        val left = paddingX
        val top = h - panelHeight - paddingY
        val right = left + panelWidth
        val bottom = top + panelHeight

        // Dark card-like panel
        canvas.drawRoundRect(
            left.toFloat(),
            top.toFloat(),
            right.toFloat(),
            bottom.toFloat(),
            20f,
            20f,
            panelPaint
        )

        // Arch icon
        val icon = when (scanningState) {
            ScanningState.READY_TO_SCAN_LOWER,
            ScanningState.SCANNING_LOWER -> lowerArchIcon

            ScanningState.READY_TO_SCAN_UPPER,
            ScanningState.SCANNING_UPPER,
            ScanningState.COMPLETE -> upperArchIcon
        }
        icon?.let {
            val iconSize = (panelHeight * 0.38f).toInt()
            val scale = iconSize.toFloat() / it.height.toFloat()
            val iconW = (it.width * scale).toInt()
            val iconLeft = left + (panelWidth - iconW) / 2
            val iconTop = top + (panelHeight * 0.12f).toInt()

            val dst = Rect(
                iconLeft,
                iconTop,
                iconLeft + iconW,
                iconTop + iconSize
            )
            canvas.drawBitmap(it, null, dst, null)
        }

        // Progress text ("1/2", "2/2", "Done")
        if (progressText.isNotEmpty()) {
            textPaint.textSize = 40f
            val tw = textPaint.measureText(progressText)
            val tx = left + (panelWidth - tw) / 2f
            val ty = top + panelHeight * 0.65f
            canvas.drawText(progressText, tx, ty, textPaint)
        }

        // Main button
        val btnHeight = (panelHeight * 0.2f).toInt()
        val btnTop = top + (panelHeight * 0.72f).toInt()
        val btnLeft = left + (panelWidth * 0.08f).toInt()
        val btnRight = left + (panelWidth * 0.92f).toInt()
        val btnBottom = btnTop + btnHeight

        mainButtonRect.set(btnLeft, btnTop, btnRight, btnBottom)
        canvas.drawRoundRect(
            btnLeft.toFloat(),
            btnTop.toFloat(),
            btnRight.toFloat(),
            btnBottom.toFloat(),
            18f,
            18f,
            buttonPaint
        )

        if (buttonText.isNotEmpty()) {
            textPaint.textSize = 32f
            val textW = textPaint.measureText(buttonText)
            val tx = btnLeft + (btnRight - btnLeft - textW) / 2f
            val ty = btnTop + btnHeight / 2f + textPaint.textSize / 3f
            canvas.drawText(buttonText, tx, ty, textPaint)
        }

        // Recapture button is visible only in READY_TO_SCAN_UPPER or COMPLETE
        if (scanningState == ScanningState.READY_TO_SCAN_UPPER ||
            scanningState == ScanningState.COMPLETE
        ) {
            val rBtnHeight = (panelHeight * 0.16f).toInt()
            val rBtnTop = btnBottom + (panelHeight * 0.04f).toInt()
            val rBtnLeft = btnLeft
            val rBtnRight = btnRight
            val rBtnBottom = rBtnTop + rBtnHeight

            recaptureButtonRect.set(rBtnLeft, rBtnTop, rBtnRight, rBtnBottom)
            // Fill
            canvas.drawRoundRect(
                rBtnLeft.toFloat(),
                rBtnTop.toFloat(),
                rBtnRight.toFloat(),
                rBtnBottom.toFloat(),
                16f,
                16f,
                recapturePaint
            )
            // Stroke
            canvas.drawRoundRect(
                rBtnLeft.toFloat(),
                rBtnTop.toFloat(),
                rBtnRight.toFloat(),
                rBtnBottom.toFloat(),
                16f,
                16f,
                recaptureStrokePaint
            )

            val label = context.getString(R.string.guided_recapture_label)
            textPaint.textSize = 30f
            val labelW = textPaint.measureText(label)
            val lx = rBtnLeft + (rBtnRight - rBtnLeft - labelW) / 2f
            val ly = rBtnTop + rBtnHeight / 2f + textPaint.textSize / 3f
            canvas.drawText(label, lx, ly, textPaint)
        } else {
            recaptureButtonRect.setEmpty()
        }
    }

    private fun drawTargetBox(canvas: Canvas, w: Int, h: Int) {
        val result = guidanceResult
        val boxSize = (h * 0.3f).toInt()
        val cx = w / 2
        val cy = h / 2
        val left = cx - boxSize / 2
        val top = cy - boxSize / 2
        val right = cx + boxSize / 2
        val bottom = cy + boxSize / 2
        
        // Determine border color from guidance result (default to amber/yellow)
        val borderColor = result?.color ?: Color.rgb(255, 200, 80)
        
        // Determine border style based on prompt
        val prompt = result?.prompt ?: ""
        val isDashed = prompt.contains("Slow down", ignoreCase = true) || 
                       prompt.contains("Keep steady", ignoreCase = true)
        val isDotted = prompt.contains("Keep steady", ignoreCase = true) && 
                       !prompt.contains("Slow down", ignoreCase = true)
        
        // Draw pulsing glow effect for "arming" state
        if (glowAlpha > 0f && prompt.contains("Hold steady", ignoreCase = true)) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 8f
                color = borderColor
                alpha = (glowAlpha * 100).toInt() // 0-100 alpha for subtle glow
            }
            val glowRect = RectF(
                left - 10f,
                top - 10f,
                right + 10f,
                bottom + 10f
            )
            canvas.drawRoundRect(glowRect, 8f, 8f, glowPaint)
        }
        
        // Draw border with appropriate style
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = borderColor
        }
        
        if (isDotted) {
            // Dotted border for "Keep steady" warnings
            boxPaint.pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
        } else if (isDashed) {
            // Dashed border for "Slow down" warnings
            boxPaint.pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
        } else {
            // Solid border for "Ready" and "Hold steady to capture..."
            boxPaint.pathEffect = null
        }
        
        val rect = RectF(
            left.toFloat(),
            top.toFloat(),
            right.toFloat(),
            bottom.toFloat()
        )
        canvas.drawRoundRect(rect, 8f, 8f, boxPaint)
    }

    private fun drawFlashOverlay(canvas: Canvas, w: Int, h: Int) {
        val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        val radius = (h * 0.18f)
        val alphaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 90
        }

        val temp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(temp)
        c.drawCircle(0f, 0f, radius, overlayPaint)
        c.drawCircle(w.toFloat(), 0f, radius, overlayPaint)
        c.drawCircle(0f, h.toFloat(), radius, overlayPaint)
        c.drawCircle(w.toFloat(), h.toFloat(), radius, overlayPaint)

        canvas.drawBitmap(temp, 0f, 0f, alphaPaint)
        temp.recycle()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x.toInt()
            val y = event.y.toInt()
            if (mainButtonRect.contains(x, y)) {
                listener?.onMainButtonClicked()
                return true
            }
            if (!recaptureButtonRect.isEmpty && recaptureButtonRect.contains(x, y)) {
                listener?.onRecaptureButtonClicked()
                return true
            }
        }
        return true
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glowAnimator?.cancel()
        glowAnimator = null
    }
}


