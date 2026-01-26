package com.oralvis.annotation.util

import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.widget.ImageView

/**
 * Utility class for mapping coordinates between screen space and image space.
 * 
 * This is CRITICAL for annotation accuracy. All bounding boxes must be stored
 * in original image pixel coordinates, regardless of:
 * - Screen size
 * - Zoom level
 * - Pan position
 * - Device resolution
 * 
 * Uses ImageView matrix inversion to map screen coordinates to image coordinates.
 */
object CoordinateMapper {

    /**
     * Map a screen point to image coordinates.
     * 
     * @param screenX X coordinate on screen
     * @param screenY Y coordinate on screen  
     * @param imageView The ImageView displaying the image
     * @return PointF in image pixel coordinates, or null if mapping fails
     */
    fun screenToImage(screenX: Float, screenY: Float, imageView: ImageView): PointF? {
        val drawable = imageView.drawable ?: return null
        
        // Get the inverse of the image matrix
        val inverseMatrix = Matrix()
        if (!imageView.imageMatrix.invert(inverseMatrix)) {
            AnnotationLogger.e("Failed to invert image matrix")
            return null
        }
        
        // Map the point through the inverse matrix
        val points = floatArrayOf(screenX, screenY)
        inverseMatrix.mapPoints(points)
        
        val imageX = points[0]
        val imageY = points[1]
        
        AnnotationLogger.logCoordinateMapping(
            screenCoords = "($screenX, $screenY)",
            imageCoords = "(${imageX}, ${imageY})",
            scaleFactor = getScaleFactor(imageView),
            translation = getTranslation(imageView)
        )
        
        return PointF(imageX, imageY)
    }

    /**
     * Map an image point to screen coordinates.
     * 
     * @param imageX X coordinate in image pixels
     * @param imageY Y coordinate in image pixels
     * @param imageView The ImageView displaying the image
     * @return PointF in screen coordinates
     */
    fun imageToScreen(imageX: Float, imageY: Float, imageView: ImageView): PointF {
        val points = floatArrayOf(imageX, imageY)
        imageView.imageMatrix.mapPoints(points)
        return PointF(points[0], points[1])
    }

    /**
     * Map a screen rectangle to image coordinates.
     * 
     * @param screenRect Rectangle in screen coordinates
     * @param imageView The ImageView displaying the image
     * @return RectF in image pixel coordinates, or null if mapping fails
     */
    fun screenRectToImage(screenRect: RectF, imageView: ImageView): RectF? {
        val drawable = imageView.drawable ?: return null
        
        val inverseMatrix = Matrix()
        if (!imageView.imageMatrix.invert(inverseMatrix)) {
            AnnotationLogger.e("Failed to invert image matrix for rect mapping")
            return null
        }
        
        val imageRect = RectF()
        inverseMatrix.mapRect(imageRect, screenRect)
        
        AnnotationLogger.logDrawEnd(
            screenRect = screenRect.toString(),
            imageRect = imageRect.toString()
        )
        
        return imageRect
    }

    /**
     * Map an image rectangle to screen coordinates.
     * 
     * @param imageRect Rectangle in image pixel coordinates
     * @param imageView The ImageView displaying the image
     * @return RectF in screen coordinates
     */
    fun imageRectToScreen(imageRect: RectF, imageView: ImageView): RectF {
        val screenRect = RectF()
        imageView.imageMatrix.mapRect(screenRect, imageRect)
        return screenRect
    }

    /**
     * Clamp a rectangle to be within the image bounds.
     * 
     * @param rect Rectangle in image coordinates
     * @param imageWidth Width of the image in pixels
     * @param imageHeight Height of the image in pixels
     * @return Clamped rectangle
     */
    fun clampToImageBounds(rect: RectF, imageWidth: Int, imageHeight: Int): RectF {
        val clamped = RectF(rect)
        
        // Clamp left and right
        clamped.left = clamped.left.coerceIn(0f, imageWidth.toFloat())
        clamped.right = clamped.right.coerceIn(0f, imageWidth.toFloat())
        
        // Clamp top and bottom
        clamped.top = clamped.top.coerceIn(0f, imageHeight.toFloat())
        clamped.bottom = clamped.bottom.coerceIn(0f, imageHeight.toFloat())
        
        // Ensure left < right and top < bottom
        if (clamped.left > clamped.right) {
            val temp = clamped.left
            clamped.left = clamped.right
            clamped.right = temp
        }
        if (clamped.top > clamped.bottom) {
            val temp = clamped.top
            clamped.top = clamped.bottom
            clamped.bottom = temp
        }
        
        return clamped
    }

    /**
     * Check if a point is within the visible image bounds on screen.
     * 
     * @param screenX X coordinate on screen
     * @param screenY Y coordinate on screen
     * @param imageView The ImageView displaying the image
     * @return true if the point is within the visible image area
     */
    fun isPointInImageBounds(screenX: Float, screenY: Float, imageView: ImageView): Boolean {
        val drawable = imageView.drawable ?: return false
        
        val imagePoint = screenToImage(screenX, screenY, imageView) ?: return false
        
        val imageWidth = drawable.intrinsicWidth
        val imageHeight = drawable.intrinsicHeight
        
        return imagePoint.x >= 0 && imagePoint.x <= imageWidth &&
               imagePoint.y >= 0 && imagePoint.y <= imageHeight
    }

    /**
     * Get the current scale factor of the image view.
     */
    fun getScaleFactor(imageView: ImageView): Float {
        val values = FloatArray(9)
        imageView.imageMatrix.getValues(values)
        val scaleX = values[Matrix.MSCALE_X]
        val scaleY = values[Matrix.MSCALE_Y]
        return (scaleX + scaleY) / 2f
    }

    /**
     * Get the current translation as a string for logging.
     */
    private fun getTranslation(imageView: ImageView): String {
        val values = FloatArray(9)
        imageView.imageMatrix.getValues(values)
        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]
        return "($transX, $transY)"
    }

    /**
     * Get the drawable bounds in screen coordinates.
     */
    fun getDrawableBoundsOnScreen(imageView: ImageView): RectF? {
        val drawable = imageView.drawable ?: return null
        
        val imageRect = RectF(
            0f, 0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )
        
        val screenRect = RectF()
        imageView.imageMatrix.mapRect(screenRect, imageRect)
        
        return screenRect
    }

    /**
     * Validate that a bounding box is reasonable (not too small).
     * 
     * @param rect Rectangle in image coordinates
     * @param minSize Minimum size in pixels (width and height must both exceed this)
     * @return true if the rectangle is large enough
     */
    fun isValidBoundingBox(rect: RectF, minSize: Float = 10f): Boolean {
        return rect.width() >= minSize && rect.height() >= minSize
    }

    /**
     * Log the current image matrix values for debugging.
     */
    fun logImageMatrix(imageView: ImageView) {
        val values = FloatArray(9)
        imageView.imageMatrix.getValues(values)
        
        val matrixString = """
            ScaleX: ${values[Matrix.MSCALE_X]}, ScaleY: ${values[Matrix.MSCALE_Y]}
            TransX: ${values[Matrix.MTRANS_X]}, TransY: ${values[Matrix.MTRANS_Y]}
            SkewX: ${values[Matrix.MSKEW_X]}, SkewY: ${values[Matrix.MSKEW_Y]}
        """.trimIndent()
        
        AnnotationLogger.logImageMatrix(matrixString)
    }
}
