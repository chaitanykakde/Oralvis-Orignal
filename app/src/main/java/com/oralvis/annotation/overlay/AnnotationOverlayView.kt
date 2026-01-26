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

/**
 * Production-grade overlay view for drawing and displaying annotation bounding boxes.
 * 
 * Sits on top of an ImageView and handles:
 * - Drawing existing annotations with labels
 * - Drawing new bounding boxes via touch
 * - Selection of existing annotations
 * - Proper coordinate mapping between screen and image space
 */
class AnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode {
        VIEW,       // Tap to select existing annotations
        ANNOTATE    // Draw new bounding boxes
    }

    // Configuration
    private var mode: Mode = Mode.VIEW
    private var imageView: ImageView? = null
    private var imageAnnotation: ImageAnnotation? = null
    
    // Image dimensions (original bitmap size)
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    
    // Drawing state
    private var isDrawing = false
    private var drawStartX = 0f
    private var drawStartY = 0f
    private var drawCurrentX = 0f
    private var drawCurrentY = 0f
    
    // Selection
    private var selectedAnnotationId: String? = null
    
    // Paints
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
        pathEffect = DashPathEffect(floatArrayOf(15f, 8f), 0f)
    }
    
    private val drawingPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    
    private val drawingFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(50, 255, 255, 255)
        isAntiAlias = true
    }
    
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    
    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // Callbacks
    var onAnnotationCreated: ((RectF) -> Unit)? = null
    var onAnnotationSelected: ((AnnotationBox?) -> Unit)? = null
    
    // ============= Public API =============
    
    fun setImageView(imageView: ImageView) {
        this.imageView = imageView
    }
    
    fun setImageDimensions(width: Int, height: Int) {
        this.imageWidth = width
        this.imageHeight = height
        AnnotationLogger.d("Image dimensions set: ${width}x${height}")
    }
    
    fun setMode(mode: Mode) {
        this.mode = mode
        if (mode == Mode.ANNOTATE) {
            selectedAnnotationId = null
        }
        AnnotationLogger.logModeChanged(mode.name)
        invalidate()
    }
    
    fun getMode(): Mode = mode
    
    fun setAnnotation(annotation: ImageAnnotation?) {
        this.imageAnnotation = annotation
        selectedAnnotationId = null
        invalidate()
    }
    
    fun getSelectedAnnotation(): AnnotationBox? {
        val id = selectedAnnotationId ?: return null
        return imageAnnotation?.getAnnotation(id)
    }
    
    fun clearSelection() {
        selectedAnnotationId = null
        onAnnotationSelected?.invoke(null)
        invalidate()
    }
    
    fun refresh() {
        invalidate()
    }
    
    // ============= Coordinate Mapping =============
    
    /**
     * Get the transformation matrix from image coordinates to screen coordinates.
     */
    private fun getImageToScreenMatrix(): Matrix? {
        val iv = imageView ?: return null
        val drawable = iv.drawable ?: return null
        
        val viewWidth = iv.width.toFloat()
        val viewHeight = iv.height.toFloat()
        val imgWidth = drawable.intrinsicWidth.toFloat()
        val imgHeight = drawable.intrinsicHeight.toFloat()
        
        if (viewWidth <= 0 || viewHeight <= 0 || imgWidth <= 0 || imgHeight <= 0) {
            return null
        }
        
        // Calculate scale to fit (FIT_CENTER behavior)
        val scale = minOf(viewWidth / imgWidth, viewHeight / imgHeight)
        
        // Calculate offset to center
        val scaledWidth = imgWidth * scale
        val scaledHeight = imgHeight * scale
        val offsetX = (viewWidth - scaledWidth) / 2f
        val offsetY = (viewHeight - scaledHeight) / 2f
        
        // Create matrix: scale then translate
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(offsetX, offsetY)
        
        return matrix
    }
    
    /**
     * Convert screen point to image coordinates.
     */
    private fun screenToImage(screenX: Float, screenY: Float): PointF? {
        val matrix = getImageToScreenMatrix() ?: return null
        val inverse = Matrix()
        if (!matrix.invert(inverse)) return null
        
        val pts = floatArrayOf(screenX, screenY)
        inverse.mapPoints(pts)
        
        return PointF(pts[0], pts[1])
    }
    
    /**
     * Convert image point to screen coordinates.
     */
    private fun imageToScreen(imageX: Float, imageY: Float): PointF {
        val matrix = getImageToScreenMatrix() ?: return PointF(imageX, imageY)
        val pts = floatArrayOf(imageX, imageY)
        matrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }
    
    /**
     * Convert screen rect to image rect.
     */
    private fun screenRectToImage(screenRect: RectF): RectF? {
        val matrix = getImageToScreenMatrix() ?: return null
        val inverse = Matrix()
        if (!matrix.invert(inverse)) return null
        
        val imageRect = RectF()
        inverse.mapRect(imageRect, screenRect)
        return imageRect
    }
    
    /**
     * Convert image rect to screen rect.
     */
    private fun imageRectToScreen(imageRect: RectF): RectF {
        val matrix = getImageToScreenMatrix() ?: return imageRect
        val screenRect = RectF()
        matrix.mapRect(screenRect, imageRect)
        return screenRect
    }
    
    /**
     * Check if screen point is within the displayed image bounds.
     */
    private fun isPointInImageBounds(screenX: Float, screenY: Float): Boolean {
        val imagePoint = screenToImage(screenX, screenY) ?: return false
        return imagePoint.x >= 0 && imagePoint.x <= imageWidth &&
               imagePoint.y >= 0 && imagePoint.y <= imageHeight
    }
    
    /**
     * Clamp rect to image bounds.
     */
    private fun clampToImageBounds(rect: RectF): RectF {
        val clamped = RectF(
            rect.left.coerceIn(0f, imageWidth.toFloat()),
            rect.top.coerceIn(0f, imageHeight.toFloat()),
            rect.right.coerceIn(0f, imageWidth.toFloat()),
            rect.bottom.coerceIn(0f, imageHeight.toFloat())
        )
        
        // Ensure proper ordering
        if (clamped.left > clamped.right) {
            val tmp = clamped.left
            clamped.left = clamped.right
            clamped.right = tmp
        }
        if (clamped.top > clamped.bottom) {
            val tmp = clamped.top
            clamped.top = clamped.bottom
            clamped.bottom = tmp
        }
        
        return clamped
    }
    
    // ============= Drawing =============
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw existing annotations
        imageAnnotation?.annotations?.forEach { box ->
            drawAnnotationBox(canvas, box)
        }
        
        // Draw current drawing box
        if (isDrawing && mode == Mode.ANNOTATE) {
            drawCurrentBox(canvas)
        }
    }
    
    private fun drawAnnotationBox(canvas: Canvas, box: AnnotationBox) {
        val screenRect = imageRectToScreen(box.bbox)
        val isSelected = box.id == selectedAnnotationId
        val labelColor = AnnotationLabel.getColorForLabel(box.label)
        
        // Draw semi-transparent fill
        fillPaint.color = Color.argb(40, Color.red(labelColor), Color.green(labelColor), Color.blue(labelColor))
        canvas.drawRect(screenRect, fillPaint)
        
        // Draw border
        if (isSelected) {
            canvas.drawRect(screenRect, selectedPaint)
            boxPaint.color = labelColor
            val inset = RectF(screenRect)
            inset.inset(3f, 3f)
            canvas.drawRect(inset, boxPaint)
        } else {
            boxPaint.color = labelColor
            canvas.drawRect(screenRect, boxPaint)
        }
        
        // Draw label
        drawLabel(canvas, box.label, screenRect, labelColor)
    }
    
    private fun drawLabel(canvas: Canvas, label: String, boxRect: RectF, color: Int) {
        val textBounds = Rect()
        labelPaint.getTextBounds(label, 0, label.length, textBounds)
        
        val padding = 6f
        val labelWidth = textBounds.width() + padding * 2
        val labelHeight = textBounds.height() + padding * 2
        
        // Position above box, or below if no room
        val labelLeft = boxRect.left
        val labelTop = if (boxRect.top - labelHeight >= 0) {
            boxRect.top - labelHeight
        } else {
            boxRect.bottom
        }
        
        // Background
        labelBgPaint.color = color
        canvas.drawRect(labelLeft, labelTop, labelLeft + labelWidth, labelTop + labelHeight, labelBgPaint)
        
        // Text
        canvas.drawText(label, labelLeft + padding, labelTop + labelHeight - padding, labelPaint)
    }
    
    private fun drawCurrentBox(canvas: Canvas) {
        val rect = RectF(
            minOf(drawStartX, drawCurrentX),
            minOf(drawStartY, drawCurrentY),
            maxOf(drawStartX, drawCurrentX),
            maxOf(drawStartY, drawCurrentX)
        )
        
        // Draw fill and border
        canvas.drawRect(rect, drawingFillPaint)
        canvas.drawRect(rect, drawingPaint)
        
        // Draw corner handles
        val handleRadius = 8f
        val handlePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawCircle(rect.left, rect.top, handleRadius, handlePaint)
        canvas.drawCircle(rect.right, rect.top, handleRadius, handlePaint)
        canvas.drawCircle(rect.left, rect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(rect.right, rect.bottom, handleRadius, handlePaint)
    }
    
    // ============= Touch Handling =============
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (mode) {
            Mode.ANNOTATE -> handleAnnotateTouch(event)
            Mode.VIEW -> handleViewTouch(event)
        }
    }
    
    private fun handleAnnotateTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (isPointInImageBounds(event.x, event.y)) {
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
                    
                    // Create screen rect
                    val screenRect = RectF(
                        minOf(drawStartX, drawCurrentX),
                        minOf(drawStartY, drawCurrentY),
                        maxOf(drawStartX, drawCurrentX),
                        maxOf(drawStartY, drawCurrentY)
                    )
                    
                    // Convert to image coordinates
                    val imageRect = screenRectToImage(screenRect)
                    
                    if (imageRect != null) {
                        // Clamp to image bounds
                        val clampedRect = clampToImageBounds(imageRect)
                        
                        // Validate minimum size (10x10 pixels in image space)
                        if (clampedRect.width() >= 10f && clampedRect.height() >= 10f) {
                            AnnotationLogger.logDrawEnd(screenRect.toString(), clampedRect.toString())
                            onAnnotationCreated?.invoke(clampedRect)
                        } else {
                            AnnotationLogger.logAnnotationDiscarded("Box too small: ${clampedRect.width()}x${clampedRect.height()}")
                        }
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
        
        // Convert tap to image coordinates
        val imagePoint = screenToImage(event.x, event.y) ?: return false
        
        // Find annotation at this point
        val tapped = imageAnnotation?.findAnnotationAt(imagePoint.x, imagePoint.y)
        
        selectedAnnotationId = tapped?.id
        onAnnotationSelected?.invoke(tapped)
        
        invalidate()
        return true
    }
}
