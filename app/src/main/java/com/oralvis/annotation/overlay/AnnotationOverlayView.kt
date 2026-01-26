package com.oralvis.annotation.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import com.oralvis.annotation.model.AnnotationBox
import com.oralvis.annotation.model.AnnotationLabel
import com.oralvis.annotation.model.ImageAnnotation
import com.oralvis.annotation.util.AnnotationLogger
import com.oralvis.annotation.util.CoordinateMapper
import java.util.UUID

/**
 * Custom overlay view for drawing and displaying annotation bounding boxes.
 * 
 * This view sits ABOVE the ImageView and handles:
 * - Drawing existing annotations
 * - Drawing the current box being created
 * - Touch handling for creating new boxes
 * - Touch handling for selecting existing boxes
 * 
 * All coordinates are converted between screen space and image space
 * using the associated ImageView's matrix.
 */
class AnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * Annotation mode determines what happens on touch events.
     */
    enum class Mode {
        VIEW,       // Tap to select existing annotations
        ANNOTATE    // Draw new bounding boxes
    }

    // ============= Configuration =============
    
    private var mode: Mode = Mode.VIEW
    private var imageView: ImageView? = null
    private var imageAnnotation: ImageAnnotation? = null
    
    // Image dimensions (original bitmap size)
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    
    // ============= Drawing State =============
    
    // Currently drawing box (in screen coordinates during draw)
    private var isDrawing = false
    private var drawStartX = 0f
    private var drawStartY = 0f
    private var drawCurrentX = 0f
    private var drawCurrentY = 0f
    
    // Selected annotation for VIEW mode
    private var selectedAnnotationId: String? = null
    
    // ============= Paints =============
    
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val selectedPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    
    private val drawingPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
    }
    
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    
    private val labelBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // ============= Callbacks =============
    
    var onAnnotationCreated: ((RectF) -> Unit)? = null
    var onAnnotationSelected: ((AnnotationBox?) -> Unit)? = null
    
    // ============= Public API =============
    
    /**
     * Set the ImageView that this overlay is associated with.
     * This is required for coordinate mapping.
     */
    fun setImageView(imageView: ImageView) {
        this.imageView = imageView
    }
    
    /**
     * Set the image dimensions for coordinate clamping.
     */
    fun setImageDimensions(width: Int, height: Int) {
        this.imageWidth = width
        this.imageHeight = height
    }
    
    /**
     * Set the current annotation mode.
     */
    fun setMode(mode: Mode) {
        this.mode = mode
        if (mode == Mode.ANNOTATE) {
            selectedAnnotationId = null
        }
        AnnotationLogger.logModeChanged(mode.name)
        invalidate()
    }
    
    /**
     * Get the current mode.
     */
    fun getMode(): Mode = mode
    
    /**
     * Set the annotation data to display.
     */
    fun setAnnotation(annotation: ImageAnnotation?) {
        this.imageAnnotation = annotation
        selectedAnnotationId = null
        invalidate()
    }
    
    /**
     * Get the currently selected annotation.
     */
    fun getSelectedAnnotation(): AnnotationBox? {
        val id = selectedAnnotationId ?: return null
        return imageAnnotation?.getAnnotation(id)
    }
    
    /**
     * Clear the current selection.
     */
    fun clearSelection() {
        selectedAnnotationId = null
        onAnnotationSelected?.invoke(null)
        invalidate()
    }
    
    /**
     * Refresh the view after annotation data changes.
     */
    fun refresh() {
        invalidate()
    }
    
    // ============= Drawing =============
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val iv = imageView ?: return
        
        // Draw existing annotations
        imageAnnotation?.annotations?.forEach { box ->
            drawAnnotationBox(canvas, box, iv)
        }
        
        // Draw currently drawing box
        if (isDrawing && mode == Mode.ANNOTATE) {
            drawCurrentDrawingBox(canvas)
        }
    }
    
    private fun drawAnnotationBox(canvas: Canvas, box: AnnotationBox, imageView: ImageView) {
        // Convert image rect to screen rect
        val screenRect = CoordinateMapper.imageRectToScreen(box.bbox, imageView)
        
        // Check if this box is selected
        val isSelected = box.id == selectedAnnotationId
        
        // Get color for this label
        val labelColor = AnnotationLabel.getColorForLabel(box.label)
        
        // Draw semi-transparent fill
        fillPaint.color = Color.argb(40, Color.red(labelColor), Color.green(labelColor), Color.blue(labelColor))
        canvas.drawRect(screenRect, fillPaint)
        
        // Draw border
        if (isSelected) {
            // Draw selected border (white dashed)
            canvas.drawRect(screenRect, selectedPaint)
            // Also draw colored border inside
            boxPaint.color = labelColor
            val insetRect = RectF(screenRect)
            insetRect.inset(3f, 3f)
            canvas.drawRect(insetRect, boxPaint)
        } else {
            boxPaint.color = labelColor
            canvas.drawRect(screenRect, boxPaint)
        }
        
        // Draw label background and text
        drawLabel(canvas, box.label, screenRect, labelColor)
    }
    
    private fun drawLabel(canvas: Canvas, label: String, boxRect: RectF, color: Int) {
        // Measure text
        val textBounds = Rect()
        labelPaint.getTextBounds(label, 0, label.length, textBounds)
        
        val padding = 8f
        val labelWidth = textBounds.width() + padding * 2
        val labelHeight = textBounds.height() + padding * 2
        
        // Position label at top of box (or bottom if no room at top)
        val labelLeft = boxRect.left
        val labelTop = if (boxRect.top - labelHeight >= 0) {
            boxRect.top - labelHeight
        } else {
            boxRect.bottom
        }
        
        // Draw background
        labelBackgroundPaint.color = color
        canvas.drawRect(
            labelLeft,
            labelTop,
            labelLeft + labelWidth,
            labelTop + labelHeight,
            labelBackgroundPaint
        )
        
        // Draw text
        canvas.drawText(
            label,
            labelLeft + padding,
            labelTop + labelHeight - padding,
            labelPaint
        )
    }
    
    private fun drawCurrentDrawingBox(canvas: Canvas) {
        val rect = RectF(
            minOf(drawStartX, drawCurrentX),
            minOf(drawStartY, drawCurrentY),
            maxOf(drawStartX, drawCurrentX),
            maxOf(drawStartY, drawCurrentY)
        )
        
        // Draw dashed rectangle for current drawing
        canvas.drawRect(rect, drawingPaint)
        
        // Draw corner handles
        val handleSize = 12f
        val handlePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        
        // Top-left
        canvas.drawCircle(rect.left, rect.top, handleSize, handlePaint)
        // Top-right
        canvas.drawCircle(rect.right, rect.top, handleSize, handlePaint)
        // Bottom-left
        canvas.drawCircle(rect.left, rect.bottom, handleSize, handlePaint)
        // Bottom-right
        canvas.drawCircle(rect.right, rect.bottom, handleSize, handlePaint)
    }
    
    // ============= Touch Handling =============
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (mode) {
            Mode.ANNOTATE -> handleAnnotateTouch(event)
            Mode.VIEW -> handleViewTouch(event)
        }
    }
    
    private fun handleAnnotateTouch(event: MotionEvent): Boolean {
        val iv = imageView ?: return false
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Only start drawing if touch is within image bounds
                if (CoordinateMapper.isPointInImageBounds(event.x, event.y, iv)) {
                    isDrawing = true
                    drawStartX = event.x
                    drawStartY = event.y
                    drawCurrentX = event.x
                    drawCurrentY = event.y
                    
                    AnnotationLogger.logDrawStart(event.x, event.y)
                    invalidate()
                    return true
                }
                return false
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    drawCurrentX = event.x
                    drawCurrentY = event.y
                    invalidate()
                    return true
                }
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDrawing) {
                    isDrawing = false
                    
                    // Create the screen rect
                    val screenRect = RectF(
                        minOf(drawStartX, drawCurrentX),
                        minOf(drawStartY, drawCurrentY),
                        maxOf(drawStartX, drawCurrentX),
                        maxOf(drawStartY, drawCurrentY)
                    )
                    
                    // Convert to image coordinates
                    val imageRect = CoordinateMapper.screenRectToImage(screenRect, iv)
                    
                    if (imageRect != null && imageWidth > 0 && imageHeight > 0) {
                        // Clamp to image bounds
                        val clampedRect = CoordinateMapper.clampToImageBounds(imageRect, imageWidth, imageHeight)
                        
                        // Validate box size (minimum 10x10 pixels)
                        if (CoordinateMapper.isValidBoundingBox(clampedRect, 10f)) {
                            // Notify that a new annotation was created
                            onAnnotationCreated?.invoke(clampedRect)
                        } else {
                            AnnotationLogger.logAnnotationDiscarded("Box too small: ${clampedRect.width()}x${clampedRect.height()}")
                        }
                    } else {
                        AnnotationLogger.logAnnotationDiscarded("Invalid image rect or dimensions")
                    }
                    
                    invalidate()
                    return true
                }
            }
        }
        
        return false
    }
    
    private fun handleViewTouch(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return false
        
        val iv = imageView ?: return false
        
        // Convert touch point to image coordinates
        val imagePoint = CoordinateMapper.screenToImage(event.x, event.y, iv) ?: return false
        
        // Find annotation at this point
        val tappedAnnotation = imageAnnotation?.findAnnotationAt(imagePoint.x, imagePoint.y)
        
        if (tappedAnnotation != null) {
            selectedAnnotationId = tappedAnnotation.id
            onAnnotationSelected?.invoke(tappedAnnotation)
            AnnotationLogger.d("Selected annotation: ${tappedAnnotation.id} (${tappedAnnotation.label})")
        } else {
            selectedAnnotationId = null
            onAnnotationSelected?.invoke(null)
            AnnotationLogger.d("Selection cleared (tapped empty area)")
        }
        
        invalidate()
        return true
    }
}
